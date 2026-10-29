package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CompositeUniqueTemplate extends ContextRuleTemplate
{
    public String getCode() { return "compositeUnique"; }
    public String getName() { return "组合唯一检查"; }
    public QualityDimension getQualityDimension() { return QualityDimension.UNIQUENESS; }
    public List<TemplateParam> getParams()
    {
        return Arrays.asList(param("fields", "组合字段（包含当前字段，2～5 个）", "fields", null),
                param("nullMode", "NULL 模式", "select", "ignore").withOptions(Arrays.asList("ignore", "asValue")));
    }
}
