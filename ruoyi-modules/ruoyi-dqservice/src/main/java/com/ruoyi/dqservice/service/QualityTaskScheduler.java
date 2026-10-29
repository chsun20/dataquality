package com.ruoyi.dqservice.service;

import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.mapper.QualityTaskMapper;
import com.ruoyi.dqservice.mapper.QualityTaskRunMapper;
import com.ruoyi.dqservice.util.TaskCron;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 质控任务 Cron 调度器。
 *
 * 调度状态以 dq_task 表为唯一事实源：启动时按表注册，任务增删改后立即重注册。
 * 当前按单实例运行；多实例部署时每个实例都会注册，需要在触发前增加分布式互斥。
 * 多副本演示环境用 dq.task.scheduler.enabled=false 显式关闭，避免同一 Cron 被多次触发。
 *
 * @author ruoyi
 */
@Component
@Slf4j
public class QualityTaskScheduler
{
    private final QualityTaskMapper taskMapper;

    private final QualityTaskRunMapper runMapper;

    private final QualityTaskRunner runner;

    private final ThreadPoolTaskScheduler scheduler;

    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();

    /** 是否由本进程承担 Cron 调度，默认开启，未显式关闭时保持原有单实例行为 */
    @Value("${dq.task.scheduler.enabled:true}")
    private boolean schedulerEnabled = true;

    public QualityTaskScheduler(QualityTaskMapper taskMapper, QualityTaskRunMapper runMapper,
                                QualityTaskRunner runner)
    {
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.runner = runner;
        ThreadPoolTaskScheduler pool = new ThreadPoolTaskScheduler();
        pool.setPoolSize(2);
        pool.setThreadNamePrefix("dq-cron-");
        pool.setWaitForTasksToCompleteOnShutdown(false);
        pool.initialize();
        this.scheduler = pool;
    }

    @PreDestroy
    public void shutdown()
    {
        scheduler.shutdown();
    }

    /** 启动时清理上次进程遗留的运行批次，再按表恢复调度 */
    @EventListener(ApplicationReadyEvent.class)
    public void start()
    {
        if (!schedulerEnabled)
        {
            log.warn("质控任务调度已由配置关闭：本实例不注册 Cron，只承接接口请求");
            return;
        }
        int interrupted = runMapper.failInterruptedRuns(new Date(),
                "服务重启导致本轮中断；未完成的结果不视为有效评价");
        if (interrupted > 0)
        {
            log.warn("质控任务：{} 个未完成的运行批次已标记为失败", interrupted);
        }
        for (QualityTask task : taskMapper.selectEnabledTasks())
        {
            register(task);
        }
        log.info("质控任务调度已恢复：{} 个启用任务", futures.size());
    }

    /** 任务新增、修改、启停或删除后重新注册 */
    public synchronized void refresh(Long taskId)
    {
        if (!schedulerEnabled)
        {
            return;
        }
        unregister(taskId);
        QualityTask task = taskMapper.selectQualityTaskById(taskId);
        if (task != null && Boolean.TRUE.equals(task.getEnabled()))
        {
            register(task);
        }
    }

    public synchronized void remove(Long taskId)
    {
        unregister(taskId);
    }

    private void register(QualityTask task)
    {
        if (task.getCronExpression() == null || task.getCronExpression().trim().isEmpty())
        {
            return;
        }
        try
        {
            String cron = TaskCron.normalize(task.getCronExpression());
            ScheduledFuture<?> future = scheduler.schedule(() -> fire(task.getTaskId()), new CronTrigger(cron));
            futures.put(task.getTaskId(), future);
        }
        catch (RuntimeException e)
        {
            log.error("质控任务 Cron 注册失败：taskId={}，cron={}", task.getTaskId(), task.getCronExpression());
        }
    }

    private void unregister(Long taskId)
    {
        ScheduledFuture<?> future = futures.remove(taskId);
        if (future != null)
        {
            future.cancel(false);
        }
    }

    private void fire(Long taskId)
    {
        try
        {
            runner.submit(taskId, QualityTaskRunner.TRIGGER_CRON, "system");
        }
        catch (RuntimeException e)
        {
            log.warn("质控任务定时触发失败：taskId={}", taskId);
        }
    }
}
