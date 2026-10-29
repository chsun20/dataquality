package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotBlankTemplate extends ContextRuleTemplate
{
    public String getCode() { return "notBlank"; }
    public String getName() { return "非空白检查"; }
    public QualityDimension getQualityDimension() { return QualityDimension.COMPLETENESS; }
    public List<TemplateParam> getParams() { return Collections.emptyList(); }
}
