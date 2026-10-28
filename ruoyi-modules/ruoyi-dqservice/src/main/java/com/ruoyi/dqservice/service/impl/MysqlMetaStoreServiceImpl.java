package com.ruoyi.dqservice.service.impl;

import com.ruoyi.dqservice.service.HiveMetaStoreService;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于 MySQL information_schema 的元数据服务。
 *
 * 原 Hive 实现依赖外部 Hive Metastore，本地演示环境没有 Hive，
 * 这里把元数据来源切换到项目自身的 MySQL 主数据源，
 * 规则制作向导可以直接浏览 MySQL 的库、表和字段。
 *
 * 返回结构保持与 Hive 实现一致（Table / StorageDescriptor / FieldSchema），
 * 前端 DataSelect.vue 无需任何改动。
 *
 * @author ruoyi
 */
@Service("mysqlMetaStore")
@Slf4j
public class MysqlMetaStoreServiceImpl implements HiveMetaStoreService
{
    /** 系统库不出现在数据源树中 */
    private static final Set<String> SYSTEM_SCHEMAS = new HashSet<>(Arrays.asList(
            "information_schema", "mysql", "performance_schema", "sys"));

    private static final String SQL_SCHEMAS =
            "select schema_name from information_schema.schemata order by schema_name";

    private static final String SQL_TABLES =
            "select table_name from information_schema.tables "
                    + "where table_schema = ? and table_type = 'BASE TABLE' order by table_name";

    private static final String SQL_COLUMNS =
            "select column_name, data_type, column_comment from information_schema.columns "
                    + "where table_schema = ? and table_name = ? order by ordinal_position";

    /** 使用全限定名，避免与 com.ruoyi.dqservice.domain.DataSource 重名 */
    @Autowired
    private javax.sql.DataSource dataSource;

    @Override
    public Iterable<String> getAllDatabases()
    {
        List<String> databases = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_SCHEMAS);
             ResultSet rs = ps.executeQuery())
        {
            while (rs.next())
            {
                String schema = rs.getString(1);
                if (schema != null && !SYSTEM_SCHEMAS.contains(schema.toLowerCase()))
                {
                    databases.add(schema);
                }
            }
        }
        catch (SQLException e)
        {
            log.error("查询 MySQL 库列表失败: {}", e.getMessage());
        }
        return databases;
    }

    @Override
    public Iterable<String> getAllTableNames(String dbName)
    {
        return queryTableNames(dbName);
    }

    @Override
    public Map<String, List<String>> getAllTableNames()
    {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String db : getAllDatabases())
        {
            result.put(db, queryTableNames(db));
        }
        return result;
    }

    @Override
    public List<Table> getAllTable(String db)
    {
        List<Table> tables = new ArrayList<>();
        for (String tableName : queryTableNames(db))
        {
            tables.add(buildTable(db, tableName));
        }
        return tables;
    }

    @Override
    public Map<String, List<Table>> getAllTable()
    {
        Map<String, List<Table>> result = new LinkedHashMap<>();
        for (String db : getAllDatabases())
        {
            result.put(db, getAllTable(db));
        }
        return result;
    }

    @Override
    public Table getTable(String dbName, String tableName)
    {
        Table table = buildTable(dbName, tableName);
        StorageDescriptor sd = new StorageDescriptor();
        sd.setCols(queryColumns(dbName, tableName));
        table.setSd(sd);
        return table;
    }

    @Override
    public void evictHiveCache()
    {
        // 元数据实时查询，无需缓存
    }

    /** 树节点只需要库名和表名，列信息由 getTable 单独查询 */
    private Table buildTable(String dbName, String tableName)
    {
        Table table = new Table();
        table.setDbName(dbName);
        table.setTableName(tableName);
        return table;
    }

    private List<String> queryTableNames(String dbName)
    {
        List<String> names = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_TABLES))
        {
            ps.setString(1, dbName);
            try (ResultSet rs = ps.executeQuery())
            {
                while (rs.next())
                {
                    names.add(rs.getString(1));
                }
            }
        }
        catch (SQLException e)
        {
            log.error("查询表列表失败: {} {}", dbName, e.getMessage());
        }
        return names;
    }

    private List<FieldSchema> queryColumns(String dbName, String tableName)
    {
        List<FieldSchema> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_COLUMNS))
        {
            ps.setString(1, dbName);
            ps.setString(2, tableName);
            try (ResultSet rs = ps.executeQuery())
            {
                while (rs.next())
                {
                    columns.add(new FieldSchema(rs.getString(1), rs.getString(2), rs.getString(3)));
                }
            }
        }
        catch (SQLException e)
        {
            log.error("查询字段信息失败: {}.{} {}", dbName, tableName, e.getMessage());
        }
        return columns;
    }
}
