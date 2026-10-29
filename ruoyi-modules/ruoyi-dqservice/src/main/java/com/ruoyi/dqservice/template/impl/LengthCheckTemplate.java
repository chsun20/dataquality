package com.ruoyi.dqservice.template.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 长度校验：字符串长度超出 [min, max] 时判定违规。
 *
 * 常用于编码、证件号等定长或限长字段；空值不报错。
 *
 * @author ruoyi
 */
@Component
public class LengthCheckTemplate implements RuleTemplate
{
    private static final List<TemplateParam> PARAMS = Arrays.asList(
            new TemplateParam("min", "最小长度", "number", false, "留空表示不限"),
            new TemplateParam("max", "最大长度", "number", false, "留空表示不限")
    );

    @Override
    public String getCode()
    {
        return "lengthCheck";
    }

    @Override
    public String getName()
    {
        return "长度校验";
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
        Double min = DrlSupport.numberParam(params, "min", "最小长度");
        Double max = DrlSupport.numberParam(params, "max", "最大长度");
        if (min == null && max == null)
        {
            throw new ServiceException("长度校验至少要填写最小长度或最大长度");
        }
        if (min != null && max != null && min > max)
        {
            throw new ServiceException("长度校验的最小长度不能大于最大长度");
        }
        String ref = DrlSupport.fieldRef(field);
        String length = ref + ".toString().length()";
        List<String> conditions = new ArrayList<>();
        if (min != null)
        {
            conditions.add(length + " < " + plain(min));
        }
        if (max != null)
        {
            conditions.add(length + " > " + plain(max));
        }
        String condition = ref + " != null && (" + String.join(" || ", conditions) + ")";
        return DrlSupport.ruleBlock(ruleName, field, "length", condition);
    }

    private static String plain(Double value)
    {
        return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
    }
}
