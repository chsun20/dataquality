package com.ruoyi.dqservice.template.plan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 依赖感知的代价排序。
 *
 * 在规划集上按 score = 整块成本 / 拒绝率 贪心选序：拒绝率用「当前前缀全部为 true 的到达样本」
 * 上的条件拒绝比例计算，整块成本包含该取值条件尚未入序的保护依赖。保护条件对任何行都不拒绝，
 * 单独前置只会让它们在全量行上各跑一次，因此不参与打分，而是随后续取值条件成块展开；
 * q=0 的取值条件过滤不掉任何行，得正无穷放到最后。
 * 同分按 originalPosition 保持稳定；到达样本不足时按原始合法顺序追加，最后由不参与选序的
 * 留出验证集判断是否真的下降。
 */
public final class PredicatePlanner
{
    private static final double EPSILON = 1e-9;

    public PlanResult plan(CompoundCheckPlan plan, List<Map<String, Object>> rows, PlanOptions options)
    {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(options, "options");
        List<String> original = plan.originalOrder();
        if (rows == null || rows.isEmpty())
            return fallback(plan, original, options, PlanResult.Reason.EMPTY_DATA, 0, 0,
                    Collections.<PredicateCostProfile>emptyList());
        if (original.size() < 2)
            return fallback(plan, original, options, PlanResult.Reason.TOO_FEW_PREDICATES, 0, 0,
                    Collections.<PredicateCostProfile>emptyList());
        PredicateSampler.Sample sample = PredicateSampler.sample(rows, options.getSampleLimit(), options.getSeed());
        int planning = sample.getPlanning().size();
        int validation = sample.getValidation().size();
        if (planning < options.getMinimumSamples())
            return fallback(plan, original, options, PlanResult.Reason.TOO_FEW_SAMPLES, planning, validation,
                    Collections.<PredicateCostProfile>emptyList());
        for (String id : original)
            if (options.cost(id) == null)
                return fallback(plan, original, options, PlanResult.Reason.COST_MISSING, planning, validation,
                        missingCostProfiles(plan, options));
        PredicateMatrix matrix = PredicateSampler.matrix(plan, sample.getPlanning());
        List<String> candidate = greedy(plan, matrix, options);
        List<PredicateCostProfile> profiles = profiles(plan, matrix, candidate, options);
        if (candidate.equals(original))
            return new PlanResult(original, candidate, original, planning, validation, options.getCostSource(),
                    Double.NaN, Double.NaN, Double.NaN, PlanResult.Reason.NO_CHANGE, profiles);
        if (validation == 0)
            return new PlanResult(original, candidate, original, planning, validation, options.getCostSource(),
                    Double.NaN, Double.NaN, Double.NaN, PlanResult.Reason.NO_VALIDATION, profiles);
        PredicateMatrix holdout = PredicateSampler.matrix(plan, sample.getValidation());
        double originalCost = estimate(holdout, original, options);
        double candidateCost = estimate(holdout, candidate, options);
        double gain = originalCost <= 0 ? 0 : (originalCost - candidateCost) / originalCost;
        if (gain + EPSILON < options.getMinimumGain())
            return new PlanResult(original, candidate, original, planning, validation, options.getCostSource(),
                    originalCost, candidateCost, gain, PlanResult.Reason.INSUFFICIENT_GAIN, profiles);
        return new PlanResult(original, candidate, candidate, planning, validation, options.getCostSource(),
                originalCost, candidateCost, gain, PlanResult.Reason.SORTED, profiles);
    }

    /**
     * 每一步对尚未入序的取值条件打分，选 score 最小者；同分保留原始位置更靠前的条件。
     * 选中后把它尚未入序的保护依赖按拓扑序插到它前面，形成「保护 + 取值」的成块展开。
     */
    private List<String> greedy(CompoundCheckPlan plan, PredicateMatrix matrix, PlanOptions options)
    {
        List<String> original = plan.originalOrder();
        List<String> order = new ArrayList<>(original.size());
        Set<String> chosen = new HashSet<>();
        boolean[] arrival = new boolean[matrix.getSamples()];
        Arrays.fill(arrival, true);
        while (order.size() < original.size())
        {
            if (count(arrival) < options.getMinimumArrival())
            {
                appendRemaining(original, order, chosen);
                break;
            }
            String best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (String id : original)
            {
                // 保护条件不参与打分：它们不拒绝任何行，提前只会放大自己的执行次数。
                if (chosen.contains(id) || plan.predicate(id).getKind().isGuard()) continue;
                double score = score(plan, matrix, arrival, id, chosen, options);
                // 首次候选无条件接管：全部为正无穷时按原始位置稳定推进，而不是直接退化为原序。
                if (best == null || score < bestScore)
                {
                    best = id;
                    bestScore = score;
                }
            }
            if (best == null)
            {
                appendRemaining(original, order, chosen);
                break;
            }
            appendDependencies(plan, best, order, chosen);
            order.add(best);
            chosen.add(best);
            for (int sample = 0; sample < arrival.length; sample++)
                if (arrival[sample] && matrix.status(best, sample) != PredicateMatrix.TRUE) arrival[sample] = false;
        }
        return order;
    }

    /** score = 整块成本 / 到达且适用行上的拒绝率；整块跑不到任何行或拒绝率为 0 时得正无穷。 */
    private double score(CompoundCheckPlan plan, PredicateMatrix matrix, boolean[] arrival, String predicateId,
            Set<String> chosen, PlanOptions options)
    {
        int applicable = 0;
        int rejected = 0;
        for (int sample = 0; sample < arrival.length; sample++)
        {
            if (!arrival[sample] || !matrix.applicable(predicateId, sample)) continue;
            applicable++;
            if (matrix.status(predicateId, sample) == PredicateMatrix.FALSE) rejected++;
        }
        if (applicable == 0 || rejected == 0) return Double.POSITIVE_INFINITY;
        return blockCost(plan, predicateId, chosen, options) * applicable / rejected;
    }

    /** 取值条件连同它尚未入序的保护依赖一次性执行的总成本。 */
    private double blockCost(CompoundCheckPlan plan, String predicateId, Set<String> chosen, PlanOptions options)
    {
        double total = options.cost(predicateId);
        for (String dependency : plan.predicate(predicateId).getDependsOn())
            if (!chosen.contains(dependency)) total += blockCost(plan, dependency, chosen, options);
        return total;
    }

    /** 把取值条件的保护依赖链按拓扑序插到它前面，已入序的依赖不重复追加。 */
    private void appendDependencies(CompoundCheckPlan plan, String predicateId, List<String> order, Set<String> chosen)
    {
        for (String dependency : plan.predicate(predicateId).getDependsOn())
        {
            if (!chosen.add(dependency)) continue;
            appendDependencies(plan, dependency, order, chosen);
            order.add(dependency);
        }
    }

    private void appendRemaining(List<String> original, List<String> order, Set<String> chosen)
    {
        for (String id : original)
            if (chosen.add(id)) order.add(id);
    }

    private int count(boolean[] values)
    {
        int total = 0;
        for (boolean value : values) if (value) total++;
        return total;
    }

    /** 在给定顺序下估算代价：只有到达的条件才计成本，且包含那条使整项失败的条件。 */
    private double estimate(PredicateMatrix matrix, List<String> order, PlanOptions options)
    {
        double total = 0;
        for (int sample = 0; sample < matrix.getSamples(); sample++)
        {
            for (String id : order)
            {
                if (!matrix.applicable(id, sample)) break;
                Double cost = options.cost(id);
                if (cost != null) total += cost;
                if (matrix.status(id, sample) != PredicateMatrix.TRUE) break;
            }
        }
        return total;
    }

    /** 逐条件记录成本、适用范围与拒绝率，供诊断和实验产物使用，不进入计划语义。 */
    private List<PredicateCostProfile> profiles(CompoundCheckPlan plan, PredicateMatrix matrix, List<String> order,
            PlanOptions options)
    {
        List<PredicateCostProfile> profiles = new ArrayList<>(order.size());
        boolean[] arrival = new boolean[matrix.getSamples()];
        Arrays.fill(arrival, true);
        for (int position = 0; position < order.size(); position++)
        {
            String id = order.get(position);
            int applicable = 0;
            int rejected = 0;
            for (int sample = 0; sample < arrival.length; sample++)
            {
                if (!arrival[sample]) continue;
                applicable++;
                if (matrix.status(id, sample) == PredicateMatrix.FALSE) rejected++;
            }
            Double cost = options.cost(id);
            profiles.add(PredicateCostProfile.of(id, cost == null ? -1 : cost, options.getCostSource(), applicable,
                    rejected, position, cost == null ? "成本缺失" : "到达样本上的拒绝率"));
            for (int sample = 0; sample < arrival.length; sample++)
                if (arrival[sample] && matrix.status(id, sample) != PredicateMatrix.TRUE) arrival[sample] = false;
        }
        return profiles;
    }

    private List<PredicateCostProfile> missingCostProfiles(CompoundCheckPlan plan, PlanOptions options)
    {
        List<PredicateCostProfile> profiles = new ArrayList<>(plan.getPredicates().size());
        for (PredicatePlan predicate : plan.getPredicates())
        {
            Double cost = options.cost(predicate.getPredicateId());
            profiles.add(PredicateCostProfile.of(predicate.getPredicateId(), cost == null ? -1 : cost,
                    options.getCostSource(), 0, 0, -1, cost == null ? "成本缺失" : "已提供成本"));
        }
        return profiles;
    }

    private PlanResult fallback(CompoundCheckPlan plan, List<String> order, PlanOptions options,
            PlanResult.Reason reason, int planning, int validation, List<PredicateCostProfile> profiles)
    {
        return new PlanResult(order, order, order, planning, validation, options.getCostSource(),
                Double.NaN, Double.NaN, Double.NaN, reason, profiles);
    }
}
