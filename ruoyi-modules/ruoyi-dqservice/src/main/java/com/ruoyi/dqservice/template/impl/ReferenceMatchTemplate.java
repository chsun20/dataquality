package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReferenceMatchTemplate extends ContextRuleTemplate
{
    public String getCode() { return "referenceMatch"; }
    public String getName() { return "可信参考值核验"; }
    public QualityDimension getQualityDimension() { return QualityDimension.ACCURACY; }
    public List<TemplateParam> getParams()
    {
        return Arrays.asList(param("sourceKey", "源表业务键", "field", null),
                param("referenceSourceId", "参考数据源", "source", null),
                param("referenceTable", "参考表", "referenceTable", null),
                param("referenceKey", "参考单字段唯一键", "referenceKey", null),
                param("referenceValue", "参考值字段", "referenceValue", null),
                param("basisName", "参考依据名称（演示数据请明确标注）", "text", null),
                param("basisVersion", "依据版本说明", "text", null),
                param("tolerance", "数值绝对容差（其它类型不使用）", "number", "0"),
                param("timeZone", "时间字段时区", "text", "Asia/Shanghai"));
    }
}
