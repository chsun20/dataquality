package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.domain.QualityTaskRun;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.mapper.ExecutionRecordMapper;
import com.ruoyi.dqservice.mapper.QualityTaskMapper;
import com.ruoyi.dqservice.mapper.QualityTaskRunMapper;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.service.QualityTaskRunner;
import com.ruoyi.dqservice.service.QualityTaskScheduler;
import com.ruoyi.dqservice.service.QualityTaskService;
import com.ruoyi.dqservice.service.RuleExecuteService;
import com.ruoyi.dqservice.service.impl.RuleServiceImpl;
import com.ruoyi.dqservice.template.RuleTemplateService;
import com.ruoyi.dqservice.util.TaskCron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证质控任务的范围校验、调度表达式与异步运行汇总。 */
class QualityTaskTest
{
    private QualityTaskMapper taskMapper;

    private QualityTaskRunMapper runMapper;

    private RuleMapper ruleMapper;

    private DataSourceMapper sourceMapper;

    private ExecutionRecordMapper executionMapper;

    private RuleTemplateService templates;

    private QualityTaskRunner runner;

    private QualityTaskScheduler scheduler;

    private QualityTaskService service;

    @BeforeEach
    void setUp()
    {
        taskMapper = mock(QualityTaskMapper.class);
        runMapper = mock(QualityTaskRunMapper.class);
        ruleMapper = mock(RuleMapper.class);
        sourceMapper = mock(DataSourceMapper.class);
        executionMapper = mock(ExecutionRecordMapper.class);
        templates = mock(RuleTemplateService.class);
        runner = mock(QualityTaskRunner.class);
        scheduler = mock(QualityTaskScheduler.class);
        service = new QualityTaskService();
        ReflectionTestUtils.setField(service, "taskMapper", taskMapper);
        ReflectionTestUtils.setField(service, "runMapper", runMapper);
        ReflectionTestUtils.setField(service, "ruleMapper", ruleMapper);
        ReflectionTestUtils.setField(service, "dataSourceMapper", sourceMapper);
        ReflectionTestUtils.setField(service, "executionRecordMapper", executionMapper);
        ReflectionTestUtils.setField(service, "templateService", templates);
        ReflectionTestUtils.setField(service, "runner", runner);
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
    }

    @Test
    void cronAcceptsSixFieldsAndDropsWildcardYear()
    {
        assertEquals("0 0 2 * * ?", TaskCron.normalize("0 0 2 * * ?"));
        assertEquals("0 0 2 * * ?", TaskCron.normalize("0 0 2 * * ? *"));
    }

    @Test
    void cronRejectsYearFieldsAndMalformedExpressions()
    {
        assertThrows(ServiceException.class, () -> TaskCron.normalize("0 0 2 * * ? 2027"));
        assertThrows(ServiceException.class, () -> TaskCron.normalize("0 2 * *"));
        assertThrows(ServiceException.class, () -> TaskCron.normalize("0 0 99 * * ?"));
        assertThrows(ServiceException.class, () -> TaskCron.normalize(" "));
    }

    @Test
    void taskRequiresAtLeastOneRule()
    {
        QualityTask task = task();
        task.setRuleIds(Collections.emptyList());
        assertThrows(ServiceException.class, () -> service.insert(task));
        verify(taskMapper, never()).insertQualityTask(any());
    }

    @Test
    void taskRejectsRuleFromAnotherSourceOrTable()
    {
        QualityTask task = task();
        Rule otherSource = rule(1L, 9, "sample", "sample_db");
        when(ruleMapper.selectRuleByRuleId(1L)).thenReturn(otherSource);
        assertThrows(ServiceException.class, () -> service.insert(task));

        Rule otherTable = rule(1L, null, "another_table", "sample_db");
        when(ruleMapper.selectRuleByRuleId(1L)).thenReturn(otherTable);
        assertThrows(ServiceException.class, () -> service.insert(task));
        verify(taskMapper, never()).insertQualityTask(any());
    }

    @Test
    void taskRejectsInvalidLimitAndMissingCronWhenEnabled()
    {
        QualityTask task = task();
        task.setLimitRows(10001);
        when(ruleMapper.selectRuleByRuleId(1L)).thenReturn(rule(1L, null, "sample", "sample_db"));
        assertThrows(ServiceException.class, () -> service.insert(task));

        task.setLimitRows(100);
        task.setEnabled(true);
        task.setCronExpression(null);
        assertThrows(ServiceException.class, () -> service.insert(task));
    }

    @Test
    void taskBindsDistinctRulesInOrderAndRefreshesSchedule()
    {
        QualityTask task = task();
        task.setRuleIds(Arrays.asList(2L, 1L, 2L));
        when(ruleMapper.selectRuleByRuleId(1L)).thenReturn(rule(1L, null, "sample", "sample_db"));
        when(ruleMapper.selectRuleByRuleId(2L)).thenReturn(rule(2L, null, "sample", "sample_db"));
        when(taskMapper.insertQualityTask(any())).thenAnswer(invocation -> {
            QualityTask saved = invocation.getArgument(0);
            saved.setTaskId(88L);
            return 1;
        });

        assertEquals(1, service.insert(task));
        verify(taskMapper).insertTaskRule(88L, 2L, 0);
        verify(taskMapper).insertTaskRule(88L, 1L, 1);
        verify(scheduler).refresh(88L);
        assertEquals(100, task.getLimitRows());
        assertEquals("SKIP", task.getMisfirePolicy());
    }

    @Test
    void ruleDeleteIsBlockedWhenTaskReferencesIt()
    {
        RuleServiceImpl ruleService = new RuleServiceImpl();
        ReflectionTestUtils.setField(ruleService, "qualityTasks", taskMapper);
        when(taskMapper.countRuleUsage(any(Long[].class))).thenReturn(1);
        assertThrows(ServiceException.class, () -> ruleService.deleteRuleByRuleIds(new Long[]{1L}));
    }

    @Test
    void overlappingTriggerIsRecordedAsSkippedWithoutRunningRules()
    {
        QualityTask task = task();
        task.setMisfirePolicy("SKIP");
        when(taskMapper.selectQualityTaskById(5L)).thenReturn(task);
        when(taskMapper.selectRulesByTaskId(5L)).thenReturn(Collections.singletonList(rule(1L, null, "sample", "sample_db")));
        doThrow(new DuplicateKeyException("running")).doReturn(1).when(runMapper).insertRun(any());

        RuleExecuteService executeService = mock(RuleExecuteService.class);
        QualityTaskRunner local = new QualityTaskRunner(taskMapper, runMapper, executeService);
        try
        {
            QualityTaskRun run = local.submit(5L, QualityTaskRunner.TRIGGER_CRON, "system");
            assertEquals(QualityTaskRunner.STATUS_SKIPPED, run.getStatus());
            assertNotNull(run.getFinishedAt());
            verify(executeService, never()).execute(anyLong(), anyInt(), anyLong(), anyLong(), anyString());
        }
        finally
        {
            local.shutdown();
        }
    }

    @Test
    void asyncRunAggregatesRuleStatusWithoutTaskLevelRate() throws Exception
    {
        QualityTask task = task();
        when(taskMapper.selectQualityTaskById(5L)).thenReturn(task);
        when(taskMapper.selectRulesByTaskId(5L)).thenReturn(Arrays.asList(
                rule(1L, null, "sample", "sample_db"),
                rule(2L, null, "sample", "sample_db"),
                rule(3L, null, "sample", "sample_db")));
        doAnswer(invocation -> {
            QualityTaskRun created = invocation.getArgument(0);
            created.setRunId(77L);
            return 1;
        }).when(runMapper).insertRun(any());
        RuleExecuteService executeService = mock(RuleExecuteService.class);
        when(executeService.execute(eq(1L), anyInt(), anyLong(), eq(5L), anyString())).thenReturn(result("SUCCESS", 2, 0));
        when(executeService.execute(eq(2L), anyInt(), anyLong(), eq(5L), anyString())).thenReturn(result("PARTIAL", 3, 1));
        when(executeService.execute(eq(3L), anyInt(), anyLong(), eq(5L), anyString())).thenReturn(result("FAILED", 0, 0));

        QualityTaskRunner local = new QualityTaskRunner(taskMapper, runMapper, executeService);
        try
        {
            QualityTaskRun run = local.submit(5L, QualityTaskRunner.TRIGGER_MANUAL, "admin");
            verify(runMapper, timeout(3000)).finishRun(any());
            assertEquals(QualityTaskRunner.STATUS_PARTIAL, run.getStatus());
            assertEquals(1, run.getRuleSuccess());
            assertEquals(1, run.getRulePartial());
            assertEquals(1, run.getRuleFailed());
            assertEquals(5, run.getViolationCount());
            assertEquals(1, run.getUnverifiableCount());
            assertNotNull(run.getFinishedAt());
        }
        finally
        {
            local.shutdown();
        }
    }

    @Test
    void runDetailAttachesReportsByRunId()
    {
        QualityTaskRun run = new QualityTaskRun();
        run.setRunId(66L);
        when(runMapper.selectRunById(66L)).thenReturn(run);
        ExecutionRecord report = new ExecutionRecord();
        report.setRunId(66L);
        when(executionMapper.selectExecutionRecordList(any())).thenReturn(Collections.singletonList(report));

        QualityTaskRun detail = service.selectRunDetail(66L);
        assertEquals(1, detail.getReports().size());
        verify(executionMapper).selectExecutionRecordList(argThat(q -> Long.valueOf(66L).equals(q.getRunId())));
    }

    private QualityTask task()
    {
        QualityTask task = new QualityTask();
        task.setTaskId(5L);
        task.setTaskName("每日质量任务");
        task.setDbName("sample_db");
        task.setTableName("sample");
        task.setLimitRows(100);
        task.setEnabled(false);
        task.setRuleIds(Collections.singletonList(1L));
        return task;
    }

    private Rule rule(Long id, Integer sourceId, String table, String db)
    {
        Rule rule = new Rule();
        rule.setRuleId(id);
        rule.setRuleName("规则" + id);
        rule.setSourceId(sourceId);
        rule.setTableName(table);
        rule.setDbName(db);
        return rule;
    }

    private Map<String, Object> result(String status, int violations, int unverifiable)
    {
        Map<String, Object> map = new HashMap<>();
        map.put("evaluationStatus", status);
        map.put("violationCount", violations);
        map.put("unverifiableCount", unverifiable);
        return map;
    }
}
