package com.ruoyi.dqservice;

import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.mapper.ExecutionRecordMapper;
import com.ruoyi.dqservice.mapper.ExecutionViolationMapper;
import com.ruoyi.dqservice.service.ReportService;
import com.ruoyi.dqservice.template.ContextRuleTemplate;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import com.ruoyi.dqservice.template.TemplateCheck;
import com.ruoyi.dqservice.template.impl.EnumMatchTemplate;
import com.ruoyi.dqservice.template.impl.RegexMatchTemplate;
import com.ruoyi.dqservice.util.QualityStatistics;
import com.ruoyi.dqservice.util.RuleParser;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L5 优化前后性能对比基准。
 *
 * 类名不匹配 surefire 默认的 *Test 规则，常规回归不会执行它；
 * 需要对比时用 -Dtest=QualityL5Benchmark 显式运行，两个版本使用同一份源码，
 * 因此基准里只允许出现两版都存在的 API，较新的 aggregate 与缓存开关走反射。
 *
 * 测量的是规则编译/执行与结果处理层，不含 JDBC 取数、网络与真实数据库写入。
 */
public class QualityL5Benchmark
{
    private static final String LABEL = System.getProperty("bench.label", "current");
    private static final String OUT = System.getProperty("bench.out", "target/l5-bench/" + LABEL + ".csv");
    private final List<String[]> records = new ArrayList<>();

    @Test
    public void runAll() throws Exception
    {
        runCompileCache();
        runRegexMatch();
        runEnumMatch();
        runFreshness();
        runAggregate();
        runReportBatch();
        write();
    }

    /** 场景一：同一 DRL 重复执行，基线每次重建编译产物，优化版复用 KieBase。 */
    private void runCompileCache() throws Exception
    {
        List<Map<String, Object>> rows = valueRows(50);
        // 首次编译包含 Drools 框架类加载，先预热一次，避免污染规则数对比。
        new DroolsManager().fireRules(multiRuleDrl(1), rows);
        for (int ruleCount : new int[] { 1, 8, 32 })
        {
            String drl = multiRuleDrl(ruleCount);
            DroolsManager manager = new DroolsManager();
            Runnable call = () -> manager.fireRules(drl, rows);
            long started = System.nanoTime();
            call.run();
            long first = System.nanoTime() - started;
            long[] repeated = measure(1, 8, call);
            add("compile_cache", LABEL, "rules=" + ruleCount + ".first", new long[] { first }, "drl_bytes=" + drl.length());
            add("compile_cache", LABEL, "rules=" + ruleCount + ".repeat", repeated, "drl_bytes=" + drl.length());
        }
        // 新版本关闭缓存后应与基线等速，用来把差异归因到缓存本身。
        String bypassDrl = multiRuleDrl(32);
        Runnable bypass = disabledCacheRunner(bypassDrl, rows);
        if (bypass == null)
        {
            add("compile_cache", LABEL + "-nocache", "rules=32.repeat", new long[] { 0 }, "该版本没有缓存开关");
        }
        else
        {
            add("compile_cache", LABEL + "-nocache", "rules=32.repeat", measure(0, 8, bypass), "enabled=false");
        }
    }

    /** 场景二：8 条正则检查，基线每行重建 Pattern，优化版按执行复用 Pattern 对象。 */
    private void runRegexMatch()
    {
        RegexMatchTemplate template = new RegexMatchTemplate();
        String[] patterns = { "^[A-Z]{3}[0-9]{4}$", "^[a-z]+-[0-9]+$", "^[0-9]{6}$", "^[A-Z]{2,5}$",
                "^[a-z0-9]{8}$", "^[A-Z]{4}-[0-9]{2}$", "^item-[0-9]{3}$", "^[0-9]{4}[A-Z]$" };
        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        for (int i = 0; i < patterns.length; i++)
        {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("pattern", patterns[i]);
            drl.append(template.buildRule("bench_regex_" + i, "v", "VARCHAR", params));
        }
        KieBase base = compileOnce(drl.toString());
        for (int rows : new int[] { 2000, 8000 })
        {
            List<Map<String, Object>> data = regexRows(rows);
            long[] samples = measure(2, 5, () -> fire(base, data));
            add("regex_match", LABEL, "checks=8,rows=" + rows, samples, "session+insert+fire");
        }
    }

    /** 场景三：4 条枚举检查，基线每行重建允许值列表，优化版复用只读集合。 */
    private void runEnumMatch()
    {
        EnumMatchTemplate template = new EnumMatchTemplate();
        String[] valueSets = { "OK,A,B,C", "OK,D,E", "OK,F,G,H", "OK,I,J,K" };
        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        for (int i = 0; i < valueSets.length; i++)
        {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("values", valueSets[i]);
            drl.append(template.buildRule("bench_enum_" + i, "v", "VARCHAR", params));
        }
        KieBase base = compileOnce(drl.toString());
        for (int rows : new int[] { 2000, 8000 })
        {
            List<Map<String, Object>> data = enumRows(rows);
            long[] samples = measure(2, 5, () -> fire(base, data));
            add("enum_match", LABEL, "checks=4,rows=" + rows, samples, "session+insert+fire");
        }
    }

    /** 场景四：上下文模板逐行评价，基线每行解析时区与新鲜度窗口，优化版按执行复用。 */
    private void runFreshness()
    {
        String id = ContextRuleTemplate.checkId("v", "freshness");
        String json = "{\"duration\":\"24\",\"unit\":\"hours\",\"futureTolerance\":\"0\",\"timeZone\":\"Asia/Shanghai\"}";
        String drl = DrlSupport.HEADER + "rule \"bench_fresh\"\n"
                + "    when\n        $row: Map()\n"
                + "    then\n        qualityContext.evaluate(\"" + id + "\", \"bench_fresh\", \"freshness\", \"v\", "
                + "\"DATETIME\", \"TIMELINESS\", \"" + DrlSupport.escape(json) + "\", $row, violations);\nend\n";
        KieBase base = compileOnce(drl);
        for (int rows : new int[] { 2000, 8000 })
        {
            List<Map<String, Object>> data = freshnessRows(rows);
            long[] samples = measure(2, 5, () -> fire(base, data));
            add("freshness", LABEL, "rows=" + rows, samples, "fire_ns_only");
        }
    }

    /** 场景五：结果统计，旧算法按检查重复扫描，新算法一次遍历。 */
    private void runAggregate() throws Exception
    {
        int totalRows = 30000;
        int checks = 8;
        int results = 20000;
        List<Map<String, Object>> rows = aggregateRows(totalRows);
        Set<String> fields = new HashSet<>(Arrays.asList("v", "w"));
        List<TemplateCheck> templates = aggregateChecks(checks);
        int repeats = 9;

        List<List<Map<String, Object>>> legacyInputs = resultCopies(results, repeats);
        long[] legacy = measure(0, repeats, () -> legacyAggregate(legacyInputs.remove(0), rows, templates, fields,
                new QualityExecutionContext(Instant.now(), rows)));
        add("aggregate", LABEL, "rows=30000,checks=8,results=20000", legacy, "legacy O(C*(N+M))");

        Method aggregate = null;
        try
        {
            aggregate = QualityStatistics.class.getMethod("aggregate", List.class, List.class, Set.class);
        }
        catch (NoSuchMethodException ignored)
        {
            // 基线版本没有统一聚合入口
        }
        if (aggregate == null)
        {
            add("aggregate", LABEL, "rows=30000,checks=8,results=20000.new", new long[] { 0 }, "该版本无 aggregate");
            return;
        }
        final Method call = aggregate;
        List<List<Map<String, Object>>> newInputs = resultCopies(results, repeats);
        List<Object> summaries = new ArrayList<>();
        long[] current = measure(0, repeats, () -> currentAggregate(call, newInputs.remove(0), rows, templates, fields, summaries));
        add("aggregate", LABEL, "rows=30000,checks=8,results=20000.new", current, "single pass");

        // 同一份数据上核对新旧计数一致，避免只比较耗时。
        String legacyCounts = aggregateCounts(resultCopies(results, 1), rows, templates, fields, false);
        String newCounts = aggregateCounts(resultCopies(results, 1), rows, templates, fields, true);
        add("aggregate_check", LABEL, "counts_equal=" + legacyCounts.equals(newCounts), new long[] { 0 },
                legacyCounts.length() > 400 ? legacyCounts.substring(0, 400) : legacyCounts);
    }

    /** 场景六：报告明细转换，基线一次性生成全部实体，优化版每批 200 条。 */
    private void runReportBatch()
    {
        for (int count : new int[] { 200, 2000, 20000 })
        {
            // 预热一次，避免类加载与 JIT 计入首个样本。
            new ReportHarness(new AtomicInteger()).save(reportResults(count, new AtomicInteger()));
            long[] samples = new long[3];
            String detail = "";
            for (int i = 0; i < samples.length; i++)
            {
                AtomicInteger conversions = new AtomicInteger();
                ReportHarness harness = new ReportHarness(conversions);
                List<Map<String, Object>> results = reportResults(count, conversions);
                long started = System.nanoTime();
                harness.save(results);
                samples[i] = System.nanoTime() - started;
                detail = "first_batch_converted=" + harness.firstBatchConverted + ",batches=" + harness.batches.size()
                        + ",max_batch=" + harness.maxBatch();
            }
            add("report_batch", LABEL, "results=" + count, samples, detail);
        }
    }

    // ---------------------------------------------------------------- 聚合实现

    /** 按 HEAD 版 RuleExecuteService 的两次遍历与逐检查扫描复刻旧路径。 */
    private int legacyAggregate(List<Map<String, Object>> results, List<Map<String, Object>> rows,
            List<TemplateCheck> templates, Set<String> fields, QualityExecutionContext context)
    {
        QualityStatistics.countViolationRows(results, rows.size());
        Map<Object, Map<String, Object>> index = new HashMap<>();
        for (Map<String, Object> row : rows) index.put(row.get(RuleParser.ROW_ID_KEY), row);
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Map<String, Object> detail : results)
        {
            Map<String, Object> row = index.get(detail.get("rowId"));
            if (row != null) detail.put("row", row);
            if (!"UNVERIFIABLE".equals(detail.get("resultType"))) violations.add(detail);
        }
        int violationRows = QualityStatistics.countViolationRows(violations, rows.size());
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (Map<String, Object> violation : violations)
        {
            Object field = violation.get("field");
            counter.merge(field == null ? "unknown" : field.toString(), 1, Integer::sum);
        }
        for (TemplateCheck check : templates) context.countLegacyTemplate(check, "COMPLETENESS", results);
        return violationRows + counter.size() + context.getCounters().size();
    }

    /** 优化版路径：一次聚合 + 用聚合结果回填旧模板计数。 */
    private int currentAggregate(Method aggregate, List<Map<String, Object>> results, List<Map<String, Object>> rows,
            List<TemplateCheck> templates, Set<String> fields, List<Object> summaries)
    {
        Object summary = invoke(aggregate, null, results, rows, fields);
        summaries.add(summary);
        List<?> violations = (List<?>) invoke(getter(summary, "getViolations"), summary);
        List<?> fieldStats = (List<?>) invoke(getter(summary, "getFieldStats"), summary);
        Method count = method(QualityExecutionContext.class, "countLegacyTemplate", TemplateCheck.class,
                String.class, summary.getClass());
        QualityExecutionContext context = new QualityExecutionContext(Instant.now(), rows);
        for (TemplateCheck check : templates) invoke(count, context, check, "COMPLETENESS", summary);
        return violations.size() + fieldStats.size() + context.getCounters().size();
    }

    private String aggregateCounts(List<List<Map<String, Object>>> inputs, List<Map<String, Object>> rows,
            List<TemplateCheck> templates, Set<String> fields, boolean current)
    {
        List<Map<String, Object>> results = inputs.remove(0);
        QualityExecutionContext context = new QualityExecutionContext(Instant.now(), rows);
        if (current)
        {
            try
            {
                Method aggregate = QualityStatistics.class.getMethod("aggregate", List.class, List.class, Set.class);
                Object summary = invoke(aggregate, null, results, rows, fields);
                Method count = method(QualityExecutionContext.class, "countLegacyTemplate", TemplateCheck.class,
                        String.class, summary.getClass());
                for (TemplateCheck check : templates) invoke(count, context, check, "COMPLETENESS", summary);
            }
            catch (Exception e)
            {
                return "unavailable";
            }
        }
        else
        {
            legacyAggregate(results, rows, templates, fields, context);
        }
        List<String> counters = new ArrayList<>();
        for (Object counter : context.getCounters()) counters.add(counterText(counter));
        Collections.sort(counters);
        return counters.toString();
    }

    private String counterText(Object counter)
    {
        return ReflectionTestUtils.getField(counter, "checkId") + ":"
                + ReflectionTestUtils.getField(counter, "checked") + "/"
                + ReflectionTestUtils.getField(counter, "skipped") + "/"
                + ReflectionTestUtils.getField(counter, "violations");
    }

    // ---------------------------------------------------------------- 数据构造

    private String multiRuleDrl(int ruleCount)
    {
        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        for (int i = 0; i < ruleCount; i++)
            drl.append(DrlSupport.ruleBlock("bench_" + i, "v", "c" + i, "this[\"v\"] == \"NOMATCH_" + i + "\""));
        return drl.toString();
    }

    private List<Map<String, Object>> valueRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("v", "row-" + i);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> regexRows(int count)
    {
        String[] values = { "ABC1234", "abc-12", "123456", "ABCDE", "abc12345", "ABCD-12", "item-123", "1234A" };
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("v", values[i % values.length]);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> enumRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("v", "OK");
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> freshnessRows(int count)
    {
        Timestamp value = Timestamp.valueOf("2026-10-29 12:00:00");
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("v", value);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> aggregateRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            if (i % 10 != 0)
            {
                row.put("v", "v" + i);
                row.put("w", "w" + i);
            }
            rows.add(row);
        }
        return rows;
    }

    private List<TemplateCheck> aggregateChecks(int count)
    {
        List<TemplateCheck> checks = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            TemplateCheck check = new TemplateCheck();
            check.setCheckId("c" + i);
            check.setTemplate(i == 0 ? "nullCount" : "regexMatch");
            check.setField(i % 2 == 0 ? "v" : "w");
            check.setParams(new LinkedHashMap<>());
            checks.add(check);
        }
        return checks;
    }

    private List<List<Map<String, Object>>> resultCopies(int count, int copies)
    {
        List<List<Map<String, Object>>> all = new ArrayList<>(copies);
        for (int c = 0; c < copies; c++) all.add(aggregateResults(count));
        return all;
    }

    private List<Map<String, Object>> aggregateResults(int count)
    {
        List<Map<String, Object>> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("checkId", "c" + (i % 8));
            detail.put("field", i % 3 == 0 ? "v" : "w");
            detail.put("rowId", i + 1);
            detail.put("rule", "bench");
            detail.put("resultType", i % 10 == 0 ? "UNVERIFIABLE" : "VIOLATION");
            results.add(detail);
        }
        return results;
    }

    private List<Map<String, Object>> reportResults(int count, AtomicInteger conversions)
    {
        List<Map<String, Object>> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new HashMap<>();
            row.put("id", "rec-" + i);
            row.put("v", new CountingValue(conversions));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("checkId", "c0");
            detail.put("field", "v");
            detail.put("rowId", i + 1);
            detail.put("rule", "bench");
            detail.put("resultType", "VIOLATION");
            detail.put("row", row);
            results.add(detail);
        }
        return results;
    }

    // ---------------------------------------------------------------- 引擎执行

    private KieBase compileOnce(String drl)
    {
        KieHelper helper = new KieHelper();
        helper.addContent(drl, ResourceType.DRL);
        Results results = helper.verify();
        List<Message> errors = results.getMessages(Message.Level.ERROR);
        if (errors != null && !errors.isEmpty()) throw new IllegalStateException(errors.toString());
        return helper.build();
    }

    private long fire(KieBase base, List<Map<String, Object>> rows)
    {
        KieSession session = base.newKieSession();
        try
        {
            List<Map<String, Object>> violations = new ArrayList<>();
            session.setGlobal("violations", violations);
            session.setGlobal("qualityContext", new QualityExecutionContext(Instant.now(), rows));
            for (Map<String, Object> row : rows) session.insert(row);
            long started = System.nanoTime();
            session.fireAllRules();
            return System.nanoTime() - started;
        }
        finally
        {
            session.dispose();
        }
    }

    /** 仅新版本可构造关闭缓存的 DroolsManager，基线返回 null。 */
    private Runnable disabledCacheRunner(String drl, List<Map<String, Object>> rows)
    {
        try
        {
            Class<?> propertiesType = Class.forName("com.ruoyi.dqservice.config.CompiledRuleCacheProperties");
            Object properties = propertiesType.newInstance();
            propertiesType.getMethod("setEnabled", boolean.class).invoke(properties, false);
            Class<?> cacheType = Class.forName("com.ruoyi.dqservice.connecter.CompiledRuleCache");
            Object cache = cacheType.getConstructor(propertiesType).newInstance(properties);
            Object manager = DroolsManager.class.getConstructor(cacheType).newInstance(cache);
            Method fire = DroolsManager.class.getMethod("fireRules", String.class, Collection.class);
            return () -> {
                try
                {
                    fire.invoke(manager, drl, rows);
                }
                catch (ReflectiveOperationException e)
                {
                    throw new IllegalStateException(e);
                }
            };
        }
        catch (ReflectiveOperationException | RuntimeException e)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------- 报告桩

    private static final class ReportHarness
    {
        private final ReportService service = new ReportService();
        private final List<Integer> batches = new ArrayList<>();
        private int firstBatchConverted = -1;

        private ReportHarness(AtomicInteger conversions)
        {
            ExecutionViolationMapper violations = (ExecutionViolationMapper) Proxy.newProxyInstance(
                    ExecutionViolationMapper.class.getClassLoader(), new Class<?>[] { ExecutionViolationMapper.class },
                    counter(conversions));
            ExecutionRecordMapper records = (ExecutionRecordMapper) Proxy.newProxyInstance(
                    ExecutionRecordMapper.class.getClassLoader(), new Class<?>[] { ExecutionRecordMapper.class },
                    (proxy, method, args) -> {
                        if (method.getName().equals("insertExecutionRecord"))
                            ((ExecutionRecord) args[0]).setExecutionId(1L);
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    });
            ReflectionTestUtils.setField(service, "executionViolationMapper", violations);
            ReflectionTestUtils.setField(service, "executionRecordMapper", records);
        }

        private InvocationHandler counter(AtomicInteger conversions)
        {
            return (proxy, method, args) -> {
                if (method.getName().equals("insertViolationBatch"))
                {
                    List<?> batch = (List<?>) args[0];
                    if (firstBatchConverted < 0) firstBatchConverted = conversions.get();
                    batches.add(batch.size());
                }
                if (method.getReturnType() == int.class) return 0;
                return null;
            };
        }

        private void save(List<Map<String, Object>> results)
        {
            ExecutionRecord record = new ExecutionRecord();
            record.setEvaluationStatus("SUCCESS");
            record.setRuleId(1L);
            record.setRuleName("bench");
            service.saveEvaluation(record, results);
        }

        private int maxBatch()
        {
            int max = 0;
            for (Integer size : batches) max = Math.max(max, size);
            return max;
        }
    }

    private static final class CountingValue
    {
        private final AtomicInteger conversions;

        private CountingValue(AtomicInteger conversions)
        {
            this.conversions = conversions;
        }

        @Override
        public String toString()
        {
            conversions.incrementAndGet();
            return "bench-value";
        }
    }

    // ---------------------------------------------------------------- 反射与统计

    private static Method method(Class<?> type, String name, Class<?>... parameters)
    {
        try
        {
            return type.getMethod(name, parameters);
        }
        catch (NoSuchMethodException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static Method getter(Object target, String name)
    {
        return method(target.getClass(), name);
    }

    private static Object invoke(Method method, Object target, Object... args)
    {
        try
        {
            return method.invoke(target, args);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private static long[] measure(int warmups, int repeats, Runnable action)
    {
        for (int i = 0; i < warmups; i++) action.run();
        long[] samples = new long[repeats];
        for (int i = 0; i < repeats; i++)
        {
            long started = System.nanoTime();
            action.run();
            samples[i] = System.nanoTime() - started;
        }
        return samples;
    }

    private void add(String experiment, String version, String testCase, long[] samples, String detail)
    {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        double median = (sorted[(sorted.length - 1) / 2] + sorted[sorted.length / 2]) / 2.0;
        records.add(new String[] { experiment, version, testCase, String.valueOf(samples.length),
                milliseconds(sorted[0]), milliseconds(median), milliseconds(sorted[sorted.length - 1]), detail });
    }

    private static String milliseconds(double nanos)
    {
        return String.format(Locale.ROOT, "%.3f", nanos / 1000000.0);
    }

    private void write() throws Exception
    {
        Path path = Paths.get(OUT);
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("experiment,version,case,samples,min_ms,median_ms,max_ms,detail");
        for (String[] record : records)
            lines.add(String.join(",", record[0], record[1], record[2], record[3], record[4], record[5], record[6],
                    record[7].replace(",", ";")));
        Files.write(path, lines, StandardCharsets.UTF_8);
        System.out.println("[L5-BENCH] " + path.toAbsolutePath());
        for (String line : lines) System.out.println(line);
    }
}
