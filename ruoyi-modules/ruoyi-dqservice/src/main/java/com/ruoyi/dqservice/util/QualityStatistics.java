package com.ruoyi.dqservice.util;

import com.ruoyi.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.util.*;
import lombok.Getter;

/** 本次扫描的行级统计口径；不表示整表评分或检测精确率。 */
public final class QualityStatistics
{
    private QualityStatistics()
    {
    }

    /**
     * 按扫描行号去重计数，同时归一化数值行号以便关联原始记录。
     * 缺失、非整数或超出扫描范围的行号拒绝统计，避免产出错误的通过率。
     * 违规明细本身不去重：同一行被多个检查命中时保留每次命中。
     */
    public static int countViolationRows(List<Map<String, Object>> violations, int totalRows)
    {
        Set<Integer> rowIds = new HashSet<>();
        for (Map<String, Object> violation : violations)
            rowIds.add(normalizeRowId(violation, totalRows));
        return rowIds.size();
    }

    private static int normalizeRowId(Map<String, Object> detail, int totalRows)
    {
        Object value = detail == null ? null : detail.get("rowId");
        int rowId;
        try
        {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            rowId = new BigDecimal(value.toString()).intValueExact();
            if (rowId < 1 || rowId > totalRows) throw new IllegalArgumentException();
        }
        catch (RuntimeException e)
        {
            throw new ServiceException("违规明细的 rowId 必须是本次扫描范围内的整数，请使用记录的 "
                    + RuleParser.ROW_ID_KEY + " 字段");
        }
        detail.put("rowId", rowId);
        return rowId;
    }

    /** 一次扫描结果，同时为响应、旧模板计数和报告准备统一统计。 */
    public static Summary aggregate(List<Map<String, Object>> results, List<Map<String, Object>> rows,
            Set<String> countFields)
    {
        Summary summary = new Summary();
        Map<Object, Map<String, Object>> rowIndex = new HashMap<>();
        for (String field : countFields) summary.nullsByField.put(field, 0);
        for (Map<String, Object> row : rows)
        {
            if (!results.isEmpty()) rowIndex.put(row.get(RuleParser.ROW_ID_KEY), row);
            for (String field : countFields)
                if (row.get(field) == null) summary.nullsByField.merge(field, 1, Integer::sum);
        }
        Set<Integer> failedRows = new HashSet<>();
        Map<String, Integer> fields = new LinkedHashMap<>();
        for (Map<String, Object> detail : results)
        {
            int rowId = normalizeRowId(detail, rows.size());
            Map<String, Object> row = rowIndex.get(rowId);
            if (row != null) detail.put("row", row);
            Object checkId = detail.get("checkId");
            if (checkId instanceof String) summary.resultsByCheck.merge((String) checkId, 1, Integer::sum);
            if ("UNVERIFIABLE".equals(detail.get("resultType"))) summary.unverifiable.add(detail);
            else
            {
                summary.violations.add(detail);
                failedRows.add(rowId);
                Object field = detail.get("field");
                fields.merge(field == null ? "unknown" : field.toString(), 1, Integer::sum);
            }
        }
        summary.violationRows = failedRows.size();
        for (Map.Entry<String, Integer> field : fields.entrySet())
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("field", field.getKey());
            item.put("count", field.getValue());
            summary.fieldStats.add(item);
        }
        summary.fieldStats.sort((a, b) -> ((Integer) b.get("count")).compareTo((Integer) a.get("count")));
        return summary;
    }

    @Getter
    public static final class Summary
    {
        private final List<Map<String, Object>> violations = new ArrayList<>();
        private final List<Map<String, Object>> unverifiable = new ArrayList<>();
        private final List<Map<String, Object>> fieldStats = new ArrayList<>();
        private final Map<String, Integer> resultsByCheck = new HashMap<>();
        private final Map<String, Integer> nullsByField = new HashMap<>();
        private int violationRows;
    }

    /** 行通过率保留一位小数；没有扫描数据时不评价，不返回 100%。 */
    public static Double passRate(int totalRows, int violationRows)
    {
        if (totalRows < 0 || violationRows < 0 || violationRows > totalRows)
        {
            throw new ServiceException("统计数据不合法：违规行数应在实际扫描行数范围内");
        }
        return totalRows == 0 ? null
                : Math.round((totalRows - violationRows) * 1000.0 / totalRows) / 10.0;
    }
}
