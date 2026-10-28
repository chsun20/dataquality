package com.ruoyi.dqservice.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 字段配置：一个字段上可以叠加多个规则模板。
 *
 * rules 保存模板编码，params 按模板编码保存各自的参数，
 * 例如 phone 同时配置空值检测与正则校验时：
 * rules = ["nullCount", "regexMatch"]
 * params = { "regexMatch": { "pattern": "^1[3-9]\\d{9}$" } }
 *
 * @author ruoyi
 */
@Data
public class ColInfo
{
    private String comment;

    /** 选中的规则模板编码 */
    private String[] rules;

    private String name;

    /** 字段类型，来自 Hive 元数据，可为空 */
    private String type;

    /** 模板编码 -> 参数键值对 */
    private Map<String, Map<String, String>> params;

    /** 取某个模板的参数，未配置时返回空 Map */
    public Map<String, String> paramsOf(String code)
    {
        if (params == null || code == null)
        {
            return Collections.emptyMap();
        }
        Map<String, String> value = params.get(code);
        return value == null ? Collections.emptyMap() : value;
    }

    /** 追加一个模板及其参数（用于从持久化配置还原） */
    public void addRule(String code, Map<String, String> ruleParams)
    {
        if (code == null || code.trim().isEmpty())
        {
            return;
        }
        String template = code.trim();
        if (rules == null || rules.length == 0)
        {
            rules = new String[] { template };
        }
        else if (!Arrays.asList(rules).contains(template))
        {
            String[] merged = Arrays.copyOf(rules, rules.length + 1);
            merged[rules.length] = template;
            rules = merged;
        }
        if (ruleParams != null && !ruleParams.isEmpty())
        {
            if (params == null)
            {
                params = new LinkedHashMap<>();
            }
            params.put(template, ruleParams);
        }
    }
}
