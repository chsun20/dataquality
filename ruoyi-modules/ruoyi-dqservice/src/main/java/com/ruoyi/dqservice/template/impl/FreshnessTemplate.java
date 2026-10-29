package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class FreshnessTemplate extends ContextRuleTemplate
{
    public String getCode() { return "freshness"; }
    public String getName() { return "数据新鲜度"; }
    public QualityDimension getQualityDimension() { return QualityDimension.TIMELINESS; }
    public List<TemplateParam> getParams()
    {
        return Arrays.asList(param("duration", "最大时长（正整数）", "number", "24"),
                param("unit", "单位", "select", "hours").withOptions(Arrays.asList("minutes", "hours", "days")),
                param("futureTolerance", "允许未来偏移（同上述单位）", "number", "0"),
                param("timeZone", "时间字段时区", "text", "Asia/Shanghai"));
    }
}
