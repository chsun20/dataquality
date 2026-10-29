package com.ruoyi.dqservice.template;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.Rule;
import java.util.*;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TemplateCheck
{
    private Integer version;
    private String checkId;
    private String field;
    private String type;
    private String template;
    private Map<String, String> params = new LinkedHashMap<>();
    private Map<String, String> fieldTypes = new LinkedHashMap<>();

    public static List<TemplateCheck> read(Rule rule)
    {
        if (rule.getTemplateParams() == null || rule.getTemplateParams().trim().isEmpty()) return Collections.emptyList();
        try
        {
            List<TemplateCheck> checks = new ObjectMapper().readValue(rule.getTemplateParams(), new TypeReference<List<TemplateCheck>>() {});
            if (checks == null) throw new IllegalArgumentException();
            for (TemplateCheck c : checks)
            {
                if (c.field == null || c.template == null || c.params == null) throw new IllegalArgumentException();
                if (c.checkId == null) c.checkId = ContextRuleTemplate.checkId(c.field, c.template);
            }
            return checks;
        }
        catch (Exception e) { throw new ServiceException("规则模板配置已损坏，请通过参数向导重新保存"); }
    }

    public static Set<Integer> dependencies(Rule rule)
    {
        Set<Integer> ids = new TreeSet<>();
        if (rule.getSourceId() != null) ids.add(rule.getSourceId());
        for (TemplateCheck c : read(rule))
            if (c.isReference()) ids.add(ContextRuleTemplate.referenceSource(c.params));
        return ids;
    }

    public boolean isReference()
    {
        return "referenceExists".equals(template) || "referenceMatch".equals(template);
    }
}
