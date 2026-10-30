package com.ruoyi.dqservice.template.plan;

import com.ruoyi.common.core.exception.ServiceException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 排序参数与可注入成本表，普通 Java 对象，不注册业务配置开关。
 *
 * 回归测试必须传入固定成本，保证 score 可复算、不依赖机器速度；
 * 标定成本只能由显式基准入口传入。
 */
public final class PlanOptions
{
    public static final int DEFAULT_SAMPLE_LIMIT = 2048;

    public static final long DEFAULT_SEED = 20261030L;

    public static final int DEFAULT_MINIMUM_SAMPLES = 128;

    /** 到达样本低于该值时，剩余条件按原始合法顺序追加。 */
    public static final int DEFAULT_MINIMUM_ARRIVAL = 32;

    /** 原型保守门槛，不是实测启用阈值，也不代表抵扣规划成本后的净收益。 */
    public static final double DEFAULT_MINIMUM_GAIN = 0.05;

    public static final String FIXED_COST_SOURCE = "fixed-table";

    private final int sampleLimit;
    private final long seed;
    private final int minimumSamples;
    private final int minimumArrival;
    private final double minimumGain;
    private final Map<String, Double> costs;
    private final String costSource;

    private PlanOptions(Builder builder)
    {
        this.sampleLimit = builder.sampleLimit;
        this.seed = builder.seed;
        this.minimumSamples = builder.minimumSamples;
        this.minimumArrival = builder.minimumArrival;
        this.minimumGain = builder.minimumGain;
        this.costs = Collections.unmodifiableMap(new LinkedHashMap<>(builder.costs));
        this.costSource = builder.costSource;
        validate();
    }

    private void validate()
    {
        if (sampleLimit < 1) throw new ServiceException("采样上限必须为正数");
        if (minimumSamples < 2) throw new ServiceException("最小样本数至少为 2");
        if (minimumSamples > sampleLimit) throw new ServiceException("最小样本数不能超过采样上限");
        if (minimumArrival < 1) throw new ServiceException("最小到达样本数必须为正数");
        if (!(minimumGain >= 0 && minimumGain <= 1)) throw new ServiceException("预测下降门槛必须在 0～1 之间");
        for (Map.Entry<String, Double> cost : costs.entrySet())
            if (cost.getValue() == null || !(cost.getValue() >= 0) || Double.isInfinite(cost.getValue()))
                throw new ServiceException("条件成本必须是非负有限数值：" + cost.getKey());
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static PlanOptions defaults()
    {
        return builder().build();
    }

    public int getSampleLimit()
    {
        return sampleLimit;
    }

    public long getSeed()
    {
        return seed;
    }

    public int getMinimumSamples()
    {
        return minimumSamples;
    }

    public int getMinimumArrival()
    {
        return minimumArrival;
    }

    public double getMinimumGain()
    {
        return minimumGain;
    }

    public String getCostSource()
    {
        return costSource;
    }

    /** 未提供成本时返回 null，调用方必须回退原序，不能杜撰成本。 */
    public Double cost(String predicateId)
    {
        return costs.get(predicateId);
    }

    public Map<String, Double> getCosts()
    {
        return costs;
    }

    public static final class Builder
    {
        private int sampleLimit = DEFAULT_SAMPLE_LIMIT;
        private long seed = DEFAULT_SEED;
        private int minimumSamples = DEFAULT_MINIMUM_SAMPLES;
        private int minimumArrival = DEFAULT_MINIMUM_ARRIVAL;
        private double minimumGain = DEFAULT_MINIMUM_GAIN;
        private final Map<String, Double> costs = new LinkedHashMap<>();
        private String costSource = FIXED_COST_SOURCE;

        public Builder sampleLimit(int limit)
        {
            this.sampleLimit = limit;
            return this;
        }

        public Builder seed(long value)
        {
            this.seed = value;
            return this;
        }

        public Builder minimumSamples(int value)
        {
            this.minimumSamples = value;
            return this;
        }

        public Builder minimumArrival(int value)
        {
            this.minimumArrival = value;
            return this;
        }

        public Builder minimumGain(double value)
        {
            this.minimumGain = value;
            return this;
        }

        public Builder costSource(String value)
        {
            this.costSource = value;
            return this;
        }

        public Builder fixedCost(String predicateId, double costNs)
        {
            costs.put(predicateId, costNs);
            return this;
        }

        public Builder fixedCosts(Map<String, Double> values)
        {
            costs.putAll(values);
            return this;
        }

        public PlanOptions build()
        {
            return new PlanOptions(this);
        }
    }
}
