package com.ruoyi.dqservice;

import com.ruoyi.dqservice.config.CompiledRuleCacheProperties;
import com.ruoyi.dqservice.connecter.CompiledRuleCache;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.l4.CompoundFixtures;
import com.ruoyi.dqservice.l4.PredicateCostCalibrator;
import com.ruoyi.dqservice.l4.ReferenceCompoundEvaluator;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor.GroupResult;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor.Ordering;
import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.CompoundMode;
import com.ruoyi.dqservice.template.plan.PlanOptions;
import com.ruoyi.dqservice.template.plan.PlanResult;
import com.ruoyi.dqservice.template.plan.PredicateCostProfile;
import com.ruoyi.dqservice.template.plan.PredicateKind;
import com.ruoyi.dqservice.template.plan.PredicatePlanner;
import com.ruoyi.dqservice.util.RuleParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * L4 条件排序原型基准入口。
 *
 * 类名不匹配 surefire 默认的 *Test 规则，常规回归不会执行它；需要产出实验数据时显式运行
 * {@code mvn -pl ruoyi-modules/ruoyi-dqservice -am test -Dtest=QualityL4Benchmark}。
 *
 * 分两段：
 * 1. 夹具段：固定算式夹具上的「排序结论 + 四组对照」，四组明细与独立参考求值器逐项比较；
 * 2. 网格段：行数 × 检查数 × 条件数，默认中心点 10000/30/4，10 万行只在隔离入口显式开启。
 *
 * 计时只覆盖 Drools 触发与结果聚合，不含 JDBC 取数、网络与报告落库；全部产物写入
 * target/l4-bench/run-*，不写入业务表，也不作为对外性能结论。
 */
public class QualityL4Benchmark
{
    private static final String STAMP = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

    private static final Path OUT = Paths.get(System.getProperty("bench.dir", "target/l4-bench/run-" + STAMP));

    private static final boolean CALIBRATE = Boolean.parseBoolean(System.getProperty("bench.calibrate", "false"));

    private static final int WARMUPS = Integer.parseInt(System.getProperty("bench.warmups", "2"));

    private static final int REPEATS = Integer.parseInt(System.getProperty("bench.repeats", "5"));

    private static final int[] FIXTURE_ROW_COUNTS = { 2000, 8000 };

    /** 高成本场景的行数档位；10 万行用 -Dbench.expensiveRows=100000 单独开启。 */
    private static final int[] EXPENSIVE_ROW_COUNTS = ints("bench.expensiveRows", "2000,8000");

    private static final boolean EXPENSIVE = Boolean.parseBoolean(System.getProperty("bench.expensive", "true"));

    /** 网格参数：默认中心点 10000 行 / 30 项检查 / 4 个条件，可按逗号列举多种取值。 */
    private static final int[] GRID_ROWS = ints("bench.rows", "10000");

    private static final int[] GRID_CHECKS = ints("bench.checks", "30");

    private static final int[] GRID_CONDITIONS = ints("bench.conditions", "4");

    private static final int ISOLATED_ROWS = 100000;

    private static final boolean ISOLATED = Boolean.parseBoolean(System.getProperty("bench.isolated", "false"));

    /** 网格段会在同一 JVM 编译上百条不同 DRL，缓存容量放大到不会被淘汰。 */
    private static final int CACHE_SIZE = 4096;

    private static final CompoundMode[] GROUP_MODE = { CompoundMode.EXPLICIT, CompoundMode.EXPLICIT,
            CompoundMode.NATIVE, CompoundMode.NATIVE };

    private static final Ordering[] GROUP_ORDER = { Ordering.ORIGINAL, Ordering.SORTED, Ordering.ORIGINAL,
            Ordering.SORTED };

    private final CompiledRuleCache ruleCache = newCache();

    private final List<String[]> plans = new ArrayList<>();

    private final List<String[]> predicates = new ArrayList<>();

    private final List<String[]> groups = new ArrayList<>();

    private final List<String[]> calls = new ArrayList<>();

    private final List<String> notes = new ArrayList<>();

    @Test
    public void runAll() throws Exception
    {
        CompoundCheckExecutor executor = new CompoundCheckExecutor(new DroolsManager(ruleCache));
        // 首次编译包含 Drools 框架类加载，先预热一次，避免污染第一行数据。
        executor.run(CompoundFixtures.guardChainPlan(), CompoundMode.EXPLICIT, Ordering.ORIGINAL,
                CompoundFixtures.codeRows(64), CompoundFixtures.DETECTED_AT, false);

        measure("codeFormat", CompoundFixtures.codeFormatPlan(), executor);
        measure("amount", CompoundFixtures.amountPlan(), executor);
        measure("guardChain", CompoundFixtures.guardChainPlan(), executor);
        if (EXPENSIVE) measure("expensive", CompoundFixtures.expensivePlan(), executor, EXPENSIVE_ROW_COUNTS);
        grid(executor);
        notes.add("编译缓存：maximumSize=" + CACHE_SIZE + "，构建成功=" + ruleCache.getBuildSuccessCount()
                + "，失败=" + ruleCache.getBuildFailureCount());
        writeTables();
    }

    private static CompiledRuleCache newCache()
    {
        CompiledRuleCacheProperties properties = new CompiledRuleCacheProperties();
        properties.setMaximumSize(CACHE_SIZE);
        return new CompiledRuleCache(properties);
    }

    // ---------------------------------------------------------------- 夹具段

    /** 同一份计划在多个行数下：排序一次，四组各计时若干次，并单独跑一遍诊断模式。 */
    private void measure(String label, CompoundCheckPlan plan, CompoundCheckExecutor executor)
    {
        measure(label, plan, executor, FIXTURE_ROW_COUNTS);
    }

    private void measure(String label, CompoundCheckPlan plan, CompoundCheckExecutor executor, int[] rowCounts)
    {
        for (int rowCount : rowCounts)
        {
            List<Map<String, Object>> rows = rowsFor(plan.getCheckId(), rowCount);
            PlanOptions options = options(plan, rows);
            long planStarted = System.nanoTime();
            PlanResult result = new PredicatePlanner().plan(plan, rows, options);
            long planningNs = System.nanoTime() - planStarted;
            CompoundCheckPlan applied = result.applyTo(plan);
            recordPlan(label, "fixture", rowCount, result, applied, rows, planningNs);

            List<String> expected = CompoundFixtures.canonical(ReferenceCompoundEvaluator.evaluate(
                    applied.withOrder(applied.originalOrder()), rows));
            recordGroups(label, "fixture", rowCount, executor, applied, rows, expected);
            for (int index = 0; index < GROUP_MODE.length; index++)
                recordCalls(label, "fixture", rowCount, executor, applied, rows, GROUP_MODE[index],
                        GROUP_ORDER[index]);
        }
    }

    /** 夹具按检查标识选择数据算式，不同夹具的字段集合不同。 */
    private static List<Map<String, Object>> rowsFor(String checkId, int rowCount)
    {
        if (CompoundFixtures.AMOUNT_CHECK_ID.equals(checkId)) return CompoundFixtures.amountRows(rowCount);
        if (CompoundFixtures.EXPENSIVE_CHECK_ID.equals(checkId)) return CompoundFixtures.expensiveRows(rowCount);
        return CompoundFixtures.codeRows(rowCount);
    }

    private PlanOptions options(CompoundCheckPlan plan, List<Map<String, Object>> rows)
    {
        if (!CALIBRATE) return CompoundFixtures.options(plan);
        Map<String, Double> measured = new PredicateCostCalibrator().calibrate(plan, rows);
        return PlanOptions.builder().costSource("calibrated").fixedCosts(measured).build();
    }

    private void recordPlan(String label, String testCase, int rowCount, PlanResult result,
            CompoundCheckPlan applied, List<Map<String, Object>> rows, long planningNs)
    {
        plans.add(new String[] { label, testCase, String.valueOf(rowCount),
                String.valueOf(result.getPlanningSamples()), String.valueOf(result.getValidationSamples()),
                result.getCostSource(), result.getReason().name(), number(result.getPredictedGain()),
                number(result.getEstimatedOriginalCost()), number(result.getEstimatedCandidateCost()),
                String.join(" ", result.getOriginalOrder()), String.join(" ", result.getCandidateOrder()),
                String.join(" ", result.getFinalOrder()), String.valueOf(applied.isSorted()),
                applied.getFingerprint(), "rows=" + rows.size(), millisecondsOf(planningNs) });
        for (PredicateCostProfile profile : result.getProfiles())
            predicates.add(new String[] { label, String.valueOf(rowCount), profile.getPredicateId(),
                    String.valueOf(profile.getCostNs()), String.valueOf(profile.getApplicableSamples()),
                    String.valueOf(profile.getRejectionSamples()), number(profile.getRejectionRate()),
                    String.valueOf(profile.getSelectedPosition()), profile.getCostSource(), profile.getNote() });
    }

    /**
     * 四组计时。每轮轮换起始组，使每个组都均等地落在“第一个执行”的位置，
     * 避免首组系统性地吸收 JIT 预热与 GC 代价。
     */
    private void recordGroups(String label, String testCase, int rowCount, CompoundCheckExecutor executor,
            CompoundCheckPlan plan, List<Map<String, Object>> rows, List<String> expected)
    {
        long[][] fire = new long[GROUP_MODE.length][REPEATS];
        long[][] aggregate = new long[GROUP_MODE.length][REPEATS];
        long[][] wall = new long[GROUP_MODE.length][REPEATS];
        GroupResult[] last = new GroupResult[GROUP_MODE.length];
        for (int round = 0; round < WARMUPS + REPEATS; round++)
        {
            int offset = round % GROUP_MODE.length;
            for (int k = 0; k < GROUP_MODE.length; k++)
            {
                int index = (k + offset) % GROUP_MODE.length;
                long started = System.nanoTime();
                GroupResult group = executor.run(plan, GROUP_MODE[index], GROUP_ORDER[index], rows,
                        CompoundFixtures.DETECTED_AT, false);
                long elapsed = System.nanoTime() - started;
                last[index] = group;
                if (round >= WARMUPS)
                {
                    fire[index][round - WARMUPS] = group.getFireNs();
                    aggregate[index][round - WARMUPS] = group.getAggregateNs();
                    wall[index][round - WARMUPS] = elapsed;
                }
            }
        }
        for (int index = 0; index < GROUP_MODE.length; index++)
        {
            GroupResult group = last[index];
            String consistent = expected.equals(CompoundFixtures.canonical(group.getViolations())) ? "yes" : "no";
            groups.add(new String[] { label, testCase, String.valueOf(rowCount), GROUP_MODE[index].name(),
                    GROUP_ORDER[index].name(), String.valueOf(group.getChecked()),
                    String.valueOf(group.getViolationCount()), String.valueOf(group.getViolationRows()),
                    String.valueOf(group.getPassRate()), consistent, milliseconds(fire[index]),
                    milliseconds(aggregate[index]), milliseconds(wall[index]) });
        }
    }

    /** 诊断版：逐谓词调用次数、昂贵谓词调用次数、执行顺序与规则触发数。 */
    private void recordCalls(String label, String testCase, int rowCount, CompoundCheckExecutor executor,
            CompoundCheckPlan plan, List<Map<String, Object>> rows, CompoundMode mode, Ordering ordering)
    {
        GroupResult group = executor.run(plan, mode, ordering, rows, CompoundFixtures.DETECTED_AT, true);
        Map<String, Integer> counts = group.getPredicateCalls().get(plan.getCheckId());
        int expensive = 0;
        StringBuilder detail = new StringBuilder();
        for (String predicateId : plan.getSelectedOrder())
        {
            Integer value = counts.get(predicateId);
            if (detail.length() > 0) detail.append(';');
            detail.append(predicateId).append('=').append(value);
            if (isExpensive(plan.predicate(predicateId).getKind()) && value != null) expensive += value;
        }
        calls.add(new String[] { label, testCase, String.valueOf(rowCount), mode.name(), ordering.name(),
                String.valueOf(group.getTriggerCount()), String.valueOf(expensive),
                // 逐原子计数已经证明实际执行顺序，这里按它记录，避免把 SORTED 的序写到 ORIGINAL 行上。
                String.join(" ", group.getPlan().getSelectedOrder()), detail.toString() });
    }

    private static boolean isExpensive(PredicateKind kind)
    {
        switch (kind)
        {
            case REGEX_MATCH:
            case REGEX_NOT_MATCH:
            case TEXT_IN:
            case TEXT_NOT_IN:
                return true;
            default:
                return false;
        }
    }

    // ---------------------------------------------------------------- 网格段

    /**
     * 规模网格：行数 × 检查数 × 条件数。
     *
     * 每格对每个检查独立排序后串行执行，四组各计时若干次；明细一致性用「四组违规条数必须相同」
     * 判定，不再重复跑独立参考求值器（夹具段已经逐项比对过）。10 万行属于隔离规模，
     * 只有显式传入 -Dbench.isolated=true 才会执行。
     */
    private void grid(CompoundCheckExecutor executor)
    {
        for (int rows : GRID_ROWS)
        {
            if (rows >= ISOLATED_ROWS && !ISOLATED)
            {
                notes.add("跳过隔离规模 rows=" + rows + "，需要 -Dbench.isolated=true 显式开启");
                continue;
            }
            for (int conditions : GRID_CONDITIONS)
                for (int checks : GRID_CHECKS)
                    measureGrid(executor, rows, checks, conditions);
        }
    }

    private void measureGrid(CompoundCheckExecutor executor, int rowCount, int checkCount, int conditions)
    {
        List<Map<String, Object>> rows = generatedRows(rowCount);
        List<CompoundCheckPlan> applied = new ArrayList<>(checkCount);
        int predicateCount = 0;
        int sortedCount = 0;
        long planningNs = 0;
        PlanResult first = null;
        for (int i = 1; i <= checkCount; i++)
        {
            CompoundCheckPlan plan = generatedPlan(conditions, "grid-c" + conditions + "-" + i);
            predicateCount = plan.getPredicates().size();
            long started = System.nanoTime();
            PlanResult result = new PredicatePlanner().plan(plan, rows, CompoundFixtures.options(plan));
            planningNs += System.nanoTime() - started;
            CompoundCheckPlan expanded = result.applyTo(plan);
            if (expanded.isSorted()) sortedCount++;
            if (first == null) first = result;
            applied.add(expanded);
        }
        String testCase = String.format(Locale.ROOT, "rows=%d,checks=%d,conditions=%d,predicates=%d",
                rowCount, checkCount, conditions, predicateCount);
        recordGridPlan(testCase, rowCount, checkCount, sortedCount, first, applied.get(0), rows, planningNs);
        recordGridGroups(testCase, rowCount, checkCount, executor, applied, rows);
        for (int index = 0; index < GROUP_MODE.length; index++)
            recordGridCalls(testCase, rowCount, executor, applied, rows, GROUP_MODE[index], GROUP_ORDER[index]);
    }

    /** 网格段的四组计时，同样每轮轮换起始组。 */
    private void recordGridGroups(String testCase, int rowCount, int checkCount,
            CompoundCheckExecutor executor, List<CompoundCheckPlan> applied, List<Map<String, Object>> rows)
    {
        long[][] fire = new long[GROUP_MODE.length][REPEATS];
        long[][] aggregate = new long[GROUP_MODE.length][REPEATS];
        long[][] wall = new long[GROUP_MODE.length][REPEATS];
        int[] violations = new int[GROUP_MODE.length];
        int[] violationRows = new int[GROUP_MODE.length];
        for (int round = 0; round < WARMUPS + REPEATS; round++)
        {
            int offset = round % GROUP_MODE.length;
            for (int k = 0; k < GROUP_MODE.length; k++)
            {
                int index = (k + offset) % GROUP_MODE.length;
                long started = System.nanoTime();
                long fireNs = 0;
                long aggregateNs = 0;
                int totalViolations = 0;
                int totalRows = 0;
                for (CompoundCheckPlan plan : applied)
                {
                    GroupResult group = executor.run(plan, GROUP_MODE[index], GROUP_ORDER[index], rows,
                            CompoundFixtures.DETECTED_AT, false);
                    fireNs += group.getFireNs();
                    aggregateNs += group.getAggregateNs();
                    totalViolations += group.getViolationCount();
                    totalRows += group.getViolationRows();
                }
                long elapsed = System.nanoTime() - started;
                violations[index] = totalViolations;
                violationRows[index] = totalRows;
                if (round >= WARMUPS)
                {
                    fire[index][round - WARMUPS] = fireNs;
                    aggregate[index][round - WARMUPS] = aggregateNs;
                    wall[index][round - WARMUPS] = elapsed;
                }
            }
        }
        String consistent = "yes";
        for (int index = 1; index < GROUP_MODE.length; index++)
            if (violations[index] != violations[0]) consistent = "no";
        for (int index = 0; index < GROUP_MODE.length; index++)
            groups.add(new String[] { "grid", testCase, String.valueOf(rowCount), GROUP_MODE[index].name(),
                    GROUP_ORDER[index].name(), String.valueOf((long) rowCount * checkCount),
                    String.valueOf(violations[index]), String.valueOf(violationRows[index]), "NA", consistent,
                    milliseconds(fire[index]), milliseconds(aggregate[index]), milliseconds(wall[index]) });
    }

    /** 网格段每格记录一行排序结论（各检查结构相同，只记首个检查的序与预测收益）。 */
    private void recordGridPlan(String testCase, int rowCount, int checkCount, int sortedCount, PlanResult result,
            CompoundCheckPlan applied, List<Map<String, Object>> rows, long planningNs)
    {
        plans.add(new String[] { "grid", testCase, String.valueOf(rowCount),
                String.valueOf(result.getPlanningSamples()), String.valueOf(result.getValidationSamples()),
                result.getCostSource(), result.getReason().name(), number(result.getPredictedGain()),
                number(result.getEstimatedOriginalCost()), number(result.getEstimatedCandidateCost()),
                String.join(" ", result.getOriginalOrder()), String.join(" ", result.getCandidateOrder()),
                String.join(" ", result.getFinalOrder()), String.valueOf(sortedCount == checkCount),
                applied.getFingerprint(), "checksSorted=" + sortedCount + "/" + checkCount + ";rows=" + rows.size(),
                millisecondsOf(planningNs) });
    }

    /** 网格段的诊断版：按谓词逐项累计全部检查的调用次数，用于观察昂贵谓词在大规模下是否被短路。 */
    private void recordGridCalls(String testCase, int rowCount, CompoundCheckExecutor executor,
            List<CompoundCheckPlan> applied, List<Map<String, Object>> rows, CompoundMode mode, Ordering ordering)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int triggers = 0;
        List<String> executedOrder = null;
        for (CompoundCheckPlan plan : applied)
        {
            GroupResult group = executor.run(plan, mode, ordering, rows, CompoundFixtures.DETECTED_AT, true);
            triggers += group.getTriggerCount();
            if (executedOrder == null) executedOrder = group.getPlan().getSelectedOrder();
            Map<String, Integer> per = group.getPredicateCalls().get(plan.getCheckId());
            for (String predicateId : executedOrder)
            {
                Integer value = per.get(predicateId);
                if (value == null) continue;
                Integer previous = counts.get(predicateId);
                counts.put(predicateId, previous == null ? value : previous + value);
            }
        }
        CompoundCheckPlan sample = applied.get(0);
        int expensive = 0;
        StringBuilder detail = new StringBuilder();
        for (String predicateId : executedOrder)
        {
            Integer value = counts.get(predicateId);
            if (detail.length() > 0) detail.append(';');
            detail.append(predicateId).append('=').append(value);
            if (isExpensive(sample.predicate(predicateId).getKind()) && value != null) expensive += value;
        }
        calls.add(new String[] { "grid", testCase, String.valueOf(rowCount), mode.name(), ordering.name(),
                String.valueOf(triggers), String.valueOf(expensive), String.join(" ", executedOrder),
                detail.toString() });
    }

    /** 生成 2/4/6 个取值条件；保护条件由构建器自动补齐，实际谓词数记录在 case 里。 */
    private static CompoundCheckPlan generatedPlan(int conditions, String checkId)
    {
        CompoundCheckPlan.Builder builder = CompoundCheckPlan
                .builder(checkId, "生成复合检查", "value", QualityDimension.VALIDITY.name())
                .reason("生成计划，声明条件数=" + conditions)
                .comparison("generated");
        if (conditions >= 4)
            // 昂贵条件刻意声明在最前，模拟“用户按直觉填写”的初始顺序。
            builder.notMatches("^M$").textIn("A,B").textEquals("A").textNotEquals("Z");
        else
            builder.textIn("A,B").textEquals("A");
        if (conditions > 4)
            builder.predicate(PredicateKind.NUMBER_GT, "amount", "1000")
                    .predicate(PredicateKind.TEXT_IN, "value", "A,B,C,D,E");
        return builder.build();
    }

    /** 10% 命中首个条件、约 1/7 触发昂贵正则，保证排序与短路都有真实选择空间。 */
    private static List<Map<String, Object>> generatedRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("value", i % 10 == 0 ? "A" : i % 7 == 0 ? "Z" : "M");
            row.put("amount", new BigDecimal(i % 4 == 0 ? "1500" : "800"));
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- 输出

    private void writeTables() throws Exception
    {
        Files.createDirectories(OUT);
        write("plans.csv", "plan,case,rows,planning,validation,costSource,reason,gain,estimatedOriginalCost,"
                + "estimatedCandidateCost,originalOrder,candidateOrder,finalOrder,sorted,fingerprint,detail,planningMs",
                plans);
        write("predicates.csv", "plan,rows,predicateId,costNs,applicableSamples,rejectedSamples,rejectionRate,"
                + "selectedPosition,costSource,note", predicates);
        write("groups.csv", "plan,case,rows,mode,ordering,checked,violations,violationRows,passRate,consistent,"
                + "fireMs,aggregateMs,wallMs", groups);
        write("calls.csv", "plan,case,rows,mode,ordering,ruleTriggers,expensiveCalls,order,calls", calls);
        writeLines("summary.md", summary());
        System.out.println("[L4-BENCH] " + OUT.toAbsolutePath());
        for (String[] row : groups) System.out.println(String.join(",", row));
    }

    private List<String> summary()
    {
        List<String> lines = new ArrayList<>();
        lines.add("# L4 条件排序原型基准");
        lines.add("");
        lines.add("- 运行时间：`" + STAMP + "`");
        lines.add("- 成本来源：`" + (CALIBRATE ? "calibrated" : "fixed-table") + "`");
        lines.add("- 预热/重复：`" + WARMUPS + "/" + REPEATS + "`");
        lines.add("- 夹具：`CompoundFixtures` 固定算式；网格：`rows=" + Arrays.toString(GRID_ROWS)
                + " checks=" + Arrays.toString(GRID_CHECKS) + " conditions=" + Arrays.toString(GRID_CONDITIONS) + "`");
        lines.add("- 计时口径：`fireMs` 为 Drools 触发耗时，`aggregateMs` 为统一聚合耗时，"
                + "`wallMs` 为整组（含上下文构建与注册）耗时");
        if (!notes.isEmpty())
        {
            lines.add("");
            for (String note : notes) lines.add("- " + note);
        }
        lines.add("");
        lines.add("## 排序结论");
        lines.add("");
        lines.add("| plan | case | rows | reason | gain | planningMs | final order |");
        lines.add("| --- | --- | --- | --- | --- | --- | --- |");
        for (String[] row : plans)
            lines.add("| " + row[0] + " | " + row[1] + " | " + row[2] + " | " + row[6] + " | " + row[7]
                    + " | " + row[16] + " | " + row[12] + " |");
        lines.add("");
        lines.add("## 四组执行");
        lines.add("");
        lines.add("| plan | case | rows | mode | ordering | checked | violations | consistent | fireMs |"
                + " aggregateMs | wallMs |");
        lines.add("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        for (String[] row : groups)
            lines.add("| " + row[0] + " | " + row[1] + " | " + row[2] + " | " + row[3] + " | " + row[4]
                    + " | " + row[5] + " | " + row[6] + " | " + row[9] + " | " + row[10] + " | " + row[11]
                    + " | " + row[12] + " |");
        lines.add("");
        lines.add("该结果只描述原型在固定夹具、固定成本与生成网格下的行为，"
                + "不代表业务系统的整体性能收益，也不等同于抵扣规划成本后的净收益。");
        return lines;
    }

    private static void write(String name, String header, List<String[]> rows) throws Exception
    {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        for (String[] row : rows)
        {
            String[] safe = new String[row.length];
            for (int i = 0; i < row.length; i++) safe[i] = row[i] == null ? "" : row[i].replace(",", ";");
            lines.add(String.join(",", safe));
        }
        writeLines(name, lines);
    }

    private static void writeLines(String name, List<String> lines) throws Exception
    {
        Files.write(OUT.resolve(name), lines, StandardCharsets.UTF_8);
    }

    private static int[] ints(String key, String fallback)
    {
        String[] parts = System.getProperty(key, fallback).split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) values[i] = Integer.parseInt(parts[i].trim());
        return values;
    }

    private static String milliseconds(long[] samples)
    {
        if (samples.length == 0) return "0";
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        double median = (sorted[(sorted.length - 1) / 2] + sorted[sorted.length / 2]) / 2.0;
        return String.format(Locale.ROOT, "%.3f/%.3f/%.3f", sorted[0] / 1e6, median / 1e6,
                sorted[sorted.length - 1] / 1e6);
    }

    private static String number(double value)
    {
        return Double.isNaN(value) ? "NA" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String millisecondsOf(long nanos)
    {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }
}
