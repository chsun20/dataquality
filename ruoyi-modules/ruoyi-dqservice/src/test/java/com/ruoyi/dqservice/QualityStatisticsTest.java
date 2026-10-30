package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.util.QualityStatistics;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import com.ruoyi.dqservice.template.TemplateCheck;
import java.time.Instant;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 行级统计的独立回归，不依赖数据库或 Spring 上下文。 */
class QualityStatisticsTest
{
    @Test
    void repeatedHitsCountOncePerRowWithoutRemovingDetails()
    {
        List<Map<String, Object>> details = Arrays.asList(violation(1), violation(1L),
                violation(new BigDecimal("2.0")));
        assertEquals(2, QualityStatistics.countViolationRows(details, 3));
        assertEquals(3, details.size());
        assertEquals(Integer.valueOf(1), details.get(1).get("rowId"));
        assertEquals(Integer.valueOf(2), details.get(2).get("rowId"));
        assertEquals(33.3, QualityStatistics.passRate(3, 2).doubleValue());
    }

    @Test
    void emptyScanIsNotEvaluated()
    {
        assertEquals(0, QualityStatistics.countViolationRows(Collections.emptyList(), 0));
        assertNull(QualityStatistics.passRate(0, 0));
    }

    @ParameterizedTest
    @CsvSource({"10,0,100.0", "10,10,0.0", "3,1,66.7", "6,5,16.7", "10000,1,100.0"})
    void rateUsesActualScannedRowsAndOneDecimal(int total, int failed, double expected)
    {
        assertEquals(expected, QualityStatistics.passRate(total, failed).doubleValue());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "1,-1", "1,2", "0,1"})
    void inconsistentTotalsAreRejected(int total, int failed)
    {
        assertThrows(ServiceException.class, () -> QualityStatistics.passRate(total, failed));
    }

    @Test
    void invalidRowIdsAreRejectedRatherThanTruncatedOrIgnored()
    {
        for (Object value : Arrays.asList(null, "1", 0, -1, 4, 1.5, Long.MAX_VALUE, Double.NaN))
        {
            assertThrows(ServiceException.class,
                    () -> QualityStatistics.countViolationRows(Collections.singletonList(violation(value)), 3),
                    "不合法的行号：" + value);
        }
        assertThrows(ServiceException.class,
                () -> QualityStatistics.countViolationRows(Collections.singletonList(null), 3));
        assertThrows(ServiceException.class,
                () -> QualityStatistics.countViolationRows(Collections.singletonList(violation(1)), 0));
    }

    @Test
    void aggregationVisitsEachResultOnceAndPreservesClassificationAndOrder()
    {
        List<Map<String, Object>> rows = sampleRows();
        List<Map<String, Object>> details = Arrays.asList(detail(1L, "a", "A", null), detail(1, "b", "B", null),
                detail(2, "missing", "R", "UNVERIFIABLE"), detail(3, "a", "A", null), detail(4, null, null, null));
        int[] visits = {0};
        List<Map<String, Object>> counted = new AbstractList<Map<String, Object>>() {
            @Override public int size() { return details.size(); }
            @Override public Map<String, Object> get(int index) { visits[0]++; return details.get(index); }
        };
        QualityStatistics.Summary summary = QualityStatistics.aggregate(counted, rows, Collections.singleton("v"));
        assertEquals(details.size(), visits[0]);
        assertEquals(Arrays.asList(details.get(0), details.get(1), details.get(3), details.get(4)), summary.getViolations());
        assertEquals(Collections.singletonList(details.get(2)), summary.getUnverifiable());
        assertEquals(3, summary.getViolationRows());
        assertSame(rows.get(0), details.get(0).get("row"));
        assertSame(rows.get(1), details.get(2).get("row"));
        assertEquals(1, details.get(0).get("rowId"));
        assertEquals(Integer.valueOf(2), summary.getResultsByCheck().get("A"));
        assertEquals(Integer.valueOf(2), summary.getNullsByField().get("v"));
        assertEquals("a", summary.getFieldStats().get(0).get("field"));
        assertEquals("b", summary.getFieldStats().get(1).get("field"));
        assertEquals("unknown", summary.getFieldStats().get(2).get("field"));
    }

    @Test
    void aggregatedLegacyCountsEqualOldTraversalForBothNullModes()
    {
        for (String mode : Arrays.asList("忽略空值", "视为重复"))
        {
            List<Map<String, Object>> rows = sampleRows();
            List<TemplateCheck> checks = new ArrayList<>();
            for (String code : Arrays.asList("nullCount", "uniqueCheck", "regexMatch"))
            {
                TemplateCheck check = new TemplateCheck();
                check.setCheckId(code);
                check.setTemplate(code);
                check.setField("v");
                check.setParams(Collections.singletonMap("nullMode", mode));
                checks.add(check);
            }
            List<Map<String, Object>> details = Arrays.asList(detail(1, "v", "nullCount", null),
                    detail(2, "v", "nullCount", null), detail(3, "v", "uniqueCheck", null),
                    detail(3, "v", "regexMatch", null));
            QualityExecutionContext original = new QualityExecutionContext(Instant.EPOCH, rows);
            QualityExecutionContext optimized = new QualityExecutionContext(Instant.EPOCH, rows);
            QualityStatistics.Summary summary = QualityStatistics.aggregate(details, rows, Collections.singleton("v"));
            // 多个检查共用同一个字段，只进行一次字段空值统计。
            for (Map<String, Object> row : rows) assertEquals(1, ((CountingRow) row).fieldReads);
            for (TemplateCheck check : checks) optimized.countLegacyTemplate(check, "VALIDITY", summary);
            for (Map<String, Object> row : rows) assertEquals(1, ((CountingRow) row).fieldReads);
            for (TemplateCheck check : checks) original.countLegacyTemplate(check, "VALIDITY", details);
            for (int i = 0; i < checks.size(); i++)
            {
                QualityExecutionContext.Counter before = original.getCounters().get(i);
                QualityExecutionContext.Counter after = optimized.getCounters().get(i);
                assertEquals(before.getCheckId(), after.getCheckId());
                assertEquals(before.getChecked(), after.getChecked());
                assertEquals(before.getSkipped(), after.getSkipped());
                assertEquals(before.getViolations(), after.getViolations());
                assertEquals(before.getUnverifiable(), after.getUnverifiable());
            }
        }
    }

    @Test
    void aggregateAlsoRejectsInvalidUnverifiableRowsAndHandlesEmptyInput()
    {
        for (Object id : Arrays.asList(null, "1", 0, -1, 5, 1.5, Long.MAX_VALUE, Double.NaN))
            assertThrows(ServiceException.class, () -> QualityStatistics.aggregate(
                    Collections.singletonList(detail(id, "v", "R", "UNVERIFIABLE")), sampleRows(), Collections.emptySet()));
        QualityStatistics.Summary empty = QualityStatistics.aggregate(Collections.emptyList(), Collections.emptyList(), Collections.singleton("v"));
        assertTrue(empty.getFieldStats().isEmpty());
        assertTrue(empty.getViolations().isEmpty());
        assertEquals(0, empty.getViolationRows());
        assertEquals(Integer.valueOf(0), empty.getNullsByField().get("v"));
    }

    private List<Map<String, Object>> sampleRows()
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : Arrays.asList(null, null, "", "A"))
        {
            Map<String, Object> row = new CountingRow();
            row.put("__rowId", rows.size() + 1);
            row.put("v", value);
            rows.add(row);
        }
        return rows;
    }

    private static class CountingRow extends LinkedHashMap<String, Object>
    {
        private int fieldReads;
        @Override public Object get(Object key)
        {
            if ("v".equals(key)) fieldReads++;
            return super.get(key);
        }
    }

    private Map<String, Object> detail(Object rowId, String field, String check, String type)
    {
        Map<String, Object> result = violation(rowId);
        result.put("field", field);
        result.put("checkId", check);
        result.put("resultType", type);
        return result;
    }

    private Map<String, Object> violation(Object rowId)
    {
        Map<String, Object> detail = new HashMap<>();
        detail.put("rowId", rowId);
        return detail;
    }
}
