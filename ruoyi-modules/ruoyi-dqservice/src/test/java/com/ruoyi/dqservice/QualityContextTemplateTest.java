package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.template.*;
import com.ruoyi.dqservice.template.impl.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 所有新增模板均经真实 Drools 编译和执行，不以 Java 单独调用替代。 */
class QualityContextTemplateTest
{
    private final DroolsManager drools = new DroolsManager();
    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    private Map<String, String> params(String... pairs)
    {
        Map<String, String> p = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) p.put(pairs[i], pairs[i + 1]);
        return p;
    }

    private List<Map<String, Object>> rows(Object... values)
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : values)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", value);
            row.put("__rowId", result.size() + 1);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> run(RuleTemplate t, String type, Map<String, String> p,
            List<Map<String, Object>> rows, QualityExecutionContext context)
    {
        return drools.fireRules(DrlSupport.HEADER + t.buildRule("边界测试", "v", type, p), rows, context);
    }

    @Test
    void blankIncludesUnicodeSpacesButNotZeroFalseOrZeroWidthSpace()
    {
        List<Map<String, Object>> rows = rows(null, "", " \t\n", "\u00a0\u3000", 0, false, "\u200b", "x");
        QualityExecutionContext context = new QualityExecutionContext(now, rows);
        List<Map<String, Object>> hits = run(new NotBlankTemplate(), "varchar", params(), rows, context);
        assertEquals(4, hits.size());
        assertEquals(8, context.getCounters().get(0).getChecked());
        assertThrows(ServiceException.class, () -> new NotBlankTemplate().buildRule("x", "v", "int", params()));
    }

    @Test
    void typedCompositeDoesNotConcatenateKeysAndMarksEveryDuplicate()
    {
        List<Map<String, Object>> rows = rows("a|b", "a", "a|b", null, null, 1, "1");
        Object[] others = {"c", "b|c", "c", "x", "x", "y", "y"};
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("other", others[i]);
        Map<String, String> p = params("fields", "[\"v\",\"other\"]");
        QualityExecutionContext c = new QualityExecutionContext(now, rows);
        assertEquals(2, run(new CompositeUniqueTemplate(), "varchar", p, rows, c).size());
        assertEquals(2, c.getCounters().get(0).getSkipped());
        p.put("nullMode", "asValue");
        assertEquals(4, run(new CompositeUniqueTemplate(), "varchar", p, rows, new QualityExecutionContext(now, rows)).size());
        p.put("fields", "v,other");
        assertThrows(ServiceException.class, () -> new CompositeUniqueTemplate().buildRule("x", "v", "varchar", p));
    }

    @Test
    void decimalComparisonPreservesPrecisionAndNullSkips()
    {
        List<Map<String, Object>> rows = rows(new BigDecimal("9007199254740993"), new BigDecimal("1.00"), null);
        rows.get(0).put("other", new BigDecimal("9007199254740992"));
        rows.get(1).put("other", new BigDecimal("1"));
        rows.get(2).put("other", BigDecimal.ZERO);
        QualityExecutionContext c = new QualityExecutionContext(now, rows);
        assertEquals(1, run(new FieldCompareTemplate(), "decimal", params("otherField", "other", "operator", "LE"), rows, c).size());
        assertEquals(1, c.getCounters().get(0).getSkipped());
    }

    @Test
    void textComparisonIsExactAndOrderingIsRejected()
    {
        List<Map<String, Object>> rows = rows("a", " a", "A");
        rows.forEach(r -> r.put("other", "a"));
        assertEquals(2, run(new FieldCompareTemplate(), "varchar", params("otherField", "other", "operator", "EQ"), rows,
                new QualityExecutionContext(now, rows)).size());
        assertThrows(ServiceException.class, () -> new FieldCompareTemplate().buildRule("x", "v", "varchar", params("otherField", "other", "operator", "GT")));
    }

    @Test
    void temporalComparisonUsesExplicitZone()
    {
        List<Map<String, Object>> rows = rows(LocalDateTime.of(2026, 10, 28, 20, 0));
        rows.get(0).put("other", java.sql.Timestamp.from(now));
        assertEquals(0, run(new FieldCompareTemplate(), "datetime", params("otherField", "other", "operator", "EQ", "timeZone", "Asia/Shanghai"),
                rows, new QualityExecutionContext(now, rows)).size());
    }

    @Test
    void freshnessIncludesBothEndpointsAndCountsNulls()
    {
        List<Map<String, Object>> rows = rows(now.minusSeconds(3600), now, now.plusSeconds(60),
                now.minusSeconds(3601), now.plusSeconds(61), null);
        QualityExecutionContext c = new QualityExecutionContext(now, rows);
        assertEquals(2, run(new FreshnessTemplate(), "timestamp", params("duration", "60", "unit", "minutes", "futureTolerance", "1"), rows, c).size());
        assertEquals(1, c.getCounters().get(0).getSkipped());
        assertEquals(5, c.getCounters().get(0).getChecked());
        assertThrows(ServiceException.class, () -> new FreshnessTemplate().buildRule("x", "v", "date", params("duration", "1")));
        assertThrows(ServiceException.class, () -> new FreshnessTemplate().buildRule("x", "v", "timestamp", params("duration", "3651", "unit", "days")));
    }

    @Test
    void referenceExistsIsConsistencyAndMissingPreparationFails()
    {
        List<Map<String, Object>> rows = rows("a", "A", null);
        Map<String, String> p = params("referenceSourceId", "1", "referenceTable", "ref", "referenceKey", "id");
        QualityExecutionContext c = new QualityExecutionContext(now, rows);
        c.addReference(ContextRuleTemplate.checkId("v", "referenceExists"),
                new QualityExecutionContext.ReferenceData("varchar", Collections.singletonMap("a", true), now));
        List<Map<String, Object>> hits = run(new ReferenceExistsTemplate(), "varchar", p, rows, c);
        assertEquals(1, hits.size());
        assertEquals("CONSISTENCY", hits.get(0).get("dimensionCode"));
        assertEquals(1, c.getCounters().get(0).getSkipped());
        assertThrows(RuntimeException.class, () -> run(new ReferenceExistsTemplate(), "varchar", p, rows, new QualityExecutionContext(now, rows)));
    }

    @Test
    void accuracySeparatesMismatchFromUnverifiableAndHonorsTolerance()
    {
        List<Map<String, Object>> rows = rows(new BigDecimal("10.01"), new BigDecimal("12"), null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("key", i == 5 ? null : i + 1);
        Map<Object, Object> values = new LinkedHashMap<>();
        values.put(BigDecimal.ONE, BigDecimal.TEN);
        values.put(new BigDecimal("2"), BigDecimal.TEN);
        values.put(new BigDecimal("3"), BigDecimal.TEN);
        values.put(new BigDecimal("4"), null);
        Map<String, String> p = params("referenceSourceId", "1", "referenceTable", "ref", "referenceKey", "id",
                "referenceValue", "amount", "sourceKey", "key", "basisName", "演示标准答案", "basisVersion", "v1", "tolerance", "0.01");
        QualityExecutionContext c = new QualityExecutionContext(now, rows);
        c.addReference(ContextRuleTemplate.checkId("v", "referenceMatch"), new QualityExecutionContext.ReferenceData("int", values, now));
        List<Map<String, Object>> hits = run(new ReferenceMatchTemplate(), "decimal", p, rows, c);
        assertEquals(5, hits.size());
        assertEquals(4, hits.stream().filter(h -> "UNVERIFIABLE".equals(h.get("resultType"))).count());
        assertEquals(2, c.getCounters().get(0).getChecked());
        assertEquals(1, c.getCounters().get(0).getViolations());
    }

    @Test
    void temporalAmbiguityAndTextNumberCoercionAreRejected()
    {
        assertThrows(ServiceException.class, () -> QualityTypes.number("1"));
        assertThrows(ServiceException.class, () -> QualityTypes.instant(LocalDateTime.of(2026, 11, 1, 1, 30), "America/New_York"));
        assertThrows(ServiceException.class, () -> QualityTypes.instant(LocalDateTime.of(2026, 3, 8, 2, 30), "America/New_York"));
    }
}
