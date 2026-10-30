package com.ruoyi.dqservice.l4;

import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.PredicateEvaluator;
import com.ruoyi.dqservice.template.plan.PredicatePlan;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量标定适配器（测试目录）。
 *
 * 以同类有效输入批量求值：预热 3 批、测量 5 批，每批重复遍历有效样本 16 次，
 * 消费返回值并记录中位单次成本。无有效样本或成本无效时不返回成本，
 * 由规划器回退原序，而不是杜撰一个数字。该适配器不引入 JMH 依赖，只在显式基准中调用。
 */
public final class PredicateCostCalibrator
{
    private static final int WARMUP_BATCHES = 3;

    private static final int MEASURED_BATCHES = 5;

    private static final int TRAVERSALS = 16;

    private static volatile long sink;

    /** 返回 predicateId -> 单次求值成本（纳秒）；缺少成本的谓词不会出现在结果里。 */
    public Map<String, Double> calibrate(CompoundCheckPlan plan, List<Map<String, Object>> rows)
    {
        Map<String, Double> costs = new LinkedHashMap<>();
        for (PredicatePlan predicate : plan.getPredicates())
        {
            List<Map<String, Object>> inputs = applicable(plan, predicate, rows);
            if (inputs.size() < 2) continue;
            double median = measure(predicate, inputs);
            if (!(median > 0) || Double.isInfinite(median)) continue;
            costs.put(predicate.getPredicateId(), median);
        }
        return costs;
    }

    /** 有效输入：该谓词的全部保护依赖为 true，此时单原子求值不会落空值或类型分支。 */
    private List<Map<String, Object>> applicable(CompoundCheckPlan plan, PredicatePlan predicate,
            List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            boolean applicable = true;
            for (String dependency : predicate.getDependsOn())
                if (!PredicateEvaluator.test(plan.predicate(dependency), row))
                {
                    applicable = false;
                    break;
                }
            if (applicable) inputs.add(row);
        }
        return inputs;
    }

    private double measure(PredicatePlan predicate, List<Map<String, Object>> inputs)
    {
        for (int batch = 0; batch < WARMUP_BATCHES; batch++) traverse(predicate, inputs);
        long[] samples = new long[MEASURED_BATCHES];
        for (int batch = 0; batch < MEASURED_BATCHES; batch++)
        {
            long started = System.nanoTime();
            traverse(predicate, inputs);
            samples[batch] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        double medianNanos = samples[samples.length / 2];
        return medianNanos / (double) TRAVERSALS / inputs.size();
    }

    private void traverse(PredicatePlan predicate, List<Map<String, Object>> inputs)
    {
        long hits = 0;
        for (int pass = 0; pass < TRAVERSALS; pass++)
            for (Map<String, Object> row : inputs)
                if (PredicateEvaluator.test(predicate, row)) hits++;
        sink += hits;
    }
}
