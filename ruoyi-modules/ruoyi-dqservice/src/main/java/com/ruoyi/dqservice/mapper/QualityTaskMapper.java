package com.ruoyi.dqservice.mapper;

import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.domain.Rule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 质控任务Mapper接口
 *
 * @author ruoyi
 */
public interface QualityTaskMapper
{
    /** 按主键查询任务（含规则数与最近运行状态） */
    QualityTask selectQualityTaskById(Long taskId);

    /** 查询任务列表 */
    List<QualityTask> selectQualityTaskList(QualityTask query);

    /** 新增任务 */
    int insertQualityTask(QualityTask task);

    /** 修改任务 */
    int updateQualityTask(QualityTask task);

    /** 批量删除任务，关联规则由外键级联删除 */
    int deleteQualityTaskByIds(Long[] taskIds);

    /** 查询任务已绑定的规则ID，按排序号返回 */
    List<Long> selectRuleIdsByTaskId(Long taskId);

    /** 查询任务已绑定的规则 */
    List<Rule> selectRulesByTaskId(Long taskId);

    /** 清空任务规则 */
    int deleteTaskRulesByTaskId(Long taskId);

    /** 绑定一条规则 */
    int insertTaskRule(@Param("taskId") Long taskId, @Param("ruleId") Long ruleId, @Param("sortNo") int sortNo);

    /** 查询已启用且配置了 Cron 的任务，供启动时注册调度 */
    List<QualityTask> selectEnabledTasks();

    /** 统计规则被任务引用的次数，删除规则前使用 */
    int countRuleUsage(Long[] ruleIds);

    /** 按数据源与表名查询可加入任务的规则 */
    List<Rule> selectCandidateRules(@Param("sourceId") Integer sourceId, @Param("tableName") String tableName);
}
