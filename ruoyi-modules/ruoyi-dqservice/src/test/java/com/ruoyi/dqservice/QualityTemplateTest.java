package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.RuleTemplateRegistry;
import com.ruoyi.dqservice.template.RuleTemplateService;
import com.ruoyi.dqservice.template.impl.*;
import com.ruoyi.dqservice.util.QualityStatistics;
import com.ruoyi.dqservice.util.RuleParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 直接执行生产模板生成的 DRL，核对维度、NULL 与重复记录口径。 */
class QualityTemplateTest
{
    private RuleTemplateRegistry registry;
    private RuleTemplateService service;

    @BeforeEach
    void setUp()
    {
        registry = new RuleTemplateRegistry(Arrays.asList(new NullCountTemplate(), new UniqueCheckTemplate(),
                new RegexMatchTemplate(), new EnumMatchTemplate(), new RangeCheckTemplate(),
                new LengthCheckTemplate(), new DateRangeTemplate()));
        service = new RuleTemplateService();
        ReflectionTestUtils.setField(service, "registry", registry);
        ReflectionTestUtils.setField(service, "droolsManager", new DroolsManager());
    }

    @ParameterizedTest
    @CsvSource({"nullCount,COMPLETENESS", "uniqueCheck,UNIQUENESS", "regexMatch,VALIDITY",
            "enumMatch,VALIDITY", "rangeCheck,VALIDITY", "lengthCheck,VALIDITY", "dateRange,VALIDITY"})
    void templateDimensionUsesStableCode(String code, QualityDimension expected)
    {
        RuleTemplate template = registry.get(code);
        assertEquals(expected, template.getQualityDimension());
        assertEquals(expected.getLabel(), template.getDimension());
        Map<String, Object> metadata = service.listTemplates().stream()
                .filter(item -> code.equals(item.get("code"))).findFirst().get();
        assertEquals(expected.name(), metadata.get("dimensionCode"));
        assertEquals(expected.getLabel(), metadata.get("dimension"));
        Rule rule = new Rule();
        rule.setTemplateCode(code);
        service.decorate(rule);
        assertEquals(Collections.singletonList(expected.getLabel()), rule.getQualityDimensions());
        assertEquals(code, rule.getTypeCode());
    }

    @Test
    void uncoveredDimensionsExposeDefinitionsWithoutPretendingToHaveTemplates()
    {
        List<Map<String, Object>> dimensions = service.listDimensions();
        assertEquals(6, dimensions.size());
        assertEquals(7, service.listTemplates().size());
        Map<String, Integer> expectedCounts = new HashMap<>();
        expectedCounts.put("COMPLETENESS", 1);
        expectedCounts.put("VALIDITY", 5);
        expectedCounts.put("UNIQUENESS", 1);
        for (Map<String, Object> dimension : dimensions)
        {
            String code = (String) dimension.get("code");
            assertFalse(((String) dimension.get("description")).isEmpty());
            assertEquals(expectedCounts.getOrDefault(code, 0).intValue(),
                    ((List<?>) dimension.get("templates")).size());
        }
    }

    @Test
    void mixedDimensionsAreDeduplicatedWithoutChangingTemplateType()
    {
        ColInfo col = new ColInfo();
        col.setName("value");
        col.addRule("nullCount", null);
        col.addRule("uniqueCheck", null);
        col.addRule("regexMatch", Collections.singletonMap("pattern", "[A-Z]+"));
        Rule rule = new Rule();
        rule.setRuleName("组合口径");
        rule.setCols(new ColInfo[] {col});
        service.build(rule);
        assertEquals("mixed", rule.getTemplateCode());
        assertEquals("mixed", rule.getTypeCode());
        assertEquals(Arrays.asList("完整性", "唯一性", "有效性"), rule.getQualityDimensions());
        rule.setTemplateParams("[{\"template\":\"regexMatch\"},{\"template\":\"dateRange\"}]");
        service.decorate(rule);
        assertEquals(Collections.singletonList("有效性"), rule.getQualityDimensions());
    }

    @Test
    void customUnknownAndBrokenConfigurationsStayUnclassified()
    {
        Rule rule = new Rule();
        rule.setRuleContext(DrlSupport.HEADER + registry.get("nullCount")
                .buildRule("自定义", "value", "varchar", Collections.emptyMap()));
        service.applyCustom(rule);
        assertTrue(rule.getQualityDimensions().isEmpty());
        service.decorate(rule);
        assertTrue(rule.getQualityDimensions().isEmpty());
        rule.setTemplateCode("unknown");
        service.decorate(rule);
        assertTrue(rule.getQualityDimensions().isEmpty());
        rule.setTemplateCode("mixed");
        for (String json : Arrays.asList(null, "broken", "null", "[]",
                "[{\"template\":\"nullCount\"},{\"template\":\"unknown\"}]"))
        {
            rule.setTemplateParams(json);
            service.decorate(rule);
            assertTrue(rule.getQualityDimensions().isEmpty());
        }
    }

    @Test
    void legacyNullRulesRetainCompletenessClassification()
    {
        Rule rule = new Rule();
        rule.setRuleContext(DrlSupport.HEADER + registry.get("nullCount")
                .buildRule("旧空值规则", "value", "varchar", Collections.emptyMap()));
        service.decorate(rule);
        assertEquals(Collections.singletonList("完整性"), rule.getQualityDimensions());
    }

    @Test
    void nullCheckDoesNotTreatEmptyOrWhitespaceAsNull()
    {
        assertRowIds(run("nullCount", Collections.emptyMap(), null, "", " ", "正常"), 1);
    }

    @Test
    void validitySkipsNullButChecksEmptyStrings()
    {
        assertRowIds(run("regexMatch", Collections.singletonMap("pattern", "[A-Z]+"),
                null, "", " ", "ABC"), 2, 3);
    }

    @Test
    void uniquenessMarksEveryMemberAndIgnoresNullByDefault()
    {
        assertRowIds(run("uniqueCheck", Collections.emptyMap(),
                "A", "A", "A", null, null, "", "", "a", " A"), 1, 2, 3, 6, 7);
    }

    @Test
    void uniquenessNullAsValueRequiresMultipleNulls()
    {
        Map<String, String> params = Collections.singletonMap("nullMode", "视为重复");
        assertRowIds(run("uniqueCheck", params, null, null, "A"), 1, 2);
        assertRowIds(run("uniqueCheck", params, null, "A", "B"));
    }

    @Test
    void uniquenessOnlyComparesRowsInTheScan()
    {
        assertRowIds(run("uniqueCheck", Collections.emptyMap(), "A"));
        assertRowIds(run("uniqueCheck", Collections.emptyMap(), "A", "A"), 1, 2);
        assertRowIds(run("uniqueCheck", Collections.emptyMap()));
    }

    @Test
    void uniquenessNullModeIsAnEnumeratedParameter()
    {
        assertEquals("select", registry.get("uniqueCheck").getParams().get(0).getType());
        assertThrows(ServiceException.class, () -> registry.get("uniqueCheck")
                .buildRule("错误参数", "value", "varchar", Collections.singletonMap("nullMode", "invalid")));
    }

    @Test
    void sameRowCanProduceMultipleDetailsButOnlyOneFailedRow()
    {
        String drl = DrlSupport.HEADER
                + registry.get("nullCount").buildRule("组合", "value", "varchar", Collections.emptyMap())
                + registry.get("uniqueCheck").buildRule("组合", "value", "varchar",
                        Collections.singletonMap("nullMode", "视为重复"));
        List<Map<String, Object>> violations = new DroolsManager().fireRules(drl, rows(null, null, "A"));
        assertEquals(4, violations.size());
        assertEquals(2, QualityStatistics.countViolationRows(violations, 3));
        assertEquals(33.3, QualityStatistics.passRate(3, 2).doubleValue());
    }

    private List<Map<String, Object>> run(String code, Map<String, String> params, Object... values)
    {
        String drl = DrlSupport.HEADER + registry.get(code).buildRule("口径测试", "value", "varchar", params);
        return new DroolsManager().fireRules(drl, rows(values));
    }

    private List<Map<String, Object>> rows(Object... values)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < values.length; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("value", values[i]);
            rows.add(row);
        }
        return rows;
    }

    private void assertRowIds(List<Map<String, Object>> details, Integer... expected)
    {
        Set<Object> actual = details.stream().map(item -> item.get("rowId")).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList(expected)), actual);
        assertEquals(expected.length, details.size());
    }
}
