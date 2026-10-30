package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.connecter.CompiledRuleCache;
import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.template.impl.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.service.ReportService;
import com.ruoyi.dqservice.service.RuleExecuteService;
import com.ruoyi.dqservice.util.RuleParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ruoyi.dqservice.source.*;
import com.ruoyi.dqservice.template.*;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证执行入口将统一统计同时传给即时响应与报告保存。 */
class QualityExecutionTest
{
    private RuleExecuteService service;
    private DroolsManager drools;
    private ReportService reports;
    private List<Map<String, Object>> rows;
    private Rule rule;
    private MysqlSourceClient.Session session;

    @BeforeEach
    void setUp() throws Exception
    {
        RuleMapper rules = mock(RuleMapper.class);
        MysqlSourceClient source = mock(MysqlSourceClient.class);
        session = mock(MysqlSourceClient.Session.class);
        RuleConfigurationValidator validator = mock(RuleConfigurationValidator.class);
        SourceTable table = new SourceTable();
        table.setTableName("sample");
        table.setDatabaseName("sample_db");
        rows = new ArrayList<>();
        drools = mock(DroolsManager.class);
        reports = mock(ReportService.class);
        service = new RuleExecuteService();
        ReflectionTestUtils.setField(service, "ruleMapper", rules);
        ReflectionTestUtils.setField(service, "mysql", source);
        ReflectionTestUtils.setField(service, "validator", validator);
        ReflectionTestUtils.setField(service, "references", new ReferenceLookupService());
        ReflectionTestUtils.setField(service, "droolsManager", drools);
        ReflectionTestUtils.setField(service, "reportService", reports);
        rule = new Rule();
        rule.setRuleId(1L);
        rule.setRuleName("执行口径");
        rule.setTableName("sample");
        rule.setRuleContext("测试 DRL");
        when(rules.selectRuleByRuleId(1L)).thenReturn(rule);
        when(source.session(anyBoolean())).thenReturn(session);
        when(validator.validate(eq(session), eq(rule), anyList(), eq(true))).thenReturn(table);
        when(session.scan(isNull(), eq(table), anyInt())).thenReturn(rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitIsNotDenominatorAndLongRowIdStillLinksToOriginalRow() throws Exception
    {
        addRows(2);
        List<Map<String, Object>> violations = Arrays.asList(violation(1), violation(1L));
        when(drools.fireRules(eq("测试 DRL"), anyCollection(), any(QualityExecutionContext.class))).thenReturn(violations);
        Map<String, Object> result = service.execute(1L, 1000);
        assertEquals(2, result.get("totalRows"));
        assertEquals(2, result.get("violationCount"));
        assertEquals(1, result.get("violationRows"));
        assertEquals(50.0, result.get("passRate"));
        Map<String, Object> original = (Map<String, Object>) violations.get(1).get("row");
        assertEquals("原始记录", original.get("value"));
        assertEquals(1, original.get(RuleParser.ROW_ID_KEY));
        assertSame(violations.get(0).get("row"), original);
        ArgumentCaptor<ExecutionRecord> saved = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(reports).saveEvaluation(saved.capture(), eq(violations));
        assertEquals(2, saved.getValue().getTotalRows());
        assertEquals(1, saved.getValue().getViolationRows());
        assertEquals(50.0, saved.getValue().getPassRate());
        assertEquals("SUCCESS", saved.getValue().getEvaluationStatus());
        verify(session).close();
    }

    @Test
    void emptyScanReturnsAndSavesNullRate() throws Exception
    {
        when(drools.fireRules(anyString(), anyCollection(), any(QualityExecutionContext.class))).thenReturn(Collections.emptyList());
        Map<String, Object> result = service.execute(1L, 1000);
        assertEquals(0, result.get("totalRows"));
        assertEquals(0, result.get("violationRows"));
        assertTrue(result.containsKey("passRate"));
        assertNull(result.get("passRate"));
        ArgumentCaptor<ExecutionRecord> saved = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(reports).saveEvaluation(saved.capture(), eq(Collections.emptyList()));
        assertEquals(0, saved.getValue().getTotalRows());
        assertEquals(0, saved.getValue().getViolationRows());
        assertNull(saved.getValue().getPassRate());
        assertEquals("NO_DATA", saved.getValue().getEvaluationStatus());
    }

    @Test
    void invalidRowIdPreventsSavingMisleadingReport() throws Exception
    {
        addRows(1);
        when(drools.fireRules(anyString(), anyCollection(), any(QualityExecutionContext.class))).thenReturn(Collections.singletonList(violation(2)));
        Map<String, Object> result = service.execute(1L, 1000);
        // 新契约保存失败报告，但仍禁止保存误导性的成功结果或无效明细。
        assertEquals("FAILED", result.get("evaluationStatus"));
        assertNull(result.get("passRate"));
        ArgumentCaptor<ExecutionRecord> saved = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(reports).saveEvaluation(saved.capture(), eq(Collections.emptyList()));
        assertEquals("FAILED", saved.getValue().getEvaluationStatus());
        assertNull(saved.getValue().getPassRate());
        verify(session).close();
    }

    @Test
    void unverifiableIsSeparateAndSuppressesOverallRate()
    {
        addRows(2);
        Map<String, Object> missing = violation(2);
        missing.put("resultType", "UNVERIFIABLE");
        when(drools.fireRules(anyString(), anyCollection(), any(QualityExecutionContext.class)))
                .thenReturn(Arrays.asList(violation(1), missing));
        Map<String, Object> result = service.execute(1L, 1000);
        assertEquals("PARTIAL", result.get("evaluationStatus"));
        assertEquals(1, result.get("violationCount"));
        assertEquals(1, result.get("violationRows"));
        assertEquals(1, result.get("unverifiableCount"));
        assertNull(result.get("passRate"));
        verify(reports).saveEvaluation(argThat(r -> "PARTIAL".equals(r.getEvaluationStatus())
                && r.getPassRate() == null && r.getUnverifiableCount() == 1), anyList());
    }

    @Test
    void invalidParametersAndExternalCustomAreRejectedBeforeExecution()
    {
        assertThrows(ServiceException.class, () -> service.execute(1L, 10001));
        rule.setDbName("bad`db");
        assertThrows(ServiceException.class, () -> service.execute(1L, 10));
        rule.setDbName(null);
        rule.setSourceId(7);
        assertThrows(ServiceException.class, () -> service.execute(1L, 10));
        verifyNoInteractions(reports, session, drools);
    }

    @Test
    void runtimeFailureDiscardsPartialResultsAndDoesNotExposeDriverMessage()
    {
        doThrow(new ServiceException("password=must-not-leak")).when(session).scan(any(), any(), anyInt());
        Map<String, Object> result = service.execute(1L, 100);
        assertEquals("FAILED", result.get("evaluationStatus"));
        assertFalse(result.toString().contains("must-not-leak"));
        assertNull(result.get("passRate"));
        verify(session).close();
        verify(reports).saveEvaluation(argThat(r -> "FAILED".equals(r.getEvaluationStatus())), eq(Collections.emptyList()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void realMixedTemplatesUseCachedCompilationAndDoNotDoubleCountContextChecks() throws Exception
    {
        addRows(4);
        Object[] values = {null, "", "ABC", "x"};
        for (int i = 0; i < values.length; i++) rows.get(i).put("value", values[i]);
        RuleTemplateRegistry registry = new RuleTemplateRegistry(Arrays.asList(
                new NullCountTemplate(), new RegexMatchTemplate(), new NotBlankTemplate()));
        CompiledRuleCache cache = new CompiledRuleCache();
        DroolsManager realDrools = new DroolsManager(cache);
        RuleTemplateService templates = new RuleTemplateService();
        ReflectionTestUtils.setField(templates, "registry", registry);
        ReflectionTestUtils.setField(templates, "droolsManager", realDrools);
        ReflectionTestUtils.setField(service, "registry", registry);
        ReflectionTestUtils.setField(service, "templates", templates);
        ReflectionTestUtils.setField(service, "droolsManager", realDrools);
        ColInfo col = new ColInfo();
        col.setName("value");
        col.setType("varchar");
        col.addRule("nullCount", null);
        col.addRule("regexMatch", Collections.singletonMap("pattern", "[A-Z]+"));
        col.addRule("notBlank", null);
        rule.setCols(new ColInfo[]{col});
        templates.build(rule);
        for (int run = 0; run < 2; run++)
        {
            Map<String, Object> result = service.execute(1L, 100);
            assertEquals("SUCCESS", result.get("evaluationStatus"));
            assertEquals(5, result.get("violationCount"));
            assertEquals(3, result.get("violationRows"));
            assertEquals(25.0, result.get("passRate"));
            List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fieldStats");
            assertEquals(1, fields.size());
            assertEquals(5, fields.get(0).get("count"));
            JsonNode counts = new ObjectMapper().readTree((String) result.get("checkCounts"));
            assertEquals(3, counts.size());
            for (JsonNode count : counts)
            {
                String code = count.get("templateCode").asText();
                assertEquals("regexMatch".equals(code) ? 3 : 4, count.get("checked").asInt());
                assertEquals("regexMatch".equals(code) ? 1 : 0, count.get("skipped").asInt());
                assertEquals("nullCount".equals(code) ? 1 : 2, count.get("violations").asInt());
                assertEquals(0, count.get("unverifiable").asInt());
            }
        }
        assertEquals(1, cache.getBuildSuccessCount());
        verify(reports, times(2)).saveEvaluation(argThat(r -> r.getViolationCount() == 5 && r.getViolationRows() == 3), anyList());
    }

    @Test
    void partialAggregationFailureClearsFieldStatisticsAndPersistenceFailurePropagates()
    {
        addRows(1);
        when(drools.fireRules(anyString(), anyCollection(), any(QualityExecutionContext.class)))
                .thenReturn(Arrays.asList(violation(1), violation(2)));
        Map<String, Object> result = service.execute(1L, 100);
        assertEquals("FAILED", result.get("evaluationStatus"));
        assertEquals(Collections.emptyList(), result.get("fieldStats"));
        assertEquals(Collections.emptyList(), result.get("violations"));
        when(drools.fireRules(anyString(), anyCollection(), any(QualityExecutionContext.class)))
                .thenReturn(Collections.emptyList());
        RuntimeException failure = new IllegalStateException("报告写入失败");
        when(reports.saveEvaluation(any(), anyList())).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.execute(1L, 100)));
    }

    private void addRows(int count)
    {
        for (int i = 1; i <= count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put("value", "原始记录");
            row.put(RuleParser.ROW_ID_KEY, i);
            rows.add(row);
        }
    }

    private Map<String, Object> violation(Object id)
    {
        Map<String, Object> detail = new HashMap<>();
        detail.put("rowId", id);
        detail.put("field", "value");
        return detail;
    }
}
