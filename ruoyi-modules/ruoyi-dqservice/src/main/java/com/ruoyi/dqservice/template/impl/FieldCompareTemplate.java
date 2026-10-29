package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FieldCompareTemplate extends ContextRuleTemplate
{
    public String getCode() { return "fieldCompare"; }
    public String getName() { return "同表字段比较"; }
    public QualityDimension getQualityDimension() { return QualityDimension.CONSISTENCY; }
    public List<TemplateParam> getParams()
    {
        return Arrays.asList(param("otherField", "比较字段", "field", null),
                param("operator", "运算符（当前字段相对于比较字段）", "select", "EQ")
                        .withOptions(Arrays.asList("EQ", "NE", "GT", "GE", "LT", "LE")),
                param("timeZone", "时间字段时区", "text", "Asia/Shanghai"));
    }
}
