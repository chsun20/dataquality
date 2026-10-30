package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.l4.CompoundFixtures;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.PlanOptions;
import com.ruoyi.dqservice.template.plan.PlanResult;
import com.ruoyi.dqservice.template.plan.PredicateCostProfile;
import com.ruoyi.dqservice.template.plan.PredicateEvaluator;
import com.ruoyi.dqservice.template.plan.PredicateKind;
import com.ruoyi.dqservice.template.plan.PredicatePlan;
import com.ruoyi.dqservice.template.plan.PredicatePlanner;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4 排序与计划契约。
 *
 * 覆盖计划原文要求的三组内容：
 * 1. 排序：固定成本可复算、留出集否决、原序回退、稀疏样本、同分稳定、m≤6 枚举合法序；
 * 2. 保护：循环/缺失依赖、重复标识、非法常量在创建时失败；
 * 3. 谓词：缺失字段/NULL/类型不符/非有限数值不满足保护，文本区分大小写且不 trim，数值走 BigDecimal。
 */
class QualityL4PlanTest
{
    private final PredicatePlanner planner = new PredicatePlanner();

    @Test
    void cheapSelectiveConditionIsPromotedAheadOfExpensiveRegex()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(2000);
        PlanResult result = planner.plan(plan, rows, CompoundFixtures.options(plan));

        assertEquals(PlanResult.Reason.SORTED, result.getReason());
        assertTrue(result.isSorted());
        // 按「保护 + 取值」成块展开：最便宜且拒绝率最高的类别块在前，正则块在最后。
        assertEquals(Arrays.asList(
                "category#NOT_NULL", "category#IS_TEXT", "category#TEXT_EQ",
                "status#NOT_NULL", "status#IS_TEXT", "status#TEXT_EQ",
                "code#NOT_NULL", "code#IS_TEXT", "code#REGEX_NOT_MATCH"), result.getFinalOrder());
        assertEquals(1500, result.getPlanningSamples());
        assertEquals(500, result.getValidationSamples());
        assertEquals(9, result.getProfiles().size());
        // 留出集（不参与选序）上代价必须真实下降，而不是只在规划集上好看。
        assertTrue(result.getPredictedGain() > 0.5, result.describe());
        assertTrue(result.getEstimatedCandidateCost() < result.getEstimatedOriginalCost() * 0.2,
                result.describe());
    }

    @Test
    void emptyInputKeepsOriginalOrder()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        PlanResult result = planner.plan(plan, Collections.<Map<String, Object>>emptyList(),
                CompoundFixtures.options(plan));

        assertEquals(PlanResult.Reason.EMPTY_DATA, result.getReason());
        assertEquals(plan.originalOrder(), result.getFinalOrder());
        assertFalse(result.isSorted());
        assertTrue(Double.isNaN(result.getPredictedGain()));
        assertEquals(0, result.getSampleSize());
    }

    @Test
    void singlePredicateKeepsOriginalOrder()
    {
        CompoundCheckPlan plan = CompoundCheckPlan
                .builder("l4-single", "单项检查", "code", QualityDimension.VALIDITY.name())
                .guard(PredicateKind.NOT_NULL)
                .build();
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(600), CompoundFixtures.options(plan));

        assertEquals(PlanResult.Reason.TOO_FEW_PREDICATES, result.getReason());
        assertEquals(plan.originalOrder(), result.getFinalOrder());
    }

    @Test
    void sparseSampleKeepsOriginalOrder()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        // 120 行按 3:1 划分后规划集只有 90 行，低于 128 的最小样本。
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(120), CompoundFixtures.options(plan));

        assertEquals(PlanResult.Reason.TOO_FEW_SAMPLES, result.getReason());
        assertEquals(plan.originalOrder(), result.getFinalOrder());
        assertTrue(result.getProfiles().isEmpty());
    }

    @Test
    void missingCostKeepsOriginalOrderWithoutInventingNumbers()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(2000), PlanOptions.builder().build());

        assertEquals(PlanResult.Reason.COST_MISSING, result.getReason());
        assertEquals(plan.originalOrder(), result.getFinalOrder());
        assertEquals(9, result.getProfiles().size());
        for (PredicateCostProfile profile : result.getProfiles())
        {
            assertEquals(-1, profile.getCostNs());
            assertEquals("成本缺失", profile.getNote());
        }
    }

    @Test
    void guardChainHasNoReorderingSpace()
    {
        CompoundCheckPlan plan = CompoundFixtures.guardChainPlan();
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(2000), CompoundFixtures.options(plan));

        assertEquals(PlanResult.Reason.NO_CHANGE, result.getReason());
        assertEquals(plan.originalOrder(), result.getCandidateOrder());
        assertFalse(result.isSorted());
    }

    @Test
    void holdoutGateRejectsGainBelowThreshold()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        // 门槛设到 100%，即使预测下降很大也回退到原序，候选序仍留存供诊断。
        PlanOptions options = CompoundFixtures.fixedOptions(plan).minimumGain(1.0).build();
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(2000), options);

        assertEquals(PlanResult.Reason.INSUFFICIENT_GAIN, result.getReason());
        assertNotEquals(plan.originalOrder(), result.getCandidateOrder());
        assertEquals(plan.originalOrder(), result.getFinalOrder());
        assertTrue(result.getPredictedGain() > 0.5, result.describe());
        assertTrue(result.getPredictedGain() < 1.0, result.describe());
    }

    @Test
    void equalScoreKeepsOriginalPosition()
    {
        // 同一字段上的两个同义条件在任何样本上拒绝率完全相同，score 必然同分。
        CompoundCheckPlan plan = CompoundCheckPlan
                .builder("l4-tie", "同分稳定性", "category", QualityDimension.VALIDITY.name())
                .guard(PredicateKind.NOT_NULL, "category")
                .guard(PredicateKind.IS_TEXT, "category")
                .predicate(PredicateKind.TEXT_EQ, "category", "AUDIT")
                .predicate(PredicateKind.TEXT_EQ, "category", "AUDIT")
                .build();
        PlanResult result = planner.plan(plan, CompoundFixtures.codeRows(1000), CompoundFixtures.options(plan));

        assertEquals(plan.originalOrder(), result.getCandidateOrder());
        assertEquals(4, result.getProfiles().size());
        PredicateCostProfile first = result.getProfiles().get(2);
        PredicateCostProfile second = result.getProfiles().get(3);
        assertEquals("category#TEXT_EQ", first.getPredicateId());
        assertEquals("category#TEXT_EQ#2", second.getPredicateId());
        assertEquals(20, first.getCostNs());
        assertEquals(20, second.getCostNs());
        assertEquals(2, first.getSelectedPosition());
        assertEquals(3, second.getSelectedPosition());
        // 同分时按原始位置推进：先第一个，再第二个，因此候选序与原始序一致。
        // 到达集合逐步收缩：第二个同义条件在剩余的 AUDIT 行上不再拒绝。
        assertTrue(first.getRejectionSamples() > 0);
        assertEquals(0, second.getRejectionSamples());
        assertEquals(first.getApplicableSamples() - first.getRejectionSamples(), second.getApplicableSamples());
    }

    @Test
    void selectedOrderIsAlwaysALegalTopologicalOrder()
    {
        CompoundCheckPlan plan = CompoundFixtures.amountPlan();
        PlanResult result = planner.plan(plan, CompoundFixtures.amountRows(800), CompoundFixtures.options(plan));

        List<String> original = plan.originalOrder();
        assertEquals(6, original.size());
        List<List<String>> legal = new ArrayList<>();
        for (List<String> permutation : permutations(original))
            if (accepts(plan, permutation)) legal.add(permutation);
        // 枚举结论必须与计划校验器一致：合法序少于全排列，且至少包含原序。
        assertTrue(legal.size() > 1 && legal.size() < 720, "合法序数量=" + legal.size());
        assertTrue(legal.contains(original));
        assertTrue(legal.contains(result.getFinalOrder()), result.describe());
        assertTrue(legal.contains(result.getCandidateOrder()), result.describe());
    }

    @Test
    void cycleMissingDependencyAndDuplicateIdAreRejected()
    {
        List<PredicatePlan> cycle = Arrays.asList(
                PredicatePlan.create("p#1", PredicateKind.NOT_NULL, "a", null,
                        Collections.singletonList("p#2"), 0),
                PredicatePlan.create("p#2", PredicateKind.NOT_NULL, "b", null,
                        Collections.singletonList("p#1"), 1));
        assertMessageContains(() -> define(cycle), "条件依赖成环");

        List<PredicatePlan> missing = Arrays.asList(
                PredicatePlan.create("p#1", PredicateKind.NOT_NULL, "a", null,
                        Collections.singletonList("ghost"), 0));
        assertMessageContains(() -> define(missing), "条件依赖不存在");

        List<PredicatePlan> duplicate = Arrays.asList(
                PredicatePlan.create("p#1", PredicateKind.NOT_NULL, "a", null,
                        Collections.<String>emptyList(), 0),
                PredicatePlan.create("p#1", PredicateKind.NOT_NULL, "b", null,
                        Collections.<String>emptyList(), 1));
        assertMessageContains(() -> define(duplicate), "条件标识重复");
    }

    @Test
    void illegalOrderIsRejected()
    {
        List<PredicatePlan> predicates = Arrays.asList(
                PredicatePlan.create("a#NOT_NULL", PredicateKind.NOT_NULL, "a", null,
                        Collections.<String>emptyList(), 0),
                PredicatePlan.create("a#IS_TEXT", PredicateKind.IS_TEXT, "a", null,
                        Collections.singletonList("a#NOT_NULL"), 1),
                PredicatePlan.create("a#TEXT_EQ", PredicateKind.TEXT_EQ, "a", "X",
                        Arrays.asList("a#NOT_NULL", "a#IS_TEXT"), 2));

        assertMessageContains(() -> CompoundCheckPlan.define("l4-order", "非法顺序", "a",
                QualityDimension.VALIDITY.name(), CompoundCheckPlan.DEFAULT_TEMPLATE_CODE, "原因", null,
                predicates, Arrays.asList("a#NOT_NULL", "a#IS_TEXT")), "执行顺序与条件集合不一致");
        assertMessageContains(() -> CompoundCheckPlan.define("l4-order", "非法顺序", "a",
                QualityDimension.VALIDITY.name(), CompoundCheckPlan.DEFAULT_TEMPLATE_CODE, "原因", null,
                predicates, Arrays.asList("a#TEXT_EQ", "a#IS_TEXT", "a#NOT_NULL")), "执行顺序违反依赖");
        assertMessageContains(() -> CompoundCheckPlan.define("l4-order", "非法顺序", "a",
                QualityDimension.VALIDITY.name(), CompoundCheckPlan.DEFAULT_TEMPLATE_CODE, "原因", null,
                predicates, Arrays.asList("a#NOT_NULL", "a#IS_TEXT", "a#TEXT_EQ", "a#TEXT_EQ")),
                "执行顺序存在重复条件");
    }

    @Test
    void missingOrUndeclaredGuardIsRejected()
    {
        List<PredicatePlan> noGuard = Arrays.asList(
                PredicatePlan.create("a#TEXT_EQ", PredicateKind.TEXT_EQ, "a", "X",
                        Collections.<String>emptyList(), 0));
        assertMessageContains(() -> define(noGuard), "条件缺少保护条件");

        List<PredicatePlan> undeclared = Arrays.asList(
                PredicatePlan.create("a#NOT_NULL", PredicateKind.NOT_NULL, "a", null,
                        Collections.<String>emptyList(), 0),
                PredicatePlan.create("a#IS_TEXT", PredicateKind.IS_TEXT, "a", null,
                        Collections.singletonList("a#NOT_NULL"), 1),
                PredicatePlan.create("a#TEXT_EQ", PredicateKind.TEXT_EQ, "a", "X",
                        Collections.singletonList("a#NOT_NULL"), 2));
        assertMessageContains(() -> define(undeclared), "条件未声明保护依赖");
    }

    @Test
    void predicateCountAndConstantsAreCheckedAtCreation()
    {
        CompoundCheckPlan.Builder tooMany = CompoundCheckPlan
                .builder("l4-many", "条件过多", "f0", QualityDimension.VALIDITY.name());
        for (int i = 0; i <= CompoundCheckPlan.MAX_PREDICATES; i++) tooMany.guard(PredicateKind.NOT_NULL, "f" + i);
        assertMessageContains(tooMany::build, "最多支持 16 个条件");

        assertMessageContains(() -> PredicatePlan.create("r", PredicateKind.REGEX_MATCH, "code", "[",
                Collections.<String>emptyList(), 0), "谓词正则不合法");
        assertMessageContains(() -> PredicatePlan.create("n", PredicateKind.NUMBER_GT, "amount", "abc",
                Collections.<String>emptyList(), 0), "谓词数值常量不合法");
        assertMessageContains(() -> PredicatePlan.create("e", PredicateKind.TEXT_IN, "category", " , , ",
                Collections.<String>emptyList(), 0), "枚举谓词缺少有效取值");
        assertMessageContains(() -> PredicatePlan.create("g", PredicateKind.NOT_NULL, "code", "x",
                Collections.<String>emptyList(), 0), "保护谓词不接受常量");
        assertMessageContains(() -> PredicatePlan.create("v", PredicateKind.TEXT_EQ, "code", null,
                Collections.<String>emptyList(), 0), "谓词缺少常量");
    }

    @Test
    void fingerprintIsStableAndChangesWithDefinition()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        assertEquals(plan.getFingerprint(), CompoundFixtures.codeFormatPlan().getFingerprint());
        assertEquals(64, plan.getFingerprint().length());

        CompoundCheckPlan sorted = plan.withOrder(Arrays.asList(
                "category#NOT_NULL", "category#IS_TEXT", "category#TEXT_EQ",
                "code#NOT_NULL", "code#IS_TEXT", "code#REGEX_NOT_MATCH",
                "status#NOT_NULL", "status#IS_TEXT", "status#TEXT_EQ"));
        assertNotEquals(plan.getFingerprint(), sorted.getFingerprint());

        CompoundCheckPlan otherLiteral = CompoundCheckPlan
                .builder(CompoundFixtures.CODE_CHECK_ID, "业务编码格式", "code", QualityDimension.VALIDITY.name())
                .guard(PredicateKind.NOT_NULL, "category")
                .guard(PredicateKind.IS_TEXT, "category")
                .guard(PredicateKind.NOT_NULL, "code")
                .guard(PredicateKind.IS_TEXT, "code")
                .notMatches(CompoundFixtures.CODE_PATTERN)
                .textEquals("category", "REVIEW")
                .textEquals("status", "PENDING")
                .build();
        assertNotEquals(plan.getFingerprint(), otherLiteral.getFingerprint());
    }

    @Test
    void predicateContractCoversNullTypeAndPrecision()
    {
        assertFalse(PredicateEvaluator.test(predicate("NOT_NULL", PredicateKind.NOT_NULL, "code", null), row()));
        assertFalse(PredicateEvaluator.test(predicate("NOT_NULL", PredicateKind.NOT_NULL, "code", null),
                row("code", null)));
        assertTrue(PredicateEvaluator.test(predicate("NOT_NULL", PredicateKind.NOT_NULL, "code", null),
                row("code", "")));

        assertFalse(PredicateEvaluator.test(predicate("IS_TEXT", PredicateKind.IS_TEXT, "code", null),
                row("code", 123)));
        assertFalse(PredicateEvaluator.test(predicate("IS_TEXT", PredicateKind.IS_TEXT, "code", null),
                row("code", null)));

        // 文本区分大小写、不 trim。
        assertFalse(PredicateEvaluator.test(predicate("EQ", PredicateKind.TEXT_EQ, "code", "AUDIT"),
                row("code", "audit")));
        assertFalse(PredicateEvaluator.test(predicate("EQ", PredicateKind.TEXT_EQ, "code", "AUDIT"),
                row("code", " AUDIT")));
        assertTrue(PredicateEvaluator.test(predicate("EQ", PredicateKind.TEXT_EQ, "code", "中文值"),
                row("code", "中文值")));

        // 正则按整串匹配，不做子串查找。
        PredicatePlan twoLetters = predicate("RE", PredicateKind.REGEX_MATCH, "code", "[A-Z]{2}");
        assertTrue(PredicateEvaluator.test(twoLetters, row("code", "AB")));
        assertFalse(PredicateEvaluator.test(twoLetters, row("code", "ABC")));

        // 数值不隐式转字符串；NaN / Infinity 不算有限数值。
        PredicatePlan over1000 = predicate("GT", PredicateKind.NUMBER_GT, "amount", "1000");
        assertFalse(PredicateEvaluator.test(over1000, row("amount", "1500")));
        assertFalse(PredicateEvaluator.test(over1000, row("amount", Double.NaN)));
        assertFalse(PredicateEvaluator.test(over1000, row("amount", Double.POSITIVE_INFINITY)));
        assertFalse(PredicateEvaluator.test(predicate("IS_NUMBER", PredicateKind.IS_NUMBER, "amount", null),
                row("amount", Double.NaN)));

        // 数值比较走 BigDecimal，避免二进制浮点误差。
        PredicatePlan equals1000 = predicate("EQ", PredicateKind.NUMBER_EQ, "amount", "1000");
        assertTrue(PredicateEvaluator.test(equals1000, row("amount", new BigDecimal("1000.00"))));
        assertTrue(PredicateEvaluator.test(equals1000, row("amount", 1000L)));
        assertTrue(PredicateEvaluator.test(over1000, row("amount", new BigDecimal("1000.0000000001"))));
        assertFalse(PredicateEvaluator.test(over1000, row("amount", new BigDecimal("1000.0"))));
        assertTrue(PredicateEvaluator.test(predicate("GE", PredicateKind.NUMBER_GE, "amount", "1000"),
                row("amount", 1000)));
        assertTrue(PredicateEvaluator.test(predicate("LE", PredicateKind.NUMBER_LE, "amount", "1000"),
                row("amount", new BigDecimal("999.999"))));
    }

    @Test
    void textInAndNotInUseExactTokens()
    {
        PredicatePlan in = predicate("IN", PredicateKind.TEXT_IN, "category", "A, B ,C");
        assertTrue(PredicateEvaluator.test(in, row("category", "B")));
        assertFalse(PredicateEvaluator.test(in, row("category", "b")));
        assertFalse(PredicateEvaluator.test(in, row("category", " B ")));
        assertFalse(PredicateEvaluator.test(in, row("category", 1)));

        PredicatePlan notIn = predicate("NIN", PredicateKind.TEXT_NOT_IN, "category", "A,B");
        assertTrue(PredicateEvaluator.test(notIn, row("category", "D")));
        assertFalse(PredicateEvaluator.test(notIn, row("category", "A")));
        assertFalse(PredicateEvaluator.test(notIn, row("category", null)));
    }

    // ------------------------------------------------------------------ 辅助

    private static PredicatePlan predicate(String id, PredicateKind kind, String field, String literal)
    {
        return PredicatePlan.create(id, kind, field, literal, Collections.<String>emptyList(), 0);
    }

    private static Map<String, Object> row(Object... pairs)
    {
        return CompoundFixtures.rowOf(pairs);
    }

    private static CompoundCheckPlan define(List<PredicatePlan> predicates)
    {
        return CompoundCheckPlan.define("l4-definition", "定义校验", "a", QualityDimension.VALIDITY.name(),
                CompoundCheckPlan.DEFAULT_TEMPLATE_CODE, "原因", null, predicates);
    }

    /** 用计划自身的校验器判断某个顺序是否合法，避免测试另写一套依赖规则。 */
    private static boolean accepts(CompoundCheckPlan plan, List<String> order)
    {
        try
        {
            plan.withOrder(order);
            return true;
        }
        catch (ServiceException e)
        {
            return false;
        }
    }

    private static List<List<String>> permutations(List<String> values)
    {
        List<List<String>> result = new ArrayList<>();
        collect(new ArrayList<>(values), new ArrayList<String>(), result);
        return result;
    }

    private static void collect(List<String> rest, List<String> prefix, List<List<String>> result)
    {
        if (rest.isEmpty())
        {
            result.add(new ArrayList<>(prefix));
            return;
        }
        for (int i = 0; i < rest.size(); i++)
        {
            List<String> remaining = new ArrayList<>(rest);
            prefix.add(remaining.remove(i));
            collect(remaining, prefix, result);
            prefix.remove(prefix.size() - 1);
        }
    }

    private static void assertMessageContains(org.junit.jupiter.api.function.Executable executable, String expected)
    {
        ServiceException error = assertThrows(ServiceException.class, executable);
        assertTrue(error.getMessage() != null && error.getMessage().contains(expected),
                "期望包含「" + expected + "」，实际：" + error.getMessage());
    }

}
