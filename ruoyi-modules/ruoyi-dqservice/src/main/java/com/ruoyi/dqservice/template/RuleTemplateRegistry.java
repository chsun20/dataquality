package com.ruoyi.dqservice.template;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则模板注册表。
 *
 * Spring 启动时把所有 RuleTemplate 实现收集进来，按固定顺序对外展示。
 * 新增模板只需实现接口并标注 @Component，这里无需改动。
 *
 * @author ruoyi
 */
@Component
public class RuleTemplateRegistry
{
    /** 展示顺序，保证前端下拉框顺序稳定 */
    private static final List<String> DISPLAY_ORDER = Arrays.asList(
            "nullCount", "uniqueCheck", "regexMatch", "enumMatch", "rangeCheck", "lengthCheck", "dateRange");

    private final Map<String, RuleTemplate> templates = new LinkedHashMap<>();

    @Autowired
    public RuleTemplateRegistry(List<RuleTemplate> beans)
    {
        Map<String, RuleTemplate> remaining = new LinkedHashMap<>();
        for (RuleTemplate template : beans)
        {
            remaining.put(template.getCode(), template);
        }
        for (String code : DISPLAY_ORDER)
        {
            RuleTemplate template = remaining.remove(code);
            if (template != null)
            {
                templates.put(code, template);
            }
        }
        // 未在展示顺序中列出的模板追加到末尾
        templates.putAll(remaining);
    }

    public RuleTemplate get(String code)
    {
        return code == null ? null : templates.get(code);
    }

    public boolean contains(String code)
    {
        return templates.containsKey(code);
    }

    public Collection<RuleTemplate> all()
    {
        return Collections.unmodifiableCollection(templates.values());
    }
}
