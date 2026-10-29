package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ReferenceExistsTemplate extends ContextRuleTemplate
{
    public String getCode() { return "referenceExists"; }
    public String getName() { return "引用存在性"; }
    public QualityDimension getQualityDimension() { return QualityDimension.CONSISTENCY; }
    public List<TemplateParam> getParams()
    {
        return Arrays.asList(param("referenceSourceId", "参考数据源", "source", null),
                param("referenceTable", "参考表", "referenceTable", null),
                param("referenceKey", "参考索引字段", "referenceKey", null));
    }
}
