package com.ruoyi.dqservice.template.plan;

import com.ruoyi.common.core.exception.ServiceException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 固定种子无放回抽样与三值矩阵构造。
 *
 * 采样与标定使用独立状态，不调用正式的 evaluateCompound，不增加业务计数、不产生明细，
 * 也不修改输入行；返回的样本只持有原始行的引用，调用方不得写入。
 */
public final class PredicateSampler
{
    /** 规划集占比：其余为不参与选序的留出验证集。 */
    private static final int PLANNING_PARTS = 3;

    private PredicateSampler()
    {
    }

    public static Sample sample(List<Map<String, Object>> rows, int limit, long seed)
    {
        if (rows == null) throw new ServiceException("采样数据不能为空");
        if (limit < 1) throw new ServiceException("采样上限必须为正数");
        int total = Math.min(limit, rows.size());
        int[] order = new int[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Random random = new Random(seed);
        for (int i = order.length - 1; i > 0; i--)
        {
            int swap = random.nextInt(i + 1);
            int value = order[i];
            order[i] = order[swap];
            order[swap] = value;
        }
        List<Map<String, Object>> planning = new ArrayList<>(total);
        List<Map<String, Object>> validation = new ArrayList<>(Math.max(0, total / 4));
        int planningCount = total == 0 ? 0 : Math.max(1, total * PLANNING_PARTS / 4);
        if (planningCount > total) planningCount = total;
        for (int i = 0; i < total; i++)
        {
            Map<String, Object> row = rows.get(order[i]);
            if (i < planningCount) planning.add(row);
            else validation.add(row);
        }
        return new Sample(planning, validation);
    }

    /** 按原始合法拓扑序构造三值矩阵；只有保护依赖全部为 TRUE 才评价依赖谓词。 */
    public static PredicateMatrix matrix(CompoundCheckPlan plan, List<Map<String, Object>> rows)
    {
        List<PredicatePlan> predicates = plan.getPredicates();
        PredicateMatrix matrix = new PredicateMatrix(predicates, rows.size());
        for (int sample = 0; sample < rows.size(); sample++)
        {
            Map<String, Object> row = rows.get(sample);
            for (int position = 0; position < predicates.size(); position++)
            {
                PredicatePlan predicate = predicates.get(position);
                boolean applicable = true;
                for (String dependency : predicate.getDependsOn())
                    if (!matrix.holds(dependency, sample))
                    {
                        applicable = false;
                        break;
                    }
                byte value = applicable
                        ? (PredicateEvaluator.test(predicate, row) ? PredicateMatrix.TRUE : PredicateMatrix.FALSE)
                        : PredicateMatrix.NOT_APPLICABLE;
                matrix.set(position, sample, value);
            }
        }
        return matrix;
    }

    /** 固定随机顺序划分后的样本；规划集用于选序，验证集不参与选序。 */
    public static final class Sample
    {
        private final List<Map<String, Object>> planning;
        private final List<Map<String, Object>> validation;

        private Sample(List<Map<String, Object>> planning, List<Map<String, Object>> validation)
        {
            this.planning = Collections.unmodifiableList(planning);
            this.validation = Collections.unmodifiableList(validation);
        }

        public List<Map<String, Object>> getPlanning()
        {
            return planning;
        }

        public List<Map<String, Object>> getValidation()
        {
            return validation;
        }

        public int size()
        {
            return planning.size() + validation.size();
        }

        public String describe()
        {
            return "sample=" + size() + "|planning=" + planning.size() + "|validation=" + validation.size();
        }

        @Override
        public String toString()
        {
            return describe();
        }
    }
}
