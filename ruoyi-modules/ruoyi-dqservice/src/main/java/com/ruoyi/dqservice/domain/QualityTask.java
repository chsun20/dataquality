package com.ruoyi.dqservice.domain;

import com.ruoyi.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;
import java.util.List;

/**
 * 质控任务 dq_task
 *
 * 一个任务固定绑定一个数据源的一张表，可挂多条规则；
 * 定时触发只负责按 Cron 提交运行批次，任务本身不保存执行结果。
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QualityTask extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 任务ID */
    private Long taskId;

    /** 任务名称 */
    private String taskName;

    /** 数据源ID，null 表示内置本地数据源 */
    private Integer sourceId;

    /** 非表字段：数据源名称 */
    private String sourceName;

    /** 库名 */
    private String dbName;

    /** 表名 */
    private String tableName;

    /** 单次执行每条规则的扫描上限 */
    private Integer limitRows;

    /** 是否启用定时调度 */
    private Boolean enabled;

    /** Spring 六段 Cron 表达式 */
    private String cronExpression;

    /** 调度重叠策略：SKIP 跳过本轮，FIRE_ONCE 立即补跑一次 */
    private String misfirePolicy;

    /** 非表字段：任务包含的规则ID */
    private List<Long> ruleIds;

    /** 非表字段：任务包含的规则 */
    private List<Rule> rules;

    /** 非表字段：规则数量 */
    private Integer ruleCount;

    /** 非表字段：最近一次运行状态 */
    private String lastRunStatus;

    /** 非表字段：最近一次运行时间 */
    private Date lastRunAt;
}
