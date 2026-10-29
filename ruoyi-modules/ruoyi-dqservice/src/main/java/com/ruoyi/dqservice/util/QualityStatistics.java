package com.ruoyi.dqservice.util;

import com.ruoyi.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        {
            Object value = violation == null ? null : violation.get("rowId");
            int rowId;
            try
            {
                if (!(value instanceof Number))
                {
                    throw new IllegalArgumentException();
                }
                rowId = new BigDecimal(value.toString()).intValueExact();
                if (rowId < 1 || rowId > totalRows)
                {
                    throw new IllegalArgumentException();
                }
            }
            catch (RuntimeException e)
            {
                throw new ServiceException("违规明细的 rowId 必须是本次扫描范围内的整数，请使用记录的 "
                        + RuleParser.ROW_ID_KEY + " 字段");
            }
            violation.put("rowId", rowId);
            rowIds.add(rowId);
        }
        return rowIds.size();
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
