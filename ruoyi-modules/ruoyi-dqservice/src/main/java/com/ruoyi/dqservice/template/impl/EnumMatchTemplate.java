package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 枚举值校验：字段取值不在给定集合内时判定违规。
 *
 * 适用性别、状态、渠道等取值有限的字段。空值不报错。
 *
 * @author ruoyi
 */
@Component
public class EnumMatchTemplate implements RuleTemplate
{
    private static final List<TemplateParam> PARAMS = Arrays.asList(
            new TemplateParam("values", "允许取值", "text", true, "多个取值用英文逗号分隔")
    );

    @Override
    public String getCode()
    {
        return "enumMatch";
    }

    @Override
    public String getName()
    {
        return "枚举值校验";
    }

    @Override
    public QualityDimension getQualityDimension()
    {
        return QualityDimension.VALIDITY;
    }

    @Override
    public List<TemplateParam> getParams()
    {
        return PARAMS;
    }

    @Override
    public String buildRule(String ruleName, String field, String columnType, Map<String, String> params)
    {
        String raw = DrlSupport.requiredParam(params, "values", "允许取值");
        String list = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> "\"" + DrlSupport.escape(s) + "\"")
                .collect(Collectors.joining(", "));
        if (list.isEmpty())
        {
            throw new com.ruoyi.common.core.exception.ServiceException("枚举值校验至少需要一个允许取值");
        }
        String ref = DrlSupport.fieldRef(field);
        String condition = ref + " != null && !java.util.Arrays.asList(" + list + ").contains("
                + ref + ".toString())";
        return DrlSupport.ruleBlock(ruleName, field, "enum", condition);
    }
}
