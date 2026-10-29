package com.ruoyi.dqservice.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 数据质量违规明细 dq_violation
 *
 * @author ruoyi
 */
@Data
public class ExecutionViolation implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 违规明细ID */
    private Long violationId;

    /** 执行批次ID */
    private Long executionId;

    /** 扫描行号 */
    private Integer rowId;

    /** 记录主键值 */
    private String recordId;

    /** 违规字段 */
    private String fieldName;

    /** 命中规则 */
    private String ruleName;

    /** 字段值 */
    private String fieldValue;

    private String checkId;
    private String templateCode;
    private String dimensionCode;
    private String resultType = "VIOLATION";
    private String reason;
    private String comparisonValue;
    private String involvedFields;
}
