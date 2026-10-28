package com.ruoyi.dqservice.template.impl;

import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplate;
import com.ruoyi.dqservice.template.TemplateParam;
import com.ruoyi.dqservice.util.RuleParser;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 唯一性校验：字段取值在一次扫描范围内出现多次时判定违规。
 *
 * 与前几类模板的区别是「跨行」判定：其它模板逐行匹配即可，唯一性必须比较记录之间。
 * 实现上用 collect 一次性把所有待检测行收进 List，再在 then 块内按值分组，
 * 只有分组大小大于 1 的行才产出违规，复杂度为 O(n)，不依赖 DRL 的两两关联。
 *
 * 注意：唯一性只在本次扫描的行范围内成立，limit 截断时表内其它重复行不会被发现。
 *
 * @author ruoyi
 */
@Component
public class UniqueCheckTemplate implements RuleTemplate
{
    /** 空值处理参数取值：忽略空值（默认，与 SQL 唯一索引语义一致） */
    private static final String NULL_MODE_IGNORE = "忽略空值";

    /** 空值处理参数取值：把空值也当作同一个取值参与重复判定 */
    private static final String NULL_MODE_AS_VALUE = "视为重复";

    private static final List<TemplateParam> PARAMS = Collections.singletonList(
            new TemplateParam("nullMode", "空值处理", "text", false, "默认忽略空值")
                    .withDefault(NULL_MODE_IGNORE)
                    .withOptions(Arrays.asList(NULL_MODE_IGNORE, NULL_MODE_AS_VALUE)));

    @Override
    public String getCode()
    {
        return "uniqueCheck";
    }

    @Override
    public String getName()
    {
        return "唯一性校验";
    }

    @Override
    public String getDimension()
    {
        return "一致性";
    }

    @Override
    public List<TemplateParam> getParams()
    {
        return PARAMS;
    }

    @Override
    public String buildRule(String ruleName, String field, String columnType, Map<String, String> params)
    {
        String nullMode = DrlSupport.param(params, "nullMode");
        boolean includeNull = NULL_MODE_AS_VALUE.equals(nullMode);
        String name = DrlSupport.escape(ruleName);
        String col = DrlSupport.escape(field);
        // 忽略空值时在 collect 阶段就把空值行排除，避免它们被算作同一个重复取值
        String collectPattern = includeNull ? "Map()" : "Map( this[\"" + col + "\"] != null )";

        StringBuilder drl = new StringBuilder();
        drl.append("rule \"").append(name).append("_").append(col).append("_unique\"\n")
           .append("    when\n")
           .append("        $rows: java.util.List( size > 0 ) from collect( ")
           .append(collectPattern)
           .append(" )\n")
           .append("    then\n")
           // 按字段取值分组，同值记录落入同一个桶
           .append("        Map groups = new HashMap();\n")
           .append("        for (Object item : $rows) {\n")
           .append("            Map row = (Map) item;\n")
           .append("            Object key = row.get(\"").append(col).append("\");\n")
           .append("            java.util.List bucket = (java.util.List) groups.get(key);\n")
           .append("            if (bucket == null) {\n")
           .append("                bucket = new java.util.ArrayList();\n")
           .append("                groups.put(key, bucket);\n")
           .append("            }\n")
           .append("            bucket.add(row);\n")
           .append("        }\n")
           // 桶内多于一条记录，说明该取值重复，桶内每一行都算违规
           .append("        for (Object item : $rows) {\n")
           .append("            Map row = (Map) item;\n")
           .append("            java.util.List bucket = (java.util.List) groups.get(row.get(\"").append(col).append("\"));\n")
           .append("            if (bucket != null && bucket.size() > 1) {\n")
           .append("                Map v = new HashMap();\n")
           .append("                v.put(\"rule\", \"").append(name).append("\");\n")
           .append("                v.put(\"field\", \"").append(col).append("\");\n")
           .append("                v.put(\"rowId\", row.get(\"").append(RuleParser.ROW_ID_KEY).append("\"));\n")
           .append("                violations.add(v);\n")
           .append("            }\n")
           .append("        }\n")
           .append("end\n\n");
        return drl.toString();
    }
}
