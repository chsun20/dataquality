package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 正则格式校验：非空值不匹配指定正则时判定违规。
 *
 * 适用手机号、邮箱、身份证号等有固定书写格式的字段。
 * 空值不在此模板中报错，需要非空约束时另选空值检测模板。
 *
 * @author ruoyi
 */
@Component
public class RegexMatchTemplate implements RuleTemplate
{
    private static final List<TemplateParam> PARAMS = Arrays.asList(
            new TemplateParam("pattern", "正则表达式", "text", true, "例如 ^1[3-9]\\d{9}$")
    );

    @Override
    public String getCode()
    {
        return "regexMatch";
    }

    @Override
    public String getName()
    {
        return "正则格式校验";
    }

    @Override
    public String getDimension()
    {
        return "有效性";
    }

    @Override
    public List<TemplateParam> getParams()
    {
        return PARAMS;
    }

    @Override
    public String buildRule(String ruleName, String field, String columnType, Map<String, String> params)
    {
        String pattern = DrlSupport.requiredParam(params, "pattern", "正则表达式");
        String ref = DrlSupport.fieldRef(field);
        String condition = ref + " != null && !" + ref + ".toString().matches(\""
                + DrlSupport.escape(pattern) + "\")";
        return DrlSupport.ruleBlock(ruleName, field, "regex", condition);
    }
}
