package com.ruoyi.dqservice.template.plan;

import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import com.ruoyi.dqservice.util.QualityStatistics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 复合检查的四组执行对照：{Java 显式短路, 原生 DRL 约束} × {原序, 排序}。
 *
 * 每轮独立 session、上下文、计数与结果容器，由 DroolsManager 在 finally 中释放 session；
 * 两种表示共享原子判定语义与明细构造，不允许用不同匹配函数制造收益。
 * 只把逐原子判断交给 Drools 的 when 约束，不在 when 内调用整条 Java 短路链。
 */
public final class CompoundCheckExecutor
{
    public enum Ordering
    {
        ORIGINAL,
        SORTED
    }

    private final DroolsManager drools;

    public CompoundCheckExecutor(DroolsManager drools)
    {
        this.drools = Objects.requireNonNull(drools, "drools");
    }

    /**
     * 执行一组对照。
     *
     * @param plan       已经带有选定顺序的计划
     * @param ordering   ORIGINAL 使用原始合法顺序，SORTED 使用计划选定顺序
     * @param diagnostic 是否在 DRL 中启用逐原子调用计数
     */
    public GroupResult run(CompoundCheckPlan plan, CompoundMode mode, Ordering ordering,
            List<Map<String, Object>> rows, Instant detectedAt, boolean diagnostic)
    {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(ordering, "ordering");
        Objects.requireNonNull(rows, "rows");
        CompoundCheckPlan applied = ordering == Ordering.ORIGINAL ? plan.withOrder(plan.originalOrder()) : plan;
        String drl = CompoundDrlBuilder.build(applied, mode, diagnostic);
        QualityExecutionContext context = new QualityExecutionContext(detectedAt, rows);
        context.registerCompound(applied, drl, mode);
        long started = System.nanoTime();
        List<Map<String, Object>> violations = drools.fireRules(drl, rows, context);
        long fireNs = System.nanoTime() - started;
        if (mode == CompoundMode.NATIVE) context.completeNativeCounting(rows.size());
        long aggregateStarted = System.nanoTime();
        // 只做统一聚合：复合检查不参与旧模板计数，避免重复累计。
        QualityStatistics.Summary summary = QualityStatistics.aggregate(violations, rows,
                Collections.<String>emptySet());
        long aggregateNs = System.nanoTime() - aggregateStarted;
        return new GroupResult(applied, mode, ordering, diagnostic, drl, rows.size(), violations, context, summary,
                fireNs, aggregateNs);
    }

    /** 依次执行四组，键为 {@code 表示-顺序}，例如 {@code NATIVE-SORTED}。 */
    public Map<String, GroupResult> runAll(CompoundCheckPlan plan, List<Map<String, Object>> rows,
            Instant detectedAt, boolean diagnostic)
    {
        Map<String, GroupResult> groups = new LinkedHashMap<>();
        for (CompoundMode mode : CompoundMode.values())
            for (Ordering ordering : Ordering.values())
                groups.put(mode.name() + "-" + ordering.name(),
                        run(plan, mode, ordering, rows, detectedAt, diagnostic));
        return groups;
    }

    public static final class GroupResult
    {
        private final CompoundCheckPlan plan;
        private final CompoundMode mode;
        private final Ordering ordering;
        private final boolean diagnostic;
        private final String drl;
        private final int rowCount;
        private final List<Map<String, Object>> violations;
        private final QualityExecutionContext context;
        private final QualityStatistics.Summary summary;
        private final long fireNs;
        private final long aggregateNs;

        private GroupResult(CompoundCheckPlan plan, CompoundMode mode, Ordering ordering, boolean diagnostic,
                String drl, int rowCount, List<Map<String, Object>> violations, QualityExecutionContext context,
                QualityStatistics.Summary summary, long fireNs, long aggregateNs)
        {
            this.plan = plan;
            this.mode = mode;
            this.ordering = ordering;
            this.diagnostic = diagnostic;
            this.drl = drl;
            this.rowCount = rowCount;
            this.violations = Collections.unmodifiableList(new ArrayList<>(violations));
            this.context = context;
            this.summary = summary;
            this.fireNs = fireNs;
            this.aggregateNs = aggregateNs;
        }

        public CompoundCheckPlan getPlan()
        {
            return plan;
        }

        public CompoundMode getMode()
        {
            return mode;
        }

        public Ordering getOrdering()
        {
            return ordering;
        }

        public boolean isDiagnostic()
        {
            return diagnostic;
        }

        public String getDrl()
        {
            return drl;
        }

        public int getRowCount()
        {
            return rowCount;
        }

        public List<Map<String, Object>> getViolations()
        {
            return violations;
        }

        public List<QualityExecutionContext.Counter> getCounters()
        {
            return context.getCounters();
        }

        public QualityStatistics.Summary getSummary()
        {
            return summary;
        }

        public int getChecked()
        {
            return counter().getChecked();
        }

        public int getSkipped()
        {
            return counter().getSkipped();
        }

        public int getUnverifiable()
        {
            return counter().getUnverifiable();
        }

        public int getViolationCount()
        {
            return counter().getViolations();
        }

        public int getViolationRows()
        {
            return summary.getViolationRows();
        }

        /** 空输入不评价，返回 null 而不是 100%。 */
        public Double getPassRate()
        {
            return QualityStatistics.passRate(rowCount, summary.getViolationRows());
        }

        /**
         * 诊断用的规则触发数：显式组通常每行触发一次，原生组只触发命中项。
         * 这里只做口径提示，不作为性能结论。
         */
        public int getTriggerCount()
        {
            return mode == CompoundMode.EXPLICIT ? rowCount : violations.size();
        }

        public Map<String, Map<String, Integer>> getPredicateCalls()
        {
            return context.getCompoundCalls();
        }

        public long getFireNs()
        {
            return fireNs;
        }

        public long getAggregateNs()
        {
            return aggregateNs;
        }

        private QualityExecutionContext.Counter counter()
        {
            for (QualityExecutionContext.Counter counter : context.getCounters())
                if (plan.getCheckId().equals(counter.getCheckId())) return counter;
            throw new IllegalStateException("复合检查未产生计数：" + plan.getCheckId());
        }

        public String describe()
        {
            return mode + "-" + ordering + "|rows=" + rowCount + "|checked=" + getChecked()
                    + "|violations=" + getViolationCount() + "|violationRows=" + getViolationRows()
                    + "|passRate=" + getPassRate() + "|sorted=" + plan.isSorted()
                    + "|fireNs=" + fireNs + "|aggregateNs=" + aggregateNs;
        }
    }
}
