package com.ruoyi.dqservice.template.plan;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.util.RuleParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一项复合检查在一次执行内的运行态：只保存只读计划、调用计数与统计完成标记。
 *
 * 行真值、样本矩阵、detectedAt 与计数器都留在各自执行上下文，跨执行只共享不可变定义。
 */
public final class CompoundCheckRuntime
{
    private final CompoundCheckPlan plan;
    private final CompoundMode mode;
    private final Map<String, PredicatePlan> predicates = new LinkedHashMap<>();
    private final Map<String, Integer> calls = new LinkedHashMap<>();
    private boolean nativeCountingCompleted;

    public CompoundCheckRuntime(CompoundCheckPlan plan, CompoundMode mode)
    {
        this.plan = plan;
        this.mode = mode;
        for (PredicatePlan predicate : plan.getPredicates())
        {
            predicates.put(predicate.getPredicateId(), predicate);
            calls.put(predicate.getPredicateId(), 0);
        }
    }

    public CompoundCheckPlan getPlan()
    {
        return plan;
    }

    public CompoundMode getMode()
    {
        return mode;
    }

    /** 单原子求值；diagnostic 为 true 时累计调用次数，关闭后不参与计时。 */
    public boolean holds(String predicateId, Map<String, Object> row, boolean diagnostic)
    {
        PredicatePlan predicate = predicates.get(predicateId);
        if (predicate == null) throw new ServiceException("复合检查缺少条件：" + predicateId);
        if (diagnostic) calls.merge(predicateId, 1, Integer::sum);
        return PredicateEvaluator.test(predicate, row);
    }

    /** 按选定顺序显式短路：任一条件为 false 立即停止，后续条件不再执行。 */
    public boolean evaluateWhole(Map<String, Object> row, boolean diagnostic)
    {
        for (String predicateId : plan.getSelectedOrder())
            if (!holds(predicateId, row, diagnostic)) return false;
        return true;
    }

    /** 明细内容只由固定描述决定，不随排序位置变化。 */
    public Map<String, Object> detail(Map<String, Object> row)
    {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", plan.getRuleName());
        detail.put("field", plan.getField());
        detail.put("rowId", row == null ? null : row.get(RuleParser.ROW_ID_KEY));
        detail.put("checkId", plan.getCheckId());
        detail.put("templateCode", plan.getTemplateCode());
        detail.put("dimensionCode", plan.getDimension());
        detail.put("resultType", "VIOLATION");
        detail.put("reason", plan.getReason());
        detail.put("comparisonValue", plan.getComparisonValue());
        Map<String, Object> involved = new LinkedHashMap<>();
        for (String field : plan.getInvolvedFields()) involved.put(field, row == null ? null : row.get(field));
        detail.put("involvedFields", involved);
        return detail;
    }

    public Map<String, Integer> getCalls()
    {
        return new LinkedHashMap<>(calls);
    }

    public Set<String> getPredicateIds()
    {
        return new LinkedHashSet<>(predicates.keySet());
    }

    /** 由执行上下文在原生组运行结束后调用；只能完成一次。 */
    public void completeNativeCounting()
    {
        if (nativeCountingCompleted) throw new ServiceException("原生统计只能完成一次：" + plan.getCheckId());
        nativeCountingCompleted = true;
    }

    public boolean isNativeCountingCompleted()
    {
        return nativeCountingCompleted;
    }

    @Override
    public String toString()
    {
        return plan.getCheckId() + "|" + mode + "|sorted=" + plan.isSorted() + "|calls=" + calls;
    }
}
