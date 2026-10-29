package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.util.RuleParser;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 平台连接只用于本地读取；外部连接独立创建，绝不修改 Spring 主数据源。 */
@Component
public class MysqlSourceClient
{
    @Autowired private javax.sql.DataSource localDataSource;
    @Autowired private DataSourceMapper sources;
    @Autowired private SourceCredentialCipher cipher;
    @Autowired private SourcePolicy policy;
    private final Semaphore executions = new Semaphore(2);
    private static final ScheduledExecutorService DEADLINES = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "dq-source-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public Session session(boolean external)
    {
        if (external && !executions.tryAcquire()) throw new ServiceException("外部检测繁忙，请稍后重试（最多同时 2 次）");
        return new Session(external);
    }

    public DataSource requireSource(Integer id)
    {
        DataSource source = sources.selectDataSourceById(id);
        if (source == null) throw new ServiceException("数据源不存在或已删除");
        if (source.getHost() == null || source.getPasswordCipher() == null)
            throw new ServiceException("旧数据源尚未配置外部连接");
        if (!Boolean.TRUE.equals(source.getEnabled())) throw new ServiceException("数据源已停用");
        return source;
    }

    private Connection open(DataSource source, String password) throws SQLException
    {
        policy.validateResolvedAddresses(source);
        Properties p = new Properties();
        p.setProperty("user", source.getUsername());
        p.setProperty("password", password);
        p.setProperty("sslMode", source.getTlsMode());
        p.setProperty("serverTimezone", source.getTimeZone());
        p.setProperty("connectionTimeZone", source.getTimeZone());
        p.setProperty("forceConnectionTimeZoneToSession", "true");
        p.setProperty("connectTimeout", "5000");
        p.setProperty("socketTimeout", "15000");
        p.setProperty("allowMultiQueries", "false");
        p.setProperty("allowLoadLocalInfile", "false");
        p.setProperty("allowUrlInLocalInfile", "false");
        p.setProperty("allowLoadLocalInfileInPath", "");
        p.setProperty("autoDeserialize", "false");
        p.setProperty("allowPublicKeyRetrieval", "false");
        p.setProperty("useServerPrepStmts", "true");
        Connection connection = DriverManager.getConnection("jdbc:mysql://" + source.getHost() + ":"
                + source.getPort() + "/" + source.getDatabaseName(), p);
        try { connection.setReadOnly(true); return connection; }
        catch (SQLException e) { connection.close(); throw e; }
        finally { p.remove("password"); }
    }

    /** 会话仅在查询层使用，不可注入 DRL global。 */
    public class Session implements AutoCloseable
    {
        private final boolean external;
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        private final Map<Integer, Connection> connections = new LinkedHashMap<>();
        private final Map<Integer, DataSource> configs = new HashMap<>();
        private final Map<String, SourceTable> tables = new HashMap<>();
        private final ScheduledFuture<?> timeout;
        private volatile boolean expired;
        private volatile boolean closed;

        private Session(boolean external)
        {
            this.external = external;
            timeout = DEADLINES.schedule(() -> {
                expired = true;
                synchronized (connections)
                {
                    for (Connection c : connections.values())
                    {
                        try { c.abort(Runnable::run); } catch (SQLException ignored) { }
                    }
                }
            }, 60, TimeUnit.SECONDS);
        }

        public void checkBudget()
        {
            if (closed) throw new ServiceException("数据源会话已关闭，不可继续查询");
            if (expired || System.nanoTime() >= deadline)
                throw new ServiceException("外部查询超过 60 秒总预算，检测未完成");
        }

        public DataSource config(Integer id)
        {
            checkBudget();
            if (id == null) return null;
            return configs.computeIfAbsent(id, MysqlSourceClient.this::requireSource);
        }

        private Connection connection(Integer id)
        {
            checkBudget();
            synchronized (connections)
            {
                Connection cached = connections.get(id);
                if (cached != null) return cached;
                try
                {
                    Connection c = id == null ? localDataSource.getConnection()
                            : open(config(id), cipher.decrypt(config(id).getPasswordCipher()));
                    connections.put(id, c);
                    checkBudget();
                    return c;
                }
                catch (SQLException e) { throw queryFailure(); }
            }
        }

        public void testDraft(DataSource source, String password)
        {
            checkBudget();
            try (Connection c = open(source, password); PreparedStatement ps = c.prepareStatement("SELECT 1"))
            {
                ps.setQueryTimeout(10);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw queryFailure(); }
                checkBudget();
            }
            catch (SQLException e) { throw queryFailure(); }
        }

        public String database(Integer id, String requested)
        {
            if (id != null)
            {
                String bound = config(id).getDatabaseName();
                if (requested != null && !requested.trim().isEmpty() && !bound.equals(requested))
                    throw new ServiceException("数据源只允许访问绑定数据库");
                return bound;
            }
            String db = requested;
            if (db == null || db.trim().isEmpty())
            {
                try { db = connection(null).getCatalog(); }
                catch (SQLException e) { throw queryFailure(); }
            }
            SourcePolicy.identifier(db);
            if (query(null, "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                    Collections.singletonList(db), 1).isEmpty())
                throw new ServiceException("指定数据库不存在或无权访问，不会回退到默认库");
            return db;
        }

        public Map<String, Object> listTables(Integer id, String dbName, String search, int page, int size)
        {
            String db = database(id, dbName);
            int bounded = Math.max(1, Math.min(size, 100));
            int offset = Math.max(0, Math.min(page - 1, 100000)) * bounded;
            List<Object> params = Arrays.asList(db, "%" + (search == null ? "" : search) + "%");
            String where = " FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME LIKE ?";
            List<Map<String, Object>> count = query(id, "SELECT COUNT(*) AS total" + where, params, 1);
            List<Object> paging = new ArrayList<>(params);
            paging.add(bounded);
            paging.add(offset);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", count.get(0).get("total"));
            result.put("rows", query(id, "SELECT TABLE_NAME AS tableName, TABLE_COMMENT AS comment, TABLE_TYPE AS tableType"
                    + where + " ORDER BY TABLE_NAME LIMIT ? OFFSET ?", paging, bounded));
            result.put("databaseName", db);
            return result;
        }

        public SourceTable table(Integer id, String dbName, String name)
        {
            SourcePolicy.identifier(name);
            String db = database(id, dbName);
            String cacheKey = id + ":" + db + ":" + name;
            if (tables.containsKey(cacheKey)) return tables.get(cacheKey);
            List<Map<String, Object>> rows = query(id,
                    "SELECT COLUMN_NAME AS name, DATA_TYPE AS type, IS_NULLABLE AS nullable, COLUMN_COMMENT AS comment"
                    + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                    Arrays.asList(db, name), 4096);
            if (rows.isEmpty()) throw new ServiceException("表不存在或无权读取其字段");
            SourceTable result = new SourceTable();
            result.setDatabaseName(db);
            result.setTableName(name);
            for (Map<String, Object> row : rows)
            {
                SourceTable.Column col = new SourceTable.Column();
                col.setName((String) row.get("name"));
                col.setType((String) row.get("type"));
                col.setComment((String) row.get("comment"));
                col.setNullable("YES".equals(row.get("nullable")));
                result.getColumns().add(col);
            }
            Map<String, SourceTable.Index> indexes = new LinkedHashMap<>();
            for (Map<String, Object> row : query(id,
                    "SELECT INDEX_NAME AS name, NON_UNIQUE AS nonUnique, COLUMN_NAME AS col, SUB_PART AS prefixLength"
                    + " FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX",
                    Arrays.asList(db, name), 4096))
            {
                String indexName = (String) row.get("name");
                SourceTable.Index index = indexes.computeIfAbsent(indexName, k -> new SourceTable.Index());
                index.setName(indexName);
                index.setUnique(((Number) row.get("nonUnique")).intValue() == 0);
                index.setPrefix(index.isPrefix() || row.get("prefixLength") != null || row.get("col") == null);
                index.getColumns().add((String) row.get("col"));
            }
            result.setIndexes(new ArrayList<>(indexes.values()));
            tables.put(cacheKey, result);
            return result;
        }

        public List<Map<String, Object>> scan(Integer id, SourceTable table, int limit)
        {
            int bounded = Math.max(1, Math.min(limit, 10000));
            List<String> pk = table.primaryKey();
            String order = pk.isEmpty() ? "" : " ORDER BY " + pk.stream().map(SourcePolicy::identifier).collect(Collectors.joining(","));
            List<Map<String, Object>> rows = query(id, "SELECT * FROM " + qualified(table) + order + " LIMIT ?",
                    Collections.singletonList(bounded), bounded);
            for (int i = 0; i < rows.size(); i++) rows.get(i).put(RuleParser.ROW_ID_KEY, i + 1);
            return rows;
        }

        public List<Map<String, Object>> reference(Integer id, SourceTable table, String key, String value, List<Object> keys)
        {
            table.column(key);
            if (value != null) table.column(value);
            if (keys.isEmpty()) return Collections.emptyList();
            if (keys.size() > 200) throw new ServiceException("参考查询每批最多 200 个键");
            String fields = SourcePolicy.identifier(key) + (value == null || value.equals(key) ? "" : "," + SourcePolicy.identifier(value));
            String marks = String.join(",", Collections.nCopies(keys.size(), "?"));
            return query(id, "SELECT " + fields + " FROM " + qualified(table) + " WHERE "
                    + SourcePolicy.identifier(key) + " IN (" + marks + ")", keys, 100000);
        }

        private String qualified(SourceTable table)
        {
            return SourcePolicy.identifier(table.getDatabaseName()) + "." + SourcePolicy.identifier(table.getTableName());
        }

        private List<Map<String, Object>> query(Integer id, String sql, List<?> values, int maxRows)
        {
            checkBudget();
            try (PreparedStatement ps = connection(id).prepareStatement(sql))
            {
                int remaining = (int) Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
                ps.setQueryTimeout(Math.min(10, remaining));
                ps.setMaxRows(maxRows + 1);
                for (int i = 0; i < values.size(); i++) ps.setObject(i + 1, values.get(i));
                try (ResultSet rs = ps.executeQuery())
                {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    ResultSetMetaData meta = rs.getMetaData();
                    while (rs.next())
                    {
                        checkBudget();
                        if (rows.size() == maxRows) throw new ServiceException("参考或元数据查询结果过大，检测未完成");
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++)
                        {
                            // DATETIME 保留本地时间语义，TIMESTAMP 保留时间点语义。
                            Object value = "DATETIME".equalsIgnoreCase(meta.getColumnTypeName(i))
                                    ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                            row.put(meta.getColumnLabel(i), value);
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            }
            catch (SQLException e) { throw queryFailure(); }
        }

        @Override
        public synchronized void close()
        {
            if (closed) return;
            closed = true;
            timeout.cancel(false);
            synchronized (connections)
            {
                for (Connection c : connections.values())
                {
                    try { c.close(); } catch (SQLException ignored) { }
                }
                connections.clear();
            }
            if (external) executions.release();
        }
    }

    /** 不带 JDBC 原始异常、URL 或登录用户名进入日志与响应。 */
    public static ServiceException queryFailure()
    {
        return new ServiceException("MySQL 连接或只读查询失败，请检查凭据、TLS、数据库权限及网络；检测未完成");
    }
}
