package com.ruoyi.dqservice.template.plan;

/**
 * 单个条件的成本画像。
 *
 * 与语义描述分开保存：成本来自固定成本表或显式标定，样本数、拒绝率与选择理由属于测量结果，
 * 不混入 {@link PredicatePlan}，也不写入指纹。
 */
public final class PredicateCostProfile
{
    private final String predicateId;
    private final double costNs;
    private final String costSource;
    private final int applicableSamples;
    private final int rejectionSamples;
    private final double rejectionRate;
    private final int selectedPosition;
    private final String note;

    private PredicateCostProfile(String predicateId, double costNs, String costSource, int applicableSamples,
            int rejectionSamples, double rejectionRate, int selectedPosition, String note)
    {
        this.predicateId = predicateId;
        this.costNs = costNs;
        this.costSource = costSource;
        this.applicableSamples = applicableSamples;
        this.rejectionSamples = rejectionSamples;
        this.rejectionRate = rejectionRate;
        this.selectedPosition = selectedPosition;
        this.note = note;
    }

    public static PredicateCostProfile of(String predicateId, double costNs, String costSource, int applicableSamples,
            int rejectionSamples, int selectedPosition, String note)
    {
        double rate = applicableSamples <= 0 ? Double.NaN : (double) rejectionSamples / applicableSamples;
        return new PredicateCostProfile(predicateId, costNs, costSource, applicableSamples, rejectionSamples, rate,
                selectedPosition, note);
    }

    public String getPredicateId()
    {
        return predicateId;
    }

    /** 单次求值成本，单位纳秒；只用于排序权重，不作为对外性能结论。 */
    public double getCostNs()
    {
        return costNs;
    }

    public String getCostSource()
    {
        return costSource;
    }

    public int getApplicableSamples()
    {
        return applicableSamples;
    }

    public int getRejectionSamples()
    {
        return rejectionSamples;
    }

    /** 拒绝率 q；没有适用样本时为 NaN，不能当 0 使用。 */
    public double getRejectionRate()
    {
        return rejectionRate;
    }

    public int getSelectedPosition()
    {
        return selectedPosition;
    }

    public String getNote()
    {
        return note;
    }

    public String describe()
    {
        return predicateId + "|costNs=" + costNs + "|source=" + costSource + "|applicable=" + applicableSamples
                + "|rejected=" + rejectionSamples + "|q=" + (Double.isNaN(rejectionRate) ? "NA" : rejectionRate)
                + "|position=" + selectedPosition + "|note=" + note;
    }
}
