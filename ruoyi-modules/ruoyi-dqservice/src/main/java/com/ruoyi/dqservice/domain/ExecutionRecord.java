package com.ruoyi.dqservice.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;
import java.util.List;

/**
 * 数据质量执行记录 dq_execution
 *
 * 每次规则执行写一条汇总记录，违规明细存 dq_violation。
 *
 * @author ruoyi
 */
@Data
public class ExecutionRecord implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 执行批次ID */
    private Long executionId;

    /** 规则ID */
    private Long ruleId;

    /** 规则名称 */
    private String ruleName;

    /** 数据库名 */
    private String dbName;

    /** 数据表名 */
    private String tableName;

    /** 扫描行数 */
    private Integer totalRows;

    /** 检查命中产生的违规明细条数，同一行可累计多条 */
    private Integer violationCount;

    /** 违规行数，按本次扫描行号去重 */
    private Integer violationRows;

    /** 行通过率(%)，分母为实际扫描行数；无数据时为 null，不评价 */
    private Double passRate;

    /** 执行耗时(毫秒) */
    private Long costMs;

    /** 执行人 */
    private String createBy;

    /** 执行时间 */
    private Date createTime;

    private Integer sourceId;
    private String sourceName;
    private String detectedAt;
    private String evaluationStatus;
    private String errorMessage;
    private String configSnapshot;
    private String checkCounts;
    private Integer unverifiableCount = 0;
    private String scanNote;
    private List<ExecutionViolation> unverifiable;

    /** 非表字段：详情接口返回的违规明细 */
    private List<ExecutionViolation> violations;

    /** 非表字段：按字段聚合的违规条数 */
    private List<FieldStat> fieldStats;

    /** 字段违规统计 */
    @Data
    public static class FieldStat implements Serializable
    {
        private static final long serialVersionUID = 1L;

        private String field;

        private Integer count;
    }
}
