package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 空值检测：字段为 null 时判定违规。
 *
 * 完整性维度的基础模板，不需要参数。
 *
 * @author ruoyi
 */
@Component
public class NullCountTemplate implements RuleTemplate
{
    @Override
    public String getCode()
    {
        return "nullCount";
    }

    @Override
    public String getName()
    {
        return "空值检测";
    }

    @Override
    public String getDimension()
    {
        return "完整性";
    }

    @Override
    public List<TemplateParam> getParams()
    {
        return Collections.emptyList();
    }

    @Override
    public String buildRule(String ruleName, String field, String columnType, Map<String, String> params)
    {
        String condition = DrlSupport.fieldRef(field) + " == null";
        return DrlSupport.ruleBlock(ruleName, field, "null", condition);
    }
}
