package com.ruoyi.dqservice.template.impl;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 日期区间校验：日期早于起止日或晚于结束日时判定违规。
 *
 * 参数在 Java 侧解析成毫秒时间戳写进 DRL，避免在规则里做字符串解析。
 * 只检测 java.util.Date 及其子类（java.sql.Date / Timestamp），空值不报错。
 *
 * @author ruoyi
 */
@Component
public class DateRangeTemplate implements RuleTemplate
{
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final List<TemplateParam> PARAMS = Arrays.asList(
            new TemplateParam("start", "开始日期", "date", false, "格式 yyyy-MM-dd，留空表示不限"),
            new TemplateParam("end", "结束日期", "date", false, "格式 yyyy-MM-dd，留空表示不限")
    );

    @Override
    public String getCode()
    {
        return "dateRange";
    }

    @Override
    public String getName()
    {
        return "日期区间校验";
    }

    @Override
    public String getDimension()
    {
        return "时效性";
    }

    @Override
    public List<TemplateParam> getParams()
    {
        return PARAMS;
    }

    @Override
    public String buildRule(String ruleName, String field, String columnType, Map<String, String> params)
    {
        Long start = millis(params, "start", "开始日期");
        Long end = millis(params, "end", "结束日期");
        if (start == null && end == null)
        {
            throw new ServiceException("日期区间校验至少要填写开始日期或结束日期");
        }
        if (start != null && end != null && start > end)
        {
            throw new ServiceException("日期区间校验的开始日期不能晚于结束日期");
        }
        String ref = DrlSupport.fieldRef(field);
        String date = "((java.util.Date)" + ref + ")";
        List<String> conditions = new ArrayList<>();
        if (start != null)
        {
            conditions.add(date + ".before(new java.util.Date(" + start + "L))");
        }
        if (end != null)
        {
            // 结束日当天整体有效，因此取次日零点作为边界
            conditions.add(date + ".after(new java.util.Date(" + (end + 86400000L) + "L))");
        }
        String condition = ref + " instanceof java.util.Date && ("
                + String.join(" || ", conditions) + ")";
        return DrlSupport.ruleBlock(ruleName, field, "date", condition);
    }

    /** 解析 yyyy-MM-dd 参数，返回当天零点的毫秒时间戳 */
    private static Long millis(Map<String, String> params, String key, String label)
    {
        String value = DrlSupport.param(params, key);
        if (value == null)
        {
            return null;
        }
        try
        {
            LocalDate date = LocalDate.parse(value, FORMATTER);
            return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        catch (Exception e)
        {
            throw new ServiceException("参数「" + label + "」日期格式应为 yyyy-MM-dd：" + value);
        }
    }
}
