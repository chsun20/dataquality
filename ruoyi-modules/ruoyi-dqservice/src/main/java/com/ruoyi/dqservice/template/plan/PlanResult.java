package com.ruoyi.dqservice.template.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次排序的完整结论：原序、候选序、最终序、样本规模、成本来源与选择/回退原因。
 *
 * 无论成功与否都给出最终顺序，调用方不需要判断 null；回退只回退到原始合法顺序，
 * 非法规则定义则由校验阶段直接报错，不静默降级。
 */
public final class PlanResult
{
    public enum Reason
    {
        SORTED("留出集验证通过，采用候选顺序"),
        EMPTY_DATA("没有可采样的数据，保留原始顺序"),
        TOO_FEW_PREDICATES("条件少于 2 项，保留原始顺序"),
        TOO_FEW_SAMPLES("有效样本不足，保留原始顺序"),
        COST_MISSING("缺少条件成本，保留原始顺序"),
        NO_CHANGE("候选顺序与原始顺序一致"),
        INSUFFICIENT_GAIN("预测下降未达门槛，保留原始顺序"),
        NO_VALIDATION("留出集为空，保留原始顺序");

        private final String label;

        Reason(String label)
        {
            this.label = label;
        }

        public String getLabel()
        {
            return label;
        }
    }

    private final List<String> originalOrder;
    private final List<String> candidateOrder;
    private final List<String> finalOrder;
    private final int planningSamples;
    private final int validationSamples;
    private final String costSource;
    private final double estimatedOriginalCost;
    private final double estimatedCandidateCost;
    private final double predictedGain;
    private final Reason reason;
    private final List<PredicateCostProfile> profiles;

    PlanResult(List<String> originalOrder, List<String> candidateOrder, List<String> finalOrder, int planningSamples,
            int validationSamples, String costSource, double estimatedOriginalCost, double estimatedCandidateCost,
            double predictedGain, Reason reason, List<PredicateCostProfile> profiles)
    {
        this.originalOrder = Collections.unmodifiableList(new ArrayList<>(originalOrder));
        this.candidateOrder = Collections.unmodifiableList(new ArrayList<>(candidateOrder));
        this.finalOrder = Collections.unmodifiableList(new ArrayList<>(finalOrder));
        this.planningSamples = planningSamples;
        this.validationSamples = validationSamples;
        this.costSource = costSource;
        this.estimatedOriginalCost = estimatedOriginalCost;
        this.estimatedCandidateCost = estimatedCandidateCost;
        this.predictedGain = predictedGain;
        this.reason = reason;
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
    }

    public List<String> getOriginalOrder()
    {
        return originalOrder;
    }

    public List<String> getCandidateOrder()
    {
        return candidateOrder;
    }

    public List<String> getFinalOrder()
    {
        return finalOrder;
    }

    public int getPlanningSamples()
    {
        return planningSamples;
    }

    public int getValidationSamples()
    {
        return validationSamples;
    }

    public int getSampleSize()
    {
        return planningSamples + validationSamples;
    }

    public String getCostSource()
    {
        return costSource;
    }

    /** 留出集上的预测代价（各谓词实际到达次数 × 成本），单位与成本表一致。 */
    public double getEstimatedOriginalCost()
    {
        return estimatedOriginalCost;
    }

    public double getEstimatedCandidateCost()
    {
        return estimatedCandidateCost;
    }

    /** 预测下降比例；未进入验证时为 NaN。 */
    public double getPredictedGain()
    {
        return predictedGain;
    }

    public Reason getReason()
    {
        return reason;
    }

    public String getReasonLabel()
    {
        return reason.getLabel();
    }

    public List<PredicateCostProfile> getProfiles()
    {
        return profiles;
    }

    public boolean isSorted()
    {
        return !finalOrder.equals(originalOrder);
    }

    /** 把最终顺序写回计划；计划指纹与 DRL 随之改变。 */
    public CompoundCheckPlan applyTo(CompoundCheckPlan plan)
    {
        return plan.withOrder(finalOrder);
    }

    public String describe()
    {
        return "reason=" + reason + "|sorted=" + isSorted()
                + "|planning=" + planningSamples + "|validation=" + validationSamples
                + "|costSource=" + costSource
                + "|costOriginal=" + estimatedOriginalCost + "|costCandidate=" + estimatedCandidateCost
                + "|gain=" + (Double.isNaN(predictedGain) ? "NA" : predictedGain)
                + "|original=" + originalOrder + "|candidate=" + candidateOrder + "|final=" + finalOrder;
    }
}
