package com.ruoyi.dqservice.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 质控任务运行批次 dq_task_run
 *
 * 一次触发对应一条记录。规则级结果通过 dq_execution.run_id 关联，
 * 任务层只汇总规则状态与可相加的条数，不合成任务级通过率。
 *
 * @author ruoyi
 */
@Data
public class QualityTaskRun implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 运行批次ID */
    private Long runId;

    /** 任务ID */
    private Long taskId;

    /** 触发时的任务名称快照 */
    private String taskName;

    /** 触发方式：MANUAL / CRON */
    private String triggerType;

    /** 触发人；定时触发为 system */
    private String triggerBy;

    /** RUNNING / SUCCESS / PARTIAL / NO_DATA / FAILED / SKIPPED */
    private String status;

    private Integer ruleTotal;

    private Integer ruleSuccess;

    private Integer rulePartial;

    private Integer ruleNoData;

    private Integer ruleFailed;

    /** 违规明细条数合计，可按规则相加 */
    private Integer violationCount;

    /** 无法核验条数合计 */
    private Integer unverifiableCount;

    private Long costMs;

    /** 汇总说明或失败原因 */
    private String message;

    private Date startedAt;

    private Date finishedAt;

    /** 非表字段：RUNNING 时等于任务ID，用于唯一索引防止同一任务并发 */
    private Long runningKey;

    /** 非表字段：本批次各规则的执行报告 */
    private List<ExecutionRecord> reports;
}
