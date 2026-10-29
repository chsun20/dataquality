package com.ruoyi.dqservice;

import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.domain.ExecutionViolation;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.ExecutionRecordMapper;
import com.ruoyi.dqservice.mapper.ExecutionViolationMapper;
import com.ruoyi.dqservice.service.ReportService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 报告服务与真实 MyBatis 映射的契约测试，数据库写入使用替身。 */
class QualityReportTest
{
    private ExecutionRecordMapper records;
    private ExecutionViolationMapper details;
    private ReportService service;

    @BeforeEach
    void setUp()
    {
        records = mock(ExecutionRecordMapper.class);
        details = mock(ExecutionViolationMapper.class);
        service = new ReportService();
        ReflectionTestUtils.setField(service, "executionRecordMapper", records);
        ReflectionTestUtils.setField(service, "executionViolationMapper", details);
        when(records.insertExecutionRecord(any())).thenAnswer(invocation -> {
            ExecutionRecord record = invocation.getArgument(0);
            record.setExecutionId(1L);
            return 1;
        });
    }

    @Test
    void newEmptyReportKeepsNullRateAndDoesNotInsertDetails()
    {
        assertEquals(Long.valueOf(1), service.saveExecution(rule(), "sample", 0, 0,
                null, 1, Collections.emptyList()));
        ArgumentCaptor<ExecutionRecord> saved = ArgumentCaptor.forClass(ExecutionRecord.class);
        verify(records).insertExecutionRecord(saved.capture());
        assertNull(saved.getValue().getPassRate());
        assertEquals(Integer.valueOf(0), saved.getValue().getTotalRows());
        assertEquals(Integer.valueOf(0), saved.getValue().getViolationCount());
        verifyNoInteractions(details);
    }

    @Test
    void oldEmptyReportsAreNotEvaluatedInBothListAndDetail()
    {
        ExecutionRecord empty = record(0, 100.0);
        ExecutionRecord nonempty = record(10, 70.0);
        when(records.selectExecutionRecordList(any())).thenReturn(Arrays.asList(empty, nonempty));
        List<ExecutionRecord> list = service.selectExecutionList(new ExecutionRecord());
        assertNull(list.get(0).getPassRate());
        assertEquals(Double.valueOf(70), list.get(1).getPassRate());
        when(records.selectExecutionRecordById(1L)).thenReturn(record(0, 100.0));
        when(details.selectViolationByExecutionId(1L)).thenReturn(Collections.emptyList());
        ExecutionRecord detail = service.selectExecutionDetail(1L);
        assertNull(detail.getPassRate());
        assertTrue(detail.getFieldStats().isEmpty());
        verify(records, never()).insertExecutionRecord(any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void reportPreservesAllHitsAndDistinguishesNullEmptyAndWhitespace()
    {
        List<Map<String, Object>> violations = Arrays.asList(violation(null), violation(""), violation(" "));
        service.saveExecution(rule(), "sample", 2, 1, 50.0, 1, violations);
        ArgumentCaptor<ExecutionRecord> summary = ArgumentCaptor.forClass(ExecutionRecord.class);
        ArgumentCaptor<List<ExecutionViolation>> saved = ArgumentCaptor.forClass((Class) List.class);
        verify(records).insertExecutionRecord(summary.capture());
        verify(details).insertViolationBatch(saved.capture());
        assertEquals(Integer.valueOf(3), summary.getValue().getViolationCount());
        assertEquals(Integer.valueOf(1), summary.getValue().getViolationRows());
        assertEquals(Double.valueOf(50), summary.getValue().getPassRate());
        assertNull(saved.getValue().get(0).getFieldValue());
        assertEquals("", saved.getValue().get(1).getFieldValue());
        assertEquals(" ", saved.getValue().get(2).getFieldValue());
        when(records.selectExecutionRecordById(1L)).thenReturn(summary.getValue());
        when(details.selectViolationByExecutionId(1L)).thenReturn(saved.getValue());
        ExecutionRecord loaded = service.selectExecutionDetail(1L);
        assertEquals(Integer.valueOf(3), loaded.getFieldStats().get(0).getCount());
        assertEquals(3, loaded.getViolations().size());
    }

    @Test
    void mapperBindsSqlNullInsteadOfOmittingRateAndUsingDatabaseDefault() throws Exception
    {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("ExecutionRecord", ExecutionRecord.class);
        String resource = "mapper/dqservice/ExecutionRecordMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource))
        {
            assertNotNull(input);
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        MappedStatement statement = configuration.getMappedStatement(
                ExecutionRecordMapper.class.getName() + ".insertExecutionRecord");
        ExecutionRecord record = record(0, null);
        BoundSql sql = statement.getBoundSql(record);
        assertTrue(sql.getSql().contains("pass_rate"));
        List<ParameterMapping> parameters = sql.getParameterMappings();
        int rateIndex = -1;
        for (int i = 0; i < parameters.size(); i++)
        {
            if ("passRate".equals(parameters.get(i).getProperty()))
            {
                rateIndex = i + 1;
                assertEquals(JdbcType.DECIMAL, parameters.get(i).getJdbcType());
            }
        }
        assertTrue(rateIndex > 0);
        PreparedStatement jdbc = mock(PreparedStatement.class);
        new DefaultParameterHandler(statement, record, sql).setParameters(jdbc);
        verify(jdbc).setNull(rateIndex, Types.DECIMAL);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void partialReportSeparatesUnverifiableAndKeepsCheckMetadata()
    {
        ExecutionRecord record = record(2, 100.0);
        record.setEvaluationStatus("PARTIAL");
        record.setUnverifiableCount(1);
        Map<String, Object> missing = violation(null);
        missing.put("resultType", "UNVERIFIABLE");
        missing.put("checkId", "check-1");
        missing.put("templateCode", "referenceMatch");
        missing.put("dimensionCode", "ACCURACY");
        missing.put("reason", "找不到参考记录，无法核验");
        missing.put("comparisonValue", "参考值");
        missing.put("involvedFields", Collections.singletonMap("business_key", "A"));
        service.saveEvaluation(record, Arrays.asList(violation("违规值"), missing));
        ArgumentCaptor<List<ExecutionViolation>> saved = ArgumentCaptor.forClass((Class) List.class);
        verify(details).insertViolationBatch(saved.capture());
        assertNull(record.getPassRate());
        when(records.selectExecutionRecordById(1L)).thenReturn(record);
        when(details.selectViolationByExecutionId(1L)).thenReturn(saved.getValue());
        ExecutionRecord loaded = service.selectExecutionDetail(1L);
        assertEquals(1, loaded.getViolations().size());
        assertEquals(1, loaded.getUnverifiable().size());
        assertEquals(Integer.valueOf(1), loaded.getFieldStats().get(0).getCount());
        ExecutionViolation detail = loaded.getUnverifiable().get(0);
        assertEquals("check-1", detail.getCheckId());
        assertEquals("referenceMatch", detail.getTemplateCode());
        assertEquals("ACCURACY", detail.getDimensionCode());
        assertEquals("参考值", detail.getComparisonValue());
        assertEquals("{\"business_key\":\"A\"}", detail.getInvolvedFields());
    }

    @Test
    void evaluationBatchesDetailsWithoutDroppingTheTail()
    {
        ExecutionRecord record = record(401, 0.0);
        record.setEvaluationStatus("SUCCESS");
        List<Integer> sizes = new java.util.ArrayList<>();
        when(details.insertViolationBatch(any())).thenAnswer(call -> {
            List<?> batch = call.getArgument(0);
            sizes.add(batch.size());
            return batch.size();
        });
        service.saveEvaluation(record, Collections.nCopies(401, violation("违规")));
        assertEquals(Arrays.asList(200, 200, 1), sizes);
    }

    @Test
    void failedAndNoDataReportsCannotPersistAnAccidentalSuccessRate()
    {
        for (String status : Arrays.asList("FAILED", "NO_DATA"))
        {
            ExecutionRecord record = record(0, 100.0);
            record.setEvaluationStatus(status);
            service.saveEvaluation(record, Collections.emptyList());
            assertNull(record.getPassRate());
        }
        verifyNoInteractions(details);
    }

    private Rule rule()
    {
        Rule rule = new Rule();
        rule.setRuleId(1L);
        rule.setRuleName("测试规则");
        return rule;
    }

    private ExecutionRecord record(int totalRows, Double passRate)
    {
        ExecutionRecord record = new ExecutionRecord();
        record.setTotalRows(totalRows);
        record.setPassRate(passRate);
        return record;
    }

    private Map<String, Object> violation(Object value)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 1);
        row.put("value", value);
        Map<String, Object> violation = new HashMap<>();
        violation.put("rowId", 1);
        violation.put("field", "value");
        violation.put("rule", "测试规则");
        violation.put("row", row);
        return violation;
    }
}
