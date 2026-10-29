package com.ruoyi.dqservice.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.util.RuleParser;
import java.time.Instant;
import java.util.*;
import lombok.Getter;

/** 一次执行一个实例，只保存值、索引与计数，不持有连接或数据源访问方法。 */
public final class QualityExecutionContext
{
    private final Instant detectedAt;
    private final List<Map<String, Object>> rows;
    private final Map<String, ReferenceData> references = new HashMap<>();
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> parameters = new HashMap<>();
    private final Map<String, Map<List<Object>, Integer>> groups = new HashMap<>();
    private static final ObjectMapper JSON = new ObjectMapper();

    public QualityExecutionContext(Instant detectedAt, Collection<Map<String, Object>> rows)
    {
        this.detectedAt = Objects.requireNonNull(detectedAt);
        this.rows = new ArrayList<>(rows);
    }

    public Instant getDetectedAt() { return detectedAt; }
    public List<Counter> getCounters() { return new ArrayList<>(counters.values()); }

    public void register(String id, String template, String field, String dimension)
    {
        counters.computeIfAbsent(id, key -> new Counter(id, template, field, dimension));
    }

    public void addReference(String id, ReferenceData data) { references.put(id, data); }

    /** 七类原模板仍由 DRL 判定；新版生成结果用稳定标识归集实际计数。 */
    public void countLegacyTemplate(TemplateCheck check, String dimension, List<Map<String, Object>> results)
    {
        register(check.getCheckId(), check.getTemplate(), check.getField(), dimension);
        Counter counter = counters.get(check.getCheckId());
        boolean includeNull = "nullCount".equals(check.getTemplate()) || ("uniqueCheck".equals(check.getTemplate())
                && "视为重复".equals(check.getParams().get("nullMode")));
        for (Map<String, Object> row : rows)
        {
            if (!includeNull && row.get(check.getField()) == null) counter.skipped++;
            else counter.checked++;
        }
        for (Map<String, Object> result : results)
            if (check.getCheckId().equals(result.get("checkId"))) counter.violations++;
    }

    public void evaluate(String id, String rule, String code, String field, String type, String dimension,
            String jsonParams, Map<String, Object> row, List<Map<String, Object>> results)
    {
        register(id, code, field, dimension);
        Counter counter = counters.get(id);
        Map<String, String> p = parameters.computeIfAbsent(id, k -> parse(jsonParams));
        Object value = row.get(field);
        Object comparison = null;
        String resultType = "VIOLATION";
        String reason = null;
        List<String> involved = new ArrayList<>(Collections.singletonList(field));
        switch (code)
        {
            case "notBlank":
                if (value == null || (value instanceof String && ((String) value).codePoints()
                        .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))) reason = "字段为 NULL、空字符串或纯空白";
                break;
            case "compositeUnique":
                involved = ContextRuleTemplate.fields(p);
                List<Object> tuple = tuple(row, involved, p);
                if (tuple == null) { counter.skipped++; return; }
                final List<String> fields = involved;
                Map<List<Object>, Integer> counts = groups.computeIfAbsent(id, k -> {
                    Map<List<Object>, Integer> indexed = new HashMap<>();
                    for (Map<String, Object> sourceRow : rows)
                    {
                        List<Object> key = tuple(sourceRow, fields, p);
                        if (key != null) indexed.merge(key, 1, Integer::sum);
                    }
                    return indexed;
                });
                if (counts.getOrDefault(tuple, 0) > 1) reason = "扫描范围内组合字段重复";
                break;
            case "fieldCompare":
                String other = p.get("otherField");
                involved.add(other);
                comparison = row.get(other);
                if (value == null || comparison == null) { counter.skipped++; return; }
                int cmp = QualityTypes.compare(value, comparison, type, ContextRuleTemplate.zone(p));
                String op = p.get("operator");
                boolean valid = "EQ".equals(op) ? cmp == 0 : "NE".equals(op) ? cmp != 0 : "GT".equals(op) ? cmp > 0
                        : "GE".equals(op) ? cmp >= 0 : "LT".equals(op) ? cmp < 0 : cmp <= 0;
                if (!valid) reason = "同表字段比较不满足 " + op;
                break;
            case "freshness":
                if (value == null) { counter.skipped++; return; }
                long[] seconds = ContextRuleTemplate.windowSeconds(p);
                Instant time = QualityTypes.instant(value, ContextRuleTemplate.zone(p));
                Instant lower = detectedAt.minusSeconds(seconds[0]);
                Instant upper = detectedAt.plusSeconds(seconds[1]);
                comparison = lower + " ～ " + upper;
                if (time.isBefore(lower) || time.isAfter(upper)) reason = "时间超出本次检测的新鲜度窗口";
                break;
            case "referenceExists":
            case "referenceMatch":
                ReferenceData reference = references.get(id);
                if (reference == null) throw new ServiceException("参考查询结果未完整准备，禁止继续评价");
                boolean match = "referenceMatch".equals(code);
                String sourceKey = match ? p.get("sourceKey") : field;
                if (!involved.contains(sourceKey)) involved.add(sourceKey);
                Object key = QualityTypes.key(row.get(sourceKey), reference.keyType);
                if (!match && key == null) { counter.skipped++; return; }
                if (match && key == null) { reason = "缺少源业务键，无法核验"; resultType = "UNVERIFIABLE"; }
                else if (!reference.values.containsKey(key))
                {
                    reason = match ? "找不到参考记录，无法核验" : "引用不存在";
                    if (match) resultType = "UNVERIFIABLE";
                }
                else if (match)
                {
                    comparison = reference.values.get(key);
                    if (value == null || comparison == null) { reason = "源值或参考值为空，无法核验"; resultType = "UNVERIFIABLE"; }
                    else
                    {
                        boolean equal = "NUMBER".equals(QualityTypes.kind(type))
                                ? QualityTypes.number(value).subtract(QualityTypes.number(comparison)).abs()
                                        .compareTo(ContextRuleTemplate.tolerance(p)) <= 0
                                : QualityTypes.compare(value, comparison, type, ContextRuleTemplate.zone(p)) == 0;
                        if (!equal) reason = "与可信参考值不一致";
                    }
                }
                break;
            default: throw new ServiceException("未知执行模板");
        }
        if ("UNVERIFIABLE".equals(resultType)) counter.unverifiable++;
        else counter.checked++;
        if (reason != null)
        {
            if ("VIOLATION".equals(resultType)) counter.violations++;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("rule", rule);
            detail.put("field", field);
            detail.put("rowId", row.get(RuleParser.ROW_ID_KEY));
            detail.put("checkId", id);
            detail.put("templateCode", code);
            detail.put("dimensionCode", dimension);
            detail.put("resultType", resultType);
            detail.put("reason", reason);
            detail.put("comparisonValue", comparison);
            Map<String, Object> values = new LinkedHashMap<>();
            for (String name : involved) values.put(name, row.get(name));
            detail.put("involvedFields", values);
            results.add(detail);
        }
    }

    private static Map<String, String> parse(String json)
    {
        try { return JSON.readValue(json, new TypeReference<Map<String, String>>() {}); }
        catch (java.io.IOException e) { throw new ServiceException("执行模板参数不可解析"); }
    }

    private static List<Object> tuple(Map<String, Object> row, List<String> fields, Map<String, String> params)
    {
        List<Object> key = new ArrayList<>();
        for (String name : fields)
        {
            if (!row.containsKey(name)) throw new ServiceException("组合字段不存在：" + name);
            Object value = row.get(name);
            if (value == null && !"asValue".equals(params.getOrDefault("nullMode", "ignore"))) return null;
            // 每个分量保留 Java 类型标签与原值，避免字符串拼接碰撞或文本数字混淆。
            key.add(value == null ? null : Arrays.asList(value.getClass().getName(), value));
        }
        return key;
    }

    public static final class ReferenceData
    {
        private final String keyType;
        private final Map<Object, Object> values;
        @Getter private final String readAt;
        public ReferenceData(String keyType, Map<Object, Object> values, Instant readAt)
        {
            this.keyType = keyType;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.readAt = readAt.toString();
        }
    }

    @Getter
    public static final class Counter
    {
        private final String checkId;
        private final String templateCode;
        private final String field;
        private final String dimensionCode;
        private int checked;
        private int skipped;
        private int unverifiable;
        private int violations;
        private Counter(String id, String template, String field, String dimension)
        {
            checkId = id;
            templateCode = template;
            this.field = field;
            dimensionCode = dimension;
        }
    }
}
