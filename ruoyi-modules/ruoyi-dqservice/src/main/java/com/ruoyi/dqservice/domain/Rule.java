package com.ruoyi.dqservice.domain;

import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.domain.DroolsRule;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.core.annotation.Excel;
import com.ruoyi.common.core.web.domain.BaseEntity;

import java.util.Objects;

/**
 * 规则对象 rule
 *
 * @author ruoyi
 * @date 2022-10-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Rule extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 规则id */
    private Long ruleId;

    /** 规则名称 */
    @Excel(name = "规则名称")
    private String ruleName;

    /** 创建者id */
    @Excel(name = "创建者id")
    private Long userId;

    /** 创建者名称 */
    @Excel(name = "创建者名称")
    private String userName;

    /** 规则文本 */
    @Excel(name = "规则文本")
    private String ruleContext;

    /** 数据库名称 */
    @Excel(name = "数据库名称")
    private String dbName;

    /** 表名 */
    @Excel(name = "表名")
    private String tableName;

    /** 字段名 */
    @Excel(name = "字段名")
    private String columnName;

    /** 规则类型名称 */
    @Excel(name = "规则类型名称")
    private String typeName;

    /** 规则类型代码 */
    @Excel(name = "规则类型代码")
    private String typeCode;

    /** 规则模板编码，多个模板组合时为 mixed */
    @Excel(name = "规则模板")
    private String templateCode;

    /** 模板与参数配置（JSON），用于编辑回显 */
    private String templateParams;

    /** 分区开始时间 */
    @Excel(name = "分区开始时间")
    private String sourceWhere;

    /** 分区大小 */
    @Excel(name = "分区大小")
    private Integer sourcePartitionNumber;

    /** 分区单位 */
    @Excel(name = "分区单位")
    private String sourcePartitionUnit;

    /** 分区时区 */
    @Excel(name = "分区时区")
    private String sourceTimeZone;

    /** 删除位 */
    @Excel(name = "删除位")
    private Long del;

    private ColInfo[] cols;
}
