package com.ruoyi.dqservice.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.CompoundCheckRuntime;
import com.ruoyi.dqservice.template.plan.CompoundMode;
import com.ruoyi.dqservice.util.RuleParser;
import com.ruoyi.dqservice.util.QualityStatistics;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;

/** 一次执行一个实例，只保存值、索引与计数，不持有连接或数据源访问方法。 */
public final class QualityExecutionContext
{
    private final Instant detectedAt;
    private final List<Map<String, Object>> rows;
    private final Map<String, ReferenceData> references = new HashMap<>();
    private final Map<String, Counter> counters = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> parameters = new HashMap<>();
    private final Map<String, DerivedParameters> derived = new HashMap<>();
    private final Map<String, Pattern> regexPatterns = new HashMap<>();
    private final Map<String, Set<String>> enumValues = new HashMap<>();
    private final Map<String, Map<List<Object>, Integer>> groups = new HashMap<>();
    private final Map<String, CompoundCheckRuntime> compoundChecks = new LinkedHashMap<>();
    private CompoundMode compoundMode;
    private boolean nativeCountingCompleted;
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

    /** 主执行链路使用已经聚合的计数，不再次遍历事实或结果。 */
    public void countLegacyTemplate(TemplateCheck check, String dimension, QualityStatistics.Summary summary)
    {
        register(check.getCheckId(), check.getTemplate(), check.getField(), dimension);
        Counter counter = counters.get(check.getCheckId());
        boolean includeNull = "nullCount".equals(check.getTemplate()) || ("uniqueCheck".equals(check.getTemplate())
                && "视为重复".equals(check.getParams().get("nullMode")));
        int skipped = includeNull ? 0 : Objects.requireNonNull(summary.getNullsByField().get(check.getField()));
        counter.skipped += skipped;
        counter.checked += rows.size() - skipped;
        counter.violations += summary.getResultsByCheck().getOrDefault(check.getCheckId(), 0);
    }

    // ---------------------------------------------------------------- 复合检查原型入口

    /**
     * 注册一项复合检查。
     *
     * 本轮执行只允许一种执行表示；DRL 内必须带有与计划一致的指纹，避免计划与编译产物错配。
     * 空输入也会预注册零计数，使 checked 口径与执行行数一致，而 passRate 仍为 null。
     */
    public void registerCompound(CompoundCheckPlan plan, String drl, CompoundMode mode)
    {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mode, "mode");
        if (compoundMode != null && compoundMode != mode)
            throw new ServiceException("本轮执行不能混用复合检查的执行表示");
        if (drl == null || drl.indexOf(plan.getFingerprint()) < 0)
            throw new ServiceException("DRL 与复合检查计划指纹不一致：" + plan.getCheckId());
        CompoundCheckRuntime existing = compoundChecks.get(plan.getCheckId());
        if (existing != null)
        {
            if (!existing.getPlan().getFingerprint().equals(plan.getFingerprint()))
                throw new ServiceException("复合检查标识冲突：" + plan.getCheckId());
            return;
        }
        compoundMode = mode;
        if (compoundChecks.size() >= CompoundCheckPlan.MAX_COMPOUND_CHECKS)
            throw new ServiceException("单次执行最多注册 " + CompoundCheckPlan.MAX_COMPOUND_CHECKS + " 项复合检查");
        compoundChecks.put(plan.getCheckId(), new CompoundCheckRuntime(plan, mode));
        register(plan.getCheckId(), plan.getTemplateCode(), plan.getField(), plan.getDimension());
    }

    /** 原生组：由 Drools 约束逐原子调用，不计入逐谓词诊断。 */
    public boolean holds(String checkId, String predicateId, Map<String, Object> row)
    {
        return runtime(checkId, CompoundMode.NATIVE).holds(predicateId, row, false);
    }

    /** 诊断版原生组：额外累计每个原子的调用次数。 */
    public boolean holdsDiagnostic(String checkId, String predicateId, Map<String, Object> row)
    {
        return runtime(checkId, CompoundMode.NATIVE).holds(predicateId, row, true);
    }

    public void evaluateCompound(String checkId, Map<String, Object> row, List<Map<String, Object>> results)
    {
        evaluateCompound(checkId, row, results, false);
    }

    public void evaluateCompoundDiagnostic(String checkId, Map<String, Object> row, List<Map<String, Object>> results)
    {
        evaluateCompound(checkId, row, results, true);
    }

    /**
     * 显式组：每行 checked 加一，任一条件为 false 立即停止，全部成立才输出一条明细。
     * 被前置条件拦住不等于跳过，因此不计 skipped。
     */
    private void evaluateCompound(String checkId, Map<String, Object> row, List<Map<String, Object>> results,
            boolean diagnostic)
    {
        CompoundCheckRuntime runtime = runtime(checkId, CompoundMode.EXPLICIT);
        Counter counter = counters.get(checkId);
        counter.checked++;
        if (runtime.evaluateWhole(row, diagnostic))
        {
            counter.violations++;
            results.add(runtime.detail(row));
        }
    }

    /** 原生组 then 块只记录命中，不计 checked。 */
    public void recordCompoundHit(String checkId, Map<String, Object> row, List<Map<String, Object>> results)
    {
        CompoundCheckRuntime runtime = runtime(checkId, CompoundMode.NATIVE);
        counters.get(checkId).violations++;
        results.add(runtime.detail(row));
    }

    /** 原生组运行结束后一次性补齐本轮 checked；只能完成一次，不重复累计。 */
    public void completeNativeCounting(int totalRows)
    {
        if (totalRows < 0) throw new ServiceException("扫描行数不能为负数");
        if (nativeCountingCompleted) throw new ServiceException("原生统计只能完成一次");
        for (CompoundCheckRuntime runtime : compoundChecks.values())
        {
            if (runtime.getMode() != CompoundMode.NATIVE) continue;
            runtime.completeNativeCounting();
            counters.get(runtime.getPlan().getCheckId()).checked += totalRows;
        }
        nativeCountingCompleted = true;
    }

    /** 逐原子调用次数快照，仅供诊断；不参与业务计数。 */
    public Map<String, Map<String, Integer>> getCompoundCalls()
    {
        Map<String, Map<String, Integer>> snapshot = new LinkedHashMap<>();
        for (CompoundCheckRuntime runtime : compoundChecks.values())
            snapshot.put(runtime.getPlan().getCheckId(), runtime.getCalls());
        return snapshot;
    }

    public Map<String, CompoundCheckRuntime> getCompoundChecks()
    {
        return new LinkedHashMap<>(compoundChecks);
    }

    private CompoundCheckRuntime runtime(String checkId, CompoundMode mode)
    {
        CompoundCheckRuntime runtime = compoundChecks.get(checkId);
        if (runtime == null) throw new ServiceException("复合检查未注册：" + checkId);
        if (runtime.getMode() != mode)
            throw new ServiceException("复合检查执行表示与 DRL 不一致：" + checkId);
        return runtime;
    }

    /** 仅缓存参数，不缓存行的求值结果；由 DRL 的非空保护后调用。 */
    public boolean matchesRegex(String pattern, String value)
    {
        return regexPatterns.computeIfAbsent(pattern, Pattern::compile).matcher(value).matches();
    }

    public boolean matchesEnum(String values, String value)
    {
        return enumValues.computeIfAbsent(values, raw -> Collections.unmodifiableSet(Arrays.stream(raw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()))).contains(value);
    }

    public void evaluate(String id, String rule, String code, String field, String type, String dimension,
            String jsonParams, Map<String, Object> row, List<Map<String, Object>> results)
    {
        register(id, code, field, dimension);
        Counter counter = counters.get(id);
        Map<String, String> p = parameters.computeIfAbsent(id, k -> parse(jsonParams));
        DerivedParameters d = derived.computeIfAbsent(id, k -> new DerivedParameters(p, type));
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
                involved = d.fields();
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
                int cmp = QualityTypes.comparePrepared(value, comparison, d.kind(), d.zone());
                String op = p.get("operator");
                boolean valid = "EQ".equals(op) ? cmp == 0 : "NE".equals(op) ? cmp != 0 : "GT".equals(op) ? cmp > 0
                        : "GE".equals(op) ? cmp >= 0 : "LT".equals(op) ? cmp < 0 : cmp <= 0;
                if (!valid) reason = "同表字段比较不满足 " + op;
                break;
            case "freshness":
                if (value == null) { counter.skipped++; return; }
                FreshnessWindow window = d.window(detectedAt);
                Instant time = QualityTypes.instant(value, d.zone());
                comparison = window.description;
                if (time.isBefore(window.lower) || time.isAfter(window.upper)) reason = "时间超出本次检测的新鲜度窗口";
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
                        boolean equal = "NUMBER".equals(d.kind())
                                ? QualityTypes.number(value).subtract(QualityTypes.number(comparison)).abs()
                                        .compareTo(d.tolerance()) <= 0
                                : QualityTypes.comparePrepared(value, comparison, d.kind(), d.zone()) == 0;
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

    /** 各派生值首次实际使用时才计算，避免提前触发原本被 NULL 保护跳过的异常。 */
    private static final class DerivedParameters
    {
        private final Map<String, String> params;
        private final String type;
        private List<String> fields;
        private String kind;
        private ZoneId zone;
        private BigDecimal tolerance;
        private FreshnessWindow window;

        private DerivedParameters(Map<String, String> params, String type)
        {
            this.params = params;
            this.type = type;
        }

        private List<String> fields()
        {
            if (fields == null) fields = Collections.unmodifiableList(ContextRuleTemplate.fields(params));
            return fields;
        }

        private String kind()
        {
            if (kind == null) kind = QualityTypes.kind(type);
            return kind;
        }

        private ZoneId zone()
        {
            if (zone == null) zone = ZoneId.of(ContextRuleTemplate.zone(params));
            return zone;
        }

        private BigDecimal tolerance()
        {
            if (tolerance == null) tolerance = ContextRuleTemplate.tolerance(params);
            return tolerance;
        }

        private FreshnessWindow window(Instant detectedAt)
        {
            if (window == null)
            {
                long[] seconds = ContextRuleTemplate.windowSeconds(params);
                window = new FreshnessWindow(detectedAt.minusSeconds(seconds[0]), detectedAt.plusSeconds(seconds[1]));
            }
            return window;
        }
    }

    private static final class FreshnessWindow
    {
        private final Instant lower;
        private final Instant upper;
        private final String description;

        private FreshnessWindow(Instant lower, Instant upper)
        {
            this.lower = lower;
            this.upper = upper;
            description = lower + " ～ " + upper;
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
