package com.ruoyi.dqservice.service;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.domain.QualityTaskRun;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.mapper.ExecutionRecordMapper;
import com.ruoyi.dqservice.mapper.QualityTaskMapper;
import com.ruoyi.dqservice.mapper.QualityTaskRunMapper;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.source.SourcePolicy;
import com.ruoyi.dqservice.template.RuleTemplateService;
import com.ruoyi.dqservice.util.TaskCron;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 质控任务管理。
 *
 * 任务固定绑定一个数据源的一张表，可挂多条规则；
 * 保存时校验规则与任务的源、库、表一致，运行结果通过 run_id 归属到运行批次。
 *
 * @author ruoyi
 */
@Service
public class QualityTaskService
{
    /** 单任务最多绑定的规则数，避免一次触发串行过长 */
    private static final int MAX_RULES = 50;

    private static final int DEFAULT_LIMIT = 1000;

    private static final int MAX_LIMIT = 10000;

    @Autowired
    private QualityTaskMapper taskMapper;

    @Autowired
    private QualityTaskRunMapper runMapper;

    @Autowired
    private RuleMapper ruleMapper;

    @Autowired
    private DataSourceMapper dataSourceMapper;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired
    private RuleTemplateService templateService;

    @Autowired
    private QualityTaskRunner runner;

    @Autowired
    private QualityTaskScheduler scheduler;

    /** 查询任务列表 */
    public List<QualityTask> selectList(QualityTask query)
    {
        return taskMapper.selectQualityTaskList(query);
    }

    /** 查询任务详情，含已绑定规则 */
    public QualityTask selectById(Long taskId)
    {
        QualityTask task = taskMapper.selectQualityTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("质控任务不存在：" + taskId);
        }
        task.setRuleIds(taskMapper.selectRuleIdsByTaskId(taskId));
        List<Rule> rules = taskMapper.selectRulesByTaskId(taskId);
        templateService.decorate(rules);
        task.setRules(rules);
        return task;
    }

    /** 查询指定数据源与表下可加入任务的规则 */
    public List<Rule> candidates(Integer sourceId, String tableName)
    {
        SourcePolicy.identifier(tableName);
        List<Rule> rules = taskMapper.selectCandidateRules(sourceId, tableName);
        templateService.decorate(rules);
        return rules;
    }

    /** 新增任务 */
    @Transactional(rollbackFor = Exception.class)
    public int insert(QualityTask task)
    {
        List<Rule> rules = validate(task);
        Date now = DateUtils.getNowDate();
        task.setCreateBy(currentUsername());
        task.setCreateTime(now);
        task.setUpdateBy(currentUsername());
        task.setUpdateTime(now);
        int changed = taskMapper.insertQualityTask(task);
        bindRules(task.getTaskId(), rules);
        scheduler.refresh(task.getTaskId());
        return changed;
    }

    /** 修改任务 */
    @Transactional(rollbackFor = Exception.class)
    public int update(QualityTask task)
    {
        if (task.getTaskId() == null || taskMapper.selectQualityTaskById(task.getTaskId()) == null)
        {
            throw new ServiceException("质控任务不存在");
        }
        List<Rule> rules = validate(task);
        task.setUpdateBy(currentUsername());
        task.setUpdateTime(DateUtils.getNowDate());
        int changed = taskMapper.updateQualityTask(task);
        taskMapper.deleteTaskRulesByTaskId(task.getTaskId());
        bindRules(task.getTaskId(), rules);
        scheduler.refresh(task.getTaskId());
        return changed;
    }

    /** 删除任务；历史运行批次保留，调度立即注销 */
    @Transactional(rollbackFor = Exception.class)
    public int delete(Long[] taskIds)
    {
        for (Long taskId : taskIds)
        {
            scheduler.remove(taskId);
        }
        return taskMapper.deleteQualityTaskByIds(taskIds);
    }

    /** 启用或停用定时调度 */
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(Long taskId, Boolean enabled, String cronExpression)
    {
        QualityTask task = taskMapper.selectQualityTaskById(taskId);
        if (task == null)
        {
            throw new ServiceException("质控任务不存在：" + taskId);
        }
        String cron = cronExpression == null ? task.getCronExpression() : cronExpression;
        task.setEnabled(Boolean.TRUE.equals(enabled));
        task.setCronExpression(Boolean.TRUE.equals(enabled) ? TaskCron.normalize(cron) : cron);
        task.setUpdateBy(currentUsername());
        task.setUpdateTime(DateUtils.getNowDate());
        int changed = taskMapper.updateQualityTask(task);
        scheduler.refresh(taskId);
        return changed;
    }

    /** 手动触发一次任务，立即返回运行批次，规则在线程池中执行 */
    public QualityTaskRun runNow(Long taskId)
    {
        return runner.submit(taskId, QualityTaskRunner.TRIGGER_MANUAL, currentUsername());
    }

    /** 查询运行批次列表 */
    public List<QualityTaskRun> selectRuns(QualityTaskRun query)
    {
        return runMapper.selectRunList(query);
    }

    /** 查询运行批次详情，附带本批次各规则的执行报告 */
    public QualityTaskRun selectRunDetail(Long runId)
    {
        QualityTaskRun run = runMapper.selectRunById(runId);
        if (run == null)
        {
            throw new ServiceException("任务运行批次不存在：" + runId);
        }
        ExecutionRecord query = new ExecutionRecord();
        query.setRunId(runId);
        List<ExecutionRecord> reports = executionRecordMapper.selectExecutionRecordList(query);
        run.setReports(reports);
        return run;
    }

    /** 删除运行批次记录，不删除已生成的规则报告 */
    public int deleteRuns(Long[] runIds)
    {
        return runMapper.deleteRunByIds(runIds);
    }

    private List<Rule> validate(QualityTask task)
    {
        text(task.getTaskName(), 100, "任务名称");
        SourcePolicy.identifier(task.getDbName());
        SourcePolicy.identifier(task.getTableName());
        int limit = task.getLimitRows() == null ? DEFAULT_LIMIT : task.getLimitRows();
        if (limit < 1 || limit > MAX_LIMIT)
        {
            throw new ServiceException("扫描上限必须为 1～10000");
        }
        task.setLimitRows(limit);
        String policy = task.getMisfirePolicy() == null || task.getMisfirePolicy().trim().isEmpty()
                ? "SKIP" : task.getMisfirePolicy().trim();
        if (!"SKIP".equals(policy) && !"FIRE_ONCE".equals(policy))
        {
            throw new ServiceException("重叠策略只支持 SKIP 或 FIRE_ONCE");
        }
        task.setMisfirePolicy(policy);
        if (Boolean.TRUE.equals(task.getEnabled()))
        {
            task.setCronExpression(TaskCron.normalize(task.getCronExpression()));
        }
        if (task.getSourceId() != null)
        {
            DataSource source = dataSourceMapper.selectDataSourceById(task.getSourceId());
            if (source == null || !Boolean.TRUE.equals(source.getEnabled()))
            {
                throw new ServiceException("任务选择的数据源不存在或已停用");
            }
        }

        List<Long> ruleIds = distinct(task.getRuleIds());
        if (ruleIds.isEmpty())
        {
            throw new ServiceException("质控任务至少需要绑定一条规则");
        }
        if (ruleIds.size() > MAX_RULES)
        {
            throw new ServiceException("单个任务最多绑定 " + MAX_RULES + " 条规则");
        }
        List<Rule> rules = new ArrayList<>(ruleIds.size());
        for (Long ruleId : ruleIds)
        {
            Rule rule = ruleMapper.selectRuleByRuleId(ruleId);
            if (rule == null)
            {
                throw new ServiceException("规则不存在：" + ruleId);
            }
            if (!Objects.equals(rule.getSourceId(), task.getSourceId()))
            {
                throw new ServiceException("规则[" + rule.getRuleName() + "]不属于任务选择的数据源");
            }
            if (!task.getTableName().equals(rule.getTableName()))
            {
                throw new ServiceException("规则[" + rule.getRuleName() + "]检测的表与任务不一致");
            }
            if (rule.getDbName() != null && !rule.getDbName().isEmpty()
                    && !rule.getDbName().equals(task.getDbName()))
            {
                throw new ServiceException("规则[" + rule.getRuleName() + "]检测的库与任务不一致");
            }
            rules.add(rule);
        }
        task.setRuleIds(ruleIds);
        return rules;
    }

    private void bindRules(Long taskId, List<Rule> rules)
    {
        int sortNo = 0;
        for (Rule rule : rules)
        {
            taskMapper.insertTaskRule(taskId, rule.getRuleId(), sortNo++);
        }
    }

    private List<Long> distinct(List<Long> ruleIds)
    {
        if (ruleIds == null)
        {
            return new ArrayList<>();
        }
        return new ArrayList<>(new LinkedHashSet<>(ruleIds));
    }

    private void text(String value, int max, String label)
    {
        if (value == null || value.trim().isEmpty() || value.length() > max)
        {
            throw new ServiceException(label + "不能为空且长度不能超过 " + max);
        }
    }

    private String currentUsername()
    {
        try
        {
            return SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            return "system";
        }
    }
}
