package com.ruoyi.dqservice.template.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数值区间校验：数值超出 [min, max] 时判定违规。
 *
 * 只检测 Number 类型，字符串字段不会被误判；空值不报错。
 * min / max 至少填一个，可只做下限或只做上限。
 *
 * @author ruoyi
 */
@Component
public class RangeCheckTemplate implements RuleTemplate
{
    private static final List<TemplateParam> PARAMS = Arrays.asList(
            new TemplateParam("min", "最小值（含）", "number", false, "留空表示不限"),
            new TemplateParam("max", "最大值（含）", "number", false, "留空表示不限")
    );

    @Override
    public String getCode()
    {
        return "rangeCheck";
    }

    @Override
    public String getName()
    {
        return "数值区间校验";
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
        Double min = DrlSupport.numberParam(params, "min", "最小值");
        Double max = DrlSupport.numberParam(params, "max", "最大值");
        if (min == null && max == null)
        {
            throw new ServiceException("数值区间校验至少要填写最小值或最大值");
        }
        if (min != null && max != null && min > max)
        {
            throw new ServiceException("数值区间校验的最小值不能大于最大值");
        }
        String ref = DrlSupport.fieldRef(field);
        // Map 中的数值可能是 Integer / Long / BigDecimal，统一转 double 再比较
        String number = "((Number)" + ref + ").doubleValue()";
        List<String> conditions = new ArrayList<>();
        if (min != null)
        {
            conditions.add(number + " < " + plain(min));
        }
        if (max != null)
        {
            conditions.add(number + " > " + plain(max));
        }
        String condition = ref + " instanceof Number && (" + String.join(" || ", conditions) + ")";
        return DrlSupport.ruleBlock(ruleName, field, "range", condition);
    }

    /** 去掉多余的小数位，让生成的 DRL 更易读 */
    private static String plain(Double value)
    {
        return new BigDecimal(String.valueOf(value)).stripTrailingZeros().toPlainString();
    }
}
