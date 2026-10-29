package com.ruoyi.dqservice.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.source.SourcePolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.*;

/** 新模板仍由 Drools 逐行触发；上下文提供类型比较、批次索引与检查计数。 */
public abstract class ContextRuleTemplate implements RuleTemplate
{
    private static final ObjectMapper JSON = new ObjectMapper();

    public static String checkId(String field, String code)
    {
        return UUID.nameUUIDFromBytes((field + "\0" + code).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String buildRule(String ruleName, String field, String type, Map<String, String> params)
    {
        Map<String, String> p = params == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params);
        validate(field, type, p);
        String id = checkId(field, getCode());
        try
        {
            String args = Arrays.asList(id, ruleName, getCode(), field, type, getQualityDimension().name(), JSON.writeValueAsString(p))
                    .stream().map(v -> "\"" + DrlSupport.escape(v) + "\"").collect(java.util.stream.Collectors.joining(", "));
            return "rule \"" + DrlSupport.escape(ruleName) + "_" + id + "\"\n"
                    + "    when\n        $row: Map()\n    then\n"
                    + "        qualityContext.evaluate(" + args + ", $row, violations);\nend\n\n";
        }
        catch (java.io.IOException e) { throw new ServiceException("模板参数编码失败"); }
    }

    public void validate(String field, String type, Map<String, String> p)
    {
        SourcePolicy.identifier(field);
        String kind = QualityTypes.kind(type);
        switch (getCode())
        {
            case "notBlank":
                require("TEXT".equals(kind), "非空白模板只适用于字符串字段");
                break;
            case "compositeUnique":
                List<String> fields = fields(p);
                require(fields.contains(field), "组合字段必须包含当前检测字段");
                String mode = p.getOrDefault("nullMode", "ignore");
                require(Arrays.asList("ignore", "asValue").contains(mode), "组合唯一 NULL 模式无效");
                break;
            case "fieldCompare":
                String other = DrlSupport.requiredParam(p, "otherField", "同表比较字段");
                SourcePolicy.identifier(other);
                require(!field.equals(other), "不能与字段自身比较");
                String op = DrlSupport.requiredParam(p, "operator", "运算符");
                require(Arrays.asList("EQ", "NE", "GT", "GE", "LT", "LE").contains(op), "运算符无效");
                require(!"OTHER".equals(kind), "字段类型不支持比较");
                require(!"TEXT".equals(kind) || Arrays.asList("EQ", "NE").contains(op), "字符串只允许 EQ/NE 比较");
                zone(p);
                break;
            case "freshness":
                require(type != null && type.toLowerCase(Locale.ROOT).matches("datetime|timestamp"), "新鲜度只适用于 DATETIME/TIMESTAMP");
                windowSeconds(p);
                zone(p);
                break;
            case "referenceExists":
            case "referenceMatch":
                referenceParams(p);
                if ("referenceMatch".equals(getCode()))
                {
                    SourcePolicy.identifier(DrlSupport.requiredParam(p, "sourceKey", "源业务键"));
                    SourcePolicy.identifier(DrlSupport.requiredParam(p, "referenceValue", "参考值字段"));
                    SourcePolicy.text(DrlSupport.requiredParam(p, "basisName", "参考依据名称"), 100, "参考依据名称");
                    SourcePolicy.text(DrlSupport.requiredParam(p, "basisVersion", "参考版本说明"), 200, "参考版本说明");
                    require(!"OTHER".equals(kind), "字段类型不支持参考核验");
                    tolerance(p);
                    zone(p);
                }
                else require(Arrays.asList("NUMBER", "TEXT").contains(kind), "引用字段只支持数值或文本类型");
                break;
            default: throw new ServiceException("未知上下文模板");
        }
    }

    public static List<String> fields(Map<String, String> p)
    {
        try
        {
            List<String> result = JSON.readValue(DrlSupport.requiredParam(p, "fields", "组合字段"), new TypeReference<List<String>>() {});
            require(result != null && result.size() >= 2 && result.size() <= 5, "组合唯一需要 2～5 个字段");
            require(new HashSet<>(result).size() == result.size(), "组合字段不能重复");
            result.forEach(SourcePolicy::identifier);
            return result;
        }
        catch (java.io.IOException e) { throw new ServiceException("多字段参数必须是 JSON 字符串数组"); }
    }

    public static String zone(Map<String, String> p)
    {
        String zone = p.getOrDefault("timeZone", "UTC");
        try { ZoneId.of(zone); return zone; }
        catch (RuntimeException e) { throw new ServiceException("时区参数不合法"); }
    }

    public static long[] windowSeconds(Map<String, String> p)
    {
        String unit = p.getOrDefault("unit", "hours");
        require(Arrays.asList("minutes", "hours", "days").contains(unit), "时长单位不合法");
        long factor = "days".equals(unit) ? 86400 : "hours".equals(unit) ? 3600 : 60;
        long duration = integer(p.get("duration"), false);
        long future = integer(p.getOrDefault("futureTolerance", "0"), true);
        require(duration <= 315360000L / factor && future <= 315360000L / factor, "时长与未来容差均不得超过 3650 天");
        return new long[]{duration * factor, future * factor};
    }

    private static long integer(String text, boolean zeroAllowed)
    {
        try
        {
            require(text != null && text.matches("[0-9]+"), "时长必须为整数");
            long value = Long.parseLong(text);
            require(value >= (zeroAllowed ? 0 : 1), "时长必须为正整数，未来容差可为 0");
            return value;
        }
        catch (NumberFormatException e) { throw new ServiceException("时长超出范围"); }
    }

    public static BigDecimal tolerance(Map<String, String> p)
    {
        try
        {
            BigDecimal value = new BigDecimal(p.getOrDefault("tolerance", "0"));
            require(value.signum() >= 0 && value.precision() <= 38 && Math.abs(value.scale()) <= 38, "数值容差必须非负且不超过 38 位精度");
            return value;
        }
        catch (NumberFormatException e) { throw new ServiceException("数值容差不合法"); }
    }

    public static Integer referenceSource(Map<String, String> p)
    {
        try
        {
            int id = Integer.parseInt(DrlSupport.requiredParam(p, "referenceSourceId", "参考数据源"));
            require(id > 0, "请选择已配置的外部参考数据源");
            return id;
        }
        catch (NumberFormatException e) { throw new ServiceException("参考数据源标识不合法"); }
    }

    private static void referenceParams(Map<String, String> p)
    {
        referenceSource(p);
        SourcePolicy.identifier(DrlSupport.requiredParam(p, "referenceTable", "参考表"));
        SourcePolicy.identifier(DrlSupport.requiredParam(p, "referenceKey", "参考键字段"));
    }

    public static void require(boolean condition, String message)
    {
        if (!condition) throw new ServiceException(message);
    }

    protected TemplateParam param(String key, String label, String type, String defaultValue)
    {
        return new TemplateParam(key, label, type, true, label).withDefault(defaultValue);
    }
}
