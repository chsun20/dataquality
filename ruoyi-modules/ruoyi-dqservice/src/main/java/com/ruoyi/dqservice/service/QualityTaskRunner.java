package com.ruoyi.dqservice.service;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.domain.QualityTaskRun;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.QualityTaskMapper;
import com.ruoyi.dqservice.mapper.QualityTaskRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 质控任务异步执行器。
 *
 * 触发时只创建运行批次并立即返回，规则在独立线程池中串行执行；
 * 同一任务依靠 dq_task_run 的唯一索引（task_id, running_key）保证不并发。
 *
 * @author ruoyi
 */
@Component
@Slf4j
public class QualityTaskRunner
{
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String TRIGGER_CRON = "CRON";

    public static final String STATUS_RUNNING = "RUNNING";

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_PARTIAL = "PARTIAL";

    public static final String STATUS_NO_DATA = "NO_DATA";

    public static final String STATUS_FAILED = "FAILED";

    public static final String STATUS_SKIPPED = "SKIPPED";

    /** 定时触发遇到上一轮未结束时，至多补跑一次 */
    private final Set<Long> pendingRuns = ConcurrentHashMap.newKeySet();

    private final QualityTaskMapper taskMapper;

    private final QualityTaskRunMapper runMapper;

    private final RuleExecuteService ruleExecuteService;

    private final ThreadPoolTaskExecutor executor;

    public QualityTaskRunner(QualityTaskMapper taskMapper, QualityTaskRunMapper runMapper,
                             RuleExecuteService ruleExecuteService)
    {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.ruleExecuteService = ruleExecuteService;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(50);
        pool.setThreadNamePrefix("dq-task-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        pool.initialize();
        this.executor = pool;
    }

    @PreDestroy
    public void shutdown()
    {
        executor.shutdown();
    }

    /**
     * 提交一次任务运行，立即返回运行批次。
     *
     * @param taskId      任务ID
     * @param triggerType MANUAL / CRON
     * @param triggerBy   触发人；定时触发为 system
     */
    public QualityTaskRun submit(Long taskId, String triggerType, String triggerBy)
    {
        QualityTask task = taskMapper.selectQualityTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("质控任务不存在：" + taskId);
        }
        List<Rule> rules = taskMapper.selectRulesByTaskId(taskId);
        QualityTaskRun run = newRun(task, triggerType, triggerBy, rules.size());
        run.setRunningKey(taskId);
        try
        {
            runMapper.insertRun(run);
        }
        catch (DuplicateKeyException e)
        {
            return skip(task, triggerType, triggerBy, rules.size());
        }

        try
        {
            executor.execute(() -> execute(run, task, rules));
        }
        catch (RejectedExecutionException e)
        {
            finish(run, STATUS_FAILED, "任务队列已满，本次未能启动；请稍后重试或减少并发任务");
            log.warn("质控任务未能提交：taskId={}，原因=线程池已满", taskId);
        }
        return run;
    }

    /** 上一轮仍在运行时记录一条跳过批次，并按下单策略决定是否补跑一次 */
    private QualityTaskRun skip(QualityTask task, String triggerType, String triggerBy, int ruleTotal)
    {
        QualityTaskRun skipped = newRun(task, triggerType, triggerBy, ruleTotal);
        skipped.setStatus(STATUS_SKIPPED);
        skipped.setFinishedAt(DateUtils.getNowDate());
        boolean retryLater = "FIRE_ONCE".equals(task.getMisfirePolicy());
        if (retryLater)
        {
            pendingRuns.add(task.getTaskId());
        }
        skipped.setMessage(retryLater
                ? "上一轮仍在运行，本次触发已排队，本轮结束后补跑一次"
                : "上一轮仍在运行，本次触发已跳过");
        runMapper.insertRun(skipped);
        log.info("质控任务触发重叠：taskId={}，本轮{}", task.getTaskId(), retryLater ? "排队补跑" : "跳过");
        return skipped;
    }

    private void execute(QualityTaskRun run, QualityTask task, List<Rule> rules)
    {
        long started = System.nanoTime();
        int success = 0;
        int partial = 0;
        int noData = 0;
        int failed = 0;
        int violationCount = 0;
        int unverifiableCount = 0;
        StringBuilder failures = new StringBuilder();
        for (Rule rule : rules)
        {
            try
            {
                Map<String, Object> result = ruleExecuteService.execute(rule.getRuleId(), task.getLimitRows(),
                        run.getRunId(), task.getTaskId(), run.getTriggerBy());
                String status = String.valueOf(result.get("evaluationStatus"));
                if (STATUS_SUCCESS.equals(status)) success++;
                else if (STATUS_PARTIAL.equals(status)) partial++;
                else if (STATUS_NO_DATA.equals(status)) noData++;
                else failed++;
                violationCount += number(result.get("violationCount"));
                unverifiableCount += number(result.get("unverifiableCount"));
            }
            catch (RuntimeException e)
            {
                failed++;
                if (failures.length() < 400)
                {
                    failures.append(rule.getRuleName() == null ? ("规则#" + rule.getRuleId()) : rule.getRuleName())
                            .append("：执行入口失败；");
                }
                log.warn("质控任务中规则执行失败：taskId={}，ruleId={}", task.getTaskId(), rule.getRuleId());
            }
        }

        run.setRuleSuccess(success);
        run.setRulePartial(partial);
        run.setRuleNoData(noData);
        run.setRuleFailed(failed);
        run.setViolationCount(violationCount);
        run.setUnverifiableCount(unverifiableCount);
        run.setCostMs(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        String status = aggregate(rules.size(), success, partial, noData, failed);
        String message = null;
        if (rules.isEmpty())
        {
            message = "任务未绑定任何规则";
        }
        else if (failed > 0)
        {
            message = truncate(failures.toString(), 500);
        }
        finish(run, status, message);

        if (pendingRuns.remove(task.getTaskId()))
        {
            retryOnce(task.getTaskId());
        }
    }

    private String aggregate(int total, int success, int partial, int noData, int failed)
    {
        if (total == 0 || failed == total)
        {
            return STATUS_FAILED;
        }
        if (failed > 0 || partial > 0)
        {
            return STATUS_PARTIAL;
        }
        if (noData == total)
        {
            return STATUS_NO_DATA;
        }
        return STATUS_SUCCESS;
    }

    private void retryOnce(Long taskId)
    {
        try
        {
            QualityTask latest = taskMapper.selectQualityTaskById(taskId);
            if (latest != null && Boolean.TRUE.equals(latest.getEnabled()))
            {
                submit(taskId, TRIGGER_CRON, "system");
            }
        }
        catch (RuntimeException e)
        {
            log.warn("质控任务补跑失败：taskId={}", taskId);
        }
    }

    private void finish(QualityTaskRun run, String status, String message)
    {
        run.setStatus(status);
        run.setMessage(message);
        run.setFinishedAt(DateUtils.getNowDate());
        if (run.getCostMs() == null)
        {
            run.setCostMs(0L);
        }
        runMapper.finishRun(run);
    }

    private QualityTaskRun newRun(QualityTask task, String triggerType, String triggerBy, int ruleTotal)
    {
        QualityTaskRun run = new QualityTaskRun();
        run.setTaskId(task.getTaskId());
        run.setTaskName(task.getTaskName());
        run.setTriggerType(triggerType);
        run.setTriggerBy(triggerBy == null || triggerBy.trim().isEmpty() ? "system" : triggerBy);
        run.setStatus(STATUS_RUNNING);
        run.setRuleTotal(ruleTotal);
        run.setRuleSuccess(0);
        run.setRulePartial(0);
        run.setRuleNoData(0);
        run.setRuleFailed(0);
        run.setViolationCount(0);
        run.setUnverifiableCount(0);
        run.setStartedAt(new Date());
        return run;
    }

    private int number(Object value)
    {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private String truncate(String value, int max)
    {
        if (value == null || value.length() <= max)
        {
            return value;
        }
        return value.substring(0, max);
    }
}
