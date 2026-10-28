package com.ruoyi.dqservice.service;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.util.RuleParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则执行服务
 *
 * 把「规则」和「数据」真正接通：
 * 1. 按规则绑定的表名从主数据源读取数据，每行转成 Map（字段名 -> 字段值）；
 * 2. 交给 DroolsManager 编译执行 DRL，回收 then 块产出的违规明细；
 * 3. 汇总成可直接展示的质量评估结果。
 *
 * @author ruoyi
 */
@Service
@Slf4j
public class RuleExecuteService
{
    /** 单次执行默认扫描行数，避免大表拖慢演示环境 */
    private static final int DEFAULT_LIMIT = 1000;

    /** 单次执行扫描行数上限 */
    private static final int MAX_LIMIT = 10000;

    /** 表名白名单：仅允许字母、数字、下划线，防止表名拼接造成 SQL 注入 */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");

    /** 库名白名单：允许字母、数字、下划线、连字符（如 ry-cloud） */
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_\\-]+");

    @Autowired
    private RuleMapper ruleMapper;

    @Autowired
    private DroolsManager droolsManager;

    /** 使用全限定名，避免与 com.ruoyi.dqservice.domain.DataSource 重名 */
    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private ReportService reportService;

    /**
     * 执行指定规则
     *
     * @param ruleId 规则主键
     * @param limit  最多扫描的数据行数，为空时取默认值
     * @return 质量评估结果：扫描行数、违规条数、通过率、字段分布、违规明细
     */
    public Map<String, Object> execute(Long ruleId, Integer limit)
    {
        Rule rule = ruleMapper.selectRuleByRuleId(ruleId);
        if (rule == null)
        {
            throw new ServiceException("规则不存在：ruleId=" + ruleId);
        }
        String tableName = rule.getTableName();
        if (tableName == null || !TABLE_NAME_PATTERN.matcher(tableName).matches())
        {
            throw new ServiceException("规则未绑定合法的数据表：" + tableName);
        }
        String drl = rule.getRuleContext();
        if (drl == null || drl.trim().isEmpty())
        {
            throw new ServiceException("规则内容为空，无法执行");
        }

        long startTime = System.currentTimeMillis();
        int maxRows = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        RowQuery query = queryRows(rule.getDbName(), tableName, maxRows);
        List<Map<String, Object>> rows = query.rows;
        List<Map<String, Object>> violations = droolsManager.fireRules(drl, rows);
        fillRowDetail(violations, rows);

        int violationRows = countViolationRows(violations);
        double rate = passRate(rows.size(), violationRows);
        long costMs = System.currentTimeMillis() - startTime;
        Long executionId = reportService.saveExecution(rule, tableName, rows.size(),
                violationRows, rate, costMs, violations);
        log.info("规则[{}]执行完成：库[{}]表[{}]扫描{}行，违规{}条，命中{}行，耗时{}ms，批次{}",
                rule.getRuleName(), query.dbName, tableName, rows.size(), violations.size(), violationRows, costMs, executionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("ruleId", rule.getRuleId());
        result.put("ruleName", rule.getRuleName());
        result.put("dbName", query.dbName);
        result.put("tableName", tableName);
        result.put("totalRows", rows.size());
        result.put("violationCount", violations.size());
        result.put("violationRows", violationRows);
        result.put("passRate", rate);
        result.put("costMs", costMs);
        result.put("fieldStats", fieldStats(violations));
        result.put("violations", violations);
        return result;
    }

    /**
     * 读取数据表，每行一条 Map 记录，并写入行号占位字段供 DRL 回填违规明细。
     * 库名/表名均经过白名单校验，limit 为 int，此处字符串拼接不存在注入风险。
     *
     * @param dbName    规则绑定的库名，为空或不存在时回退到数据源默认库
     * @param tableName 数据表名
     * @param limit     本次最多扫描的行数
     */
    private RowQuery queryRows(String dbName, String tableName, int limit)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        String usedDbName;
        try (Connection connection = dataSource.getConnection())
        {
            usedDbName = resolveDbName(connection, dbName);
            String table = (usedDbName == null ? "" : "`" + usedDbName + "`.") + "`" + tableName + "`";
            String sql = "select * from " + table + " limit " + limit;
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(sql))
            {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                int rowNo = 0;
                while (rs.next())
                {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++)
                    {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rowNo++;
                    row.put(RuleParser.ROW_ID_KEY, rowNo);
                    rows.add(row);
                }
            }
            if (usedDbName == null)
            {
                usedDbName = connection.getCatalog();
            }
        }
        catch (SQLException e)
        {
            log.error("读取数据表[{}]失败", tableName, e);
            throw new ServiceException("读取数据表 " + tableName + " 失败：" + e.getMessage());
        }
        return new RowQuery(usedDbName, rows);
    }

    /**
     * 解析实际可用的库名：规则库名合法且在实例中存在时使用，
     * 否则记录告警并返回 null，走数据源连接自身的默认库。
     */
    private String resolveDbName(Connection connection, String dbName)
    {
        if (dbName == null || dbName.trim().isEmpty())
        {
            return null;
        }
        String candidate = dbName.trim();
        if (!DB_NAME_PATTERN.matcher(candidate).matches())
        {
            log.warn("规则库名[{}]包含非法字符，回退到数据源默认库", dbName);
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "select 1 from information_schema.schemata where schema_name = ?"))
        {
            statement.setString(1, candidate);
            try (ResultSet rs = statement.executeQuery())
            {
                if (rs.next())
                {
                    return candidate;
                }
            }
        }
        catch (SQLException e)
        {
            log.warn("校验库[{}]是否存在时出错，回退到数据源默认库", dbName, e);
            return null;
        }
        log.warn("规则库名[{}]在实例中不存在，回退到数据源默认库", dbName);
        return null;
    }

    /** 一次表读取的结果：实际使用的库名 + 数据行 */
    private static class RowQuery
    {
        private final String dbName;

        private final List<Map<String, Object>> rows;

        private RowQuery(String dbName, List<Map<String, Object>> rows)
        {
            this.dbName = dbName;
            this.rows = rows;
        }
    }

    /**
     * 把违规明细关联回原始记录，前端可直接展示「哪一行、哪个字段」对应的数据
     */
    private void fillRowDetail(List<Map<String, Object>> violations, List<Map<String, Object>> rows)
    {
        if (violations == null || violations.isEmpty())
        {
            return;
        }
        Map<Object, Map<String, Object>> rowIndex = new HashMap<>();
        for (Map<String, Object> row : rows)
        {
            rowIndex.put(row.get(RuleParser.ROW_ID_KEY), row);
        }
        for (Map<String, Object> violation : violations)
        {
            Map<String, Object> row = rowIndex.get(violation.get("rowId"));
            if (row != null)
            {
                violation.put("row", row);
            }
        }
    }

    /** 统计命中违规的记录行数（同一条记录可能多个字段违规） */
    private int countViolationRows(List<Map<String, Object>> violations)
    {
        Set<Object> rowIds = new HashSet<>();
        for (Map<String, Object> violation : violations)
        {
            rowIds.add(violation.get("rowId"));
        }
        return rowIds.size();
    }

    /**
     * 通过率：无违规记录占比，保留一位小数。
     * 空表视为 100%，避免除零。
     */
    private double passRate(int totalRows, int violationRows)
    {
        if (totalRows <= 0)
        {
            return 100.0;
        }
        return Math.round((totalRows - violationRows) * 1000.0 / totalRows) / 10.0;
    }

    /** 按字段聚合违规条数，按条数降序，供报告画图 */
    private List<Map<String, Object>> fieldStats(List<Map<String, Object>> violations)
    {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Map<String, Object> violation : violations)
        {
            Object field = violation.get("field");
            String key = field == null ? "unknown" : field.toString();
            counter.merge(key, 1, Integer::sum);
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counter.entrySet())
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", entry.getKey());
            item.put("count", entry.getValue());
            stats.add(item);
        }
        stats.sort((a, b) -> ((Integer) b.get("count")).compareTo((Integer) a.get("count")));
        return stats;
    }
}
