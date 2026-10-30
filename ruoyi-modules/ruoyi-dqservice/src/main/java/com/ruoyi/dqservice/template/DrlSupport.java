package com.ruoyi.dqservice.template;

import com.ruoyi.dqservice.util.RuleParser;
import com.ruoyi.common.core.exception.ServiceException;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DRL 片段拼装工具。
 *
 * 统一 header 与 then 块，保证所有模板产出的规则都满足执行侧契约：
 * 声明 global java.util.List violations，并写入 rule / field / rowId 三个键。
 *
 * @author ruoyi
 */
public final class DrlSupport
{
    /** DRL 固定头部，各编译产物由独立模块坐标隔离，同一内容可复用 KieBase。 */
    public static final String HEADER =
            "package dq.dynamic\n"
            + "import java.util.Map\n"
            + "import java.util.HashMap\n"
            + "global java.util.List violations\n"
                        + "global com.ruoyi.dqservice.template.QualityExecutionContext qualityContext\n\n";

    /** 规则主体起点：第一处 rule "..." 声明 */
    private static final Pattern BODY_START = Pattern.compile("(?m)^[ \\t]*rule[ \\t]+\\\"");

    /** package 声明，用于判断提交内容是否已是完整 DRL */
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^[ \\t]*package[ \\t]+");

    private DrlSupport()
    {
    }

    /**
     * 截取 DRL 主体（rule ... end），去掉 package / import / global 头部。
     * 列表展示、修改弹窗与详情弹窗都只面向主体部分。
     */
    public static String body(String drl)
    {
        if (drl == null)
        {
            return "";
        }
        Matcher matcher = BODY_START.matcher(drl);
        return matcher.find() ? drl.substring(matcher.start()).trim() : drl.trim();
    }

    /**
     * 把用户提交的内容归一化为可执行 DRL：
     * 只写了 rule ... end 主体时自动补全头部，避免手工规则漏写 global 声明。
     */
    public static String normalize(String drl)
    {
        String text = drl == null ? "" : drl.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty())
        {
            throw new ServiceException("规则内容不能为空");
        }
        if (!PACKAGE_DECL.matcher(text).find())
        {
            text = HEADER + text;
        }
        return text;
    }

    /** 执行侧依赖 global violations 收集违规，缺失时给出可读报错 */
    public static void requireViolations(String drl)
    {
        if (drl == null || !drl.contains("global") || !drl.contains("violations"))
        {
            throw new ServiceException("规则缺少 global java.util.List violations 声明，执行时无法收集违规结果");
        }
    }

    /** 字段访问表达式：this["col"] */
    public static String fieldRef(String field)
    {
        return "this[\"" + escape(field) + "\"]";
    }

    /** DRL 字符串字面量转义，避免规则名、字段名、正则中的引号与反斜杠破坏语法 */
    public static String escape(String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")
                .replace("\b", "\\b").replace("\f", "\\f");
    }

    /**
     * 拼装一条完整规则。
     *
     * @param ruleName  规则名称
     * @param field     字段名
     * @param suffix    规则名后缀，用于区分同一字段上的不同模板
     * @param condition when 中的条件表达式
     */
    public static String ruleBlock(String ruleName, String field, String suffix, String condition)
    {
        String name = escape(ruleName);
        String col = escape(field);
        return "rule \"" + name + "_" + col + "_" + suffix + "\"\n"
                + "    when\n"
                + "        $row: Map(" + condition + ")\n"
                + "    then\n"
                + "        Map v = new HashMap();\n"
                + "        v.put(\"rule\", \"" + name + "\");\n"
                + "        v.put(\"field\", \"" + col + "\");\n"
                + "        v.put(\"rowId\", $row.get(\"" + RuleParser.ROW_ID_KEY + "\"));\n"
                + "        violations.add(v);\n"
                + "end\n\n";
    }

    /** 取参数值，空串归一为 null */
    public static String param(Map<String, String> params, String key)
    {
        if (params == null)
        {
            return null;
        }
        String value = params.get(key);
        if (value == null)
        {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** 必填参数，缺失时给出明确报错 */
    public static String requiredParam(Map<String, String> params, String key, String label)
    {
        String value = param(params, key);
        if (value == null)
        {
            throw new ServiceException("模板缺少必填参数：" + label);
        }
        return value;
    }

    /** 数字参数解析，非法值直接报错而不是静默忽略 */
    public static Double numberParam(Map<String, String> params, String key, String label)
    {
        String value = param(params, key);
        if (value == null)
        {
            return null;
        }
        try
        {
            return Double.valueOf(value);
        }
        catch (NumberFormatException e)
        {
            throw new ServiceException("参数「" + label + "」必须是数字：" + value);
        }
    }
}
