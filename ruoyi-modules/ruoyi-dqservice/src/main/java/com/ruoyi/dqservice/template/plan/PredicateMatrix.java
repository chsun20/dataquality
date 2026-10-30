package com.ruoyi.dqservice.template.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三值真值矩阵：TRUE / FALSE / NOT_APPLICABLE。
 *
 * 依照原始合法拓扑序构造，仅当保护依赖全部为 TRUE 时才评价依赖谓词；
 * NOT_APPLICABLE 不能当 false 混入拒绝率。
 */
public final class PredicateMatrix
{
    public static final byte FALSE = 0;

    public static final byte TRUE = 1;

    public static final byte NOT_APPLICABLE = 2;

    private final List<PredicatePlan> predicates;
    private final Map<String, Integer> positions;
    private final int samples;
    private final byte[] status;

    PredicateMatrix(List<PredicatePlan> predicates, int samples)
    {
        this.predicates = new ArrayList<>(predicates);
        this.samples = samples;
        this.positions = new HashMap<>();
        for (int i = 0; i < predicates.size(); i++) positions.put(predicates.get(i).getPredicateId(), i);
        this.status = new byte[predicates.size() * samples];
    }

    void set(int position, int sample, byte value)
    {
        status[position * samples + sample] = value;
    }

    public int getSamples()
    {
        return samples;
    }

    public List<PredicatePlan> getPredicates()
    {
        return predicates;
    }

    public byte status(String predicateId, int sample)
    {
        Integer position = positions.get(predicateId);
        if (position == null) throw new IllegalStateException("矩阵缺少条件：" + predicateId);
        return status[position * samples + sample];
    }

    public boolean holds(String predicateId, int sample)
    {
        return status(predicateId, sample) == TRUE;
    }

    public boolean applicable(String predicateId, int sample)
    {
        return status(predicateId, sample) != NOT_APPLICABLE;
    }
}
