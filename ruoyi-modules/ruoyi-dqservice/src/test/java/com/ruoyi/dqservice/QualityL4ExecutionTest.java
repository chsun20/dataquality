package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.CompiledRuleCache;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.l4.CompoundFixtures;
import com.ruoyi.dqservice.l4.ReferenceCompoundEvaluator;
import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor.GroupResult;
import com.ruoyi.dqservice.template.plan.CompoundCheckExecutor.Ordering;
import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.CompoundDrlBuilder;
import com.ruoyi.dqservice.template.plan.CompoundMode;
import com.ruoyi.dqservice.template.plan.PlanResult;
import com.ruoyi.dqservice.template.plan.PredicateKind;
import com.ruoyi.dqservice.template.plan.PredicatePlanner;
import com.ruoyi.dqservice.util.RuleParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4 四组执行对照：{Java 显式短路, 原生 DRL 约束} × {原序, 排序}。
 *
 * 要求四组明细逐项一致、计数口径一致，并用故意错序的夹具证明短路真的少执行昂贵条件；
 * 同时验证计划指纹进入 DRL、执行表示不可混用、编译缓存按 DRL 文本复用。
 */
class QualityL4ExecutionTest
{
    private DroolsManager drools;

    @BeforeEach
    void setUp()
    {
        drools = new DroolsManager();
    }

    @Test
    void fourGroupsAgreeWithIndependentReference()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(2000);
        List<Map<String, Object>> expected = ReferenceCompoundEvaluator.evaluate(plan, rows);
        assertEquals(15, expected.size());

        Map<String, GroupResult> groups = new CompoundCheckExecutor(drools)
                .runAll(plan, rows, CompoundFixtures.DETECTED_AT, false);
        assertEquals(Arrays.asList("EXPLICIT-ORIGINAL", "EXPLICIT-SORTED", "NATIVE-ORIGINAL", "NATIVE-SORTED"),
                new ArrayList<>(groups.keySet()));

        for (GroupResult group : groups.values())
        {
            assertEquals(CompoundFixtures.canonical(expected), CompoundFixtures.canonical(group.getViolations()),
                    group.describe());
            assertEquals(2000, group.getChecked(), group.describe());
            assertEquals(0, group.getSkipped(), group.describe());
            assertEquals(15, group.getViolationCount(), group.describe());
            assertEquals(15, group.getViolationRows(), group.describe());
            assertEquals(99.3, group.getPassRate().doubleValue(), 1e-9, group.describe());
            assertEquals(1, group.getCounters().size());
            assertEquals(plan.getCheckId(), group.getCounters().get(0).getCheckId());
        }
        // 排序只改变执行顺序，ORIGINAL 与 SORTED 的计划指纹必须不同。
        assertNotEquals(groups.get("EXPLICIT-ORIGINAL").getPlan().getFingerprint(),
                groups.get("EXPLICIT-SORTED").getPlan().getFingerprint());
    }

    @Test
    void explicitShortCircuitSkipsExpensivePredicateFarLessOften()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(2000);
        CompoundCheckExecutor executor = new CompoundCheckExecutor(drools);

        GroupResult original = executor.run(plan, CompoundMode.EXPLICIT, Ordering.ORIGINAL, rows,
                CompoundFixtures.DETECTED_AT, true);
        GroupResult sorted = executor.run(plan, CompoundMode.EXPLICIT, Ordering.SORTED, rows,
                CompoundFixtures.DETECTED_AT, true);

        Map<String, Integer> before = original.getPredicateCalls().get(plan.getCheckId());
        Map<String, Integer> after = sorted.getPredicateCalls().get(plan.getCheckId());
        // 原序把昂贵的正则放在类别条件之前：每行都要跑一次正则。
        assertEquals(2000, before.get("code#REGEX_NOT_MATCH"));
        assertEquals(286, before.get("category#TEXT_EQ"));
        // 排序后廉价条件依次拦住 90% 与一半，正则只对 AUDIT 且 PENDING 的行执行。
        assertEquals(2000, after.get("category#TEXT_EQ"));
        assertEquals(200, after.get("status#TEXT_EQ"));
        assertEquals(100, after.get("code#REGEX_NOT_MATCH"));
        assertEquals(CompoundFixtures.canonical(original.getViolations()),
                CompoundFixtures.canonical(sorted.getViolations()));
    }

    @Test
    void nativeGroupDelegatesAtomicsToDrlConstraints()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(2000);

        GroupResult nativeSorted = new CompoundCheckExecutor(drools).run(plan, CompoundMode.NATIVE,
                Ordering.SORTED, rows, CompoundFixtures.DETECTED_AT, true);
        Map<String, Integer> calls = nativeSorted.getPredicateCalls().get(plan.getCheckId());

        // 原生组不调用整条 Java 短路链，而是逐原子交给 Drools 约束；这里只验证约束确实被触发。
        for (String predicateId : plan.getSelectedOrder())
            assertNotNull(calls.get(predicateId), predicateId);
        assertTrue(calls.get("category#NOT_NULL") >= 2000, calls.toString());
        assertTrue(calls.get("code#REGEX_NOT_MATCH") >= 15, calls.toString());
        assertEquals(2000, nativeSorted.getChecked());
        assertEquals(15, nativeSorted.getTriggerCount());
    }

    @Test
    void failingGuardIsCheckedNotSkipped()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(1, "AUDIT", "PENDING", null));
        rows.add(row(2, "AUDIT", "PENDING", 123));
        rows.add(row(3, null, "PENDING", "BAD-CODE"));
        rows.add(row(4, "AUDIT", "PENDING", "BAD-CODE"));
        rows.add(row(5, "AUDIT", "PENDING", "AB-0001"));
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put(RuleParser.ROW_ID_KEY, 6);
        missing.put("status", "PENDING");
        missing.put("code", "BAD-CODE");
        rows.add(missing);

        List<Map<String, Object>> expected = ReferenceCompoundEvaluator.evaluate(plan, rows);
        assertEquals(1, expected.size());

        for (GroupResult group : new CompoundCheckExecutor(drools)
                .runAll(plan, rows, CompoundFixtures.DETECTED_AT, false).values())
        {
            assertEquals(CompoundFixtures.canonical(expected), CompoundFixtures.canonical(group.getViolations()),
                    group.describe());
            // 缺失字段、NULL、类型不符只让整项不成立，仍计入 checked 且不计 skipped。
            assertEquals(6, group.getChecked(), group.describe());
            assertEquals(0, group.getSkipped(), group.describe());
            assertEquals(0, group.getUnverifiable(), group.describe());
            assertEquals(1, group.getViolationCount(), group.describe());
            assertEquals(83.3, group.getPassRate().doubleValue(), 1e-9, group.describe());
        }
    }

    @Test
    void violationDetailIsStableAcrossModesAndOrders()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row(1, "AUDIT", "PENDING", "BAD-CODE"));

        for (GroupResult group : new CompoundCheckExecutor(drools)
                .runAll(plan, rows, CompoundFixtures.DETECTED_AT, false).values())
        {
            assertEquals(1, group.getViolations().size(), group.describe());
            Map<String, Object> detail = group.getViolations().get(0);
            assertEquals("业务编码格式", detail.get("rule"));
            assertEquals("code", detail.get("field"));
            assertEquals(1, detail.get("rowId"));
            assertEquals(CompoundFixtures.CODE_CHECK_ID, detail.get("checkId"));
            assertEquals("compoundExperimental", detail.get("templateCode"));
            assertEquals("VALIDITY", detail.get("dimensionCode"));
            assertEquals("VIOLATION", detail.get("resultType"));
            assertEquals("待审核业务记录编码不符合约定格式", detail.get("reason"));
            assertEquals(CompoundFixtures.CODE_PATTERN, detail.get("comparisonValue"));
            @SuppressWarnings("unchecked")
            Map<String, Object> involved = (Map<String, Object>) detail.get("involvedFields");
            assertEquals(Arrays.asList("category", "code", "status"), new ArrayList<>(involved.keySet()));
            assertEquals("BAD-CODE", involved.get("code"));
        }
    }

    @Test
    void emptyInputYieldsZeroCheckedAndNoPassRate()
    {
        CompoundCheckPlan plan = sortedPlan();
        for (GroupResult group : new CompoundCheckExecutor(drools)
                .runAll(plan, Collections.<Map<String, Object>>emptyList(), CompoundFixtures.DETECTED_AT, false)
                .values())
        {
            assertEquals(0, group.getChecked(), group.describe());
            assertEquals(0, group.getViolationCount(), group.describe());
            assertEquals(0, group.getViolationRows(), group.describe());
            assertNull(group.getPassRate(), group.describe());
            assertTrue(group.getViolations().isEmpty(), group.describe());
        }
    }

    @Test
    void drlTextCarriesFingerprintAndDiffersPerRepresentation()
    {
        CompoundCheckPlan plan = sortedPlan();
        String explicit = CompoundDrlBuilder.build(plan, CompoundMode.EXPLICIT, false);
        String explicitDiagnostic = CompoundDrlBuilder.build(plan, CompoundMode.EXPLICIT, true);
        String nativeDrl = CompoundDrlBuilder.build(plan, CompoundMode.NATIVE, false);
        String original = CompoundDrlBuilder.build(plan.withOrder(plan.originalOrder()), CompoundMode.EXPLICIT, false);

        assertTrue(explicit.contains("// plan-fingerprint: " + plan.getFingerprint()));
        assertTrue(explicit.contains("evaluateCompound("));
        assertFalse(explicit.contains("evaluateCompoundDiagnostic("));
        assertTrue(explicitDiagnostic.contains("evaluateCompoundDiagnostic("));
        assertNotEquals(explicit, explicitDiagnostic);

        assertTrue(nativeDrl.contains("qualityContext.holds("));
        assertTrue(nativeDrl.contains("recordCompoundHit("));
        assertNotEquals(explicit, nativeDrl);
        // 顺序变化产生新的 DRL 文本，从而自然得到新的编译缓存键。
        assertNotEquals(explicit, original);
        assertTrue(original.contains("// plan-order: " + String.join(",", plan.originalOrder())));

        drools.verifyDrl(explicit);
        drools.verifyDrl(explicitDiagnostic);
        drools.verifyDrl(nativeDrl);
    }

    @Test
    void contextRejectsMixedRepresentationAndFingerprintMismatch()
    {
        CompoundCheckPlan plan = sortedPlan();
        CompoundCheckPlan reversed = plan.withOrder(plan.originalOrder());
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(10);
        String nativeDrl = CompoundDrlBuilder.build(plan, CompoundMode.NATIVE, false);
        String explicitDrl = CompoundDrlBuilder.build(plan, CompoundMode.EXPLICIT, false);
        String reversedDrl = CompoundDrlBuilder.build(reversed, CompoundMode.EXPLICIT, false);

        QualityExecutionContext mixed = new QualityExecutionContext(CompoundFixtures.DETECTED_AT, rows);
        mixed.registerCompound(plan, nativeDrl, CompoundMode.NATIVE);
        assertMessageContains(() -> mixed.registerCompound(plan, explicitDrl, CompoundMode.EXPLICIT), "不能混用");

        QualityExecutionContext unknown = new QualityExecutionContext(CompoundFixtures.DETECTED_AT, rows);
        assertMessageContains(() -> unknown.registerCompound(plan, "package dq.dynamic\n", CompoundMode.EXPLICIT),
                "指纹不一致");

        QualityExecutionContext conflict = new QualityExecutionContext(CompoundFixtures.DETECTED_AT, rows);
        conflict.registerCompound(plan, explicitDrl, CompoundMode.EXPLICIT);
        assertMessageContains(() -> conflict.registerCompound(reversed, reversedDrl, CompoundMode.EXPLICIT),
                "标识冲突");

        assertMessageContains(() -> conflict.evaluateCompound("missing", rows.get(0),
                new ArrayList<Map<String, Object>>()), "复合检查未注册");
        // 显式注册的上下文不能被原生入口调用，反之同理。
        assertMessageContains(() -> conflict.holds(plan.getCheckId(), "category#NOT_NULL", rows.get(0)),
                "执行表示与 DRL 不一致");
        QualityExecutionContext nativeOnly = new QualityExecutionContext(CompoundFixtures.DETECTED_AT, rows);
        nativeOnly.registerCompound(plan, nativeDrl, CompoundMode.NATIVE);
        assertMessageContains(() -> nativeOnly.evaluateCompound(plan.getCheckId(), rows.get(0),
                new ArrayList<Map<String, Object>>()), "执行表示与 DRL 不一致");
    }

    @Test
    void nativeCountingCompletesExactlyOnce()
    {
        CompoundCheckPlan plan = sortedPlan();
        String nativeDrl = CompoundDrlBuilder.build(plan, CompoundMode.NATIVE, false);
        QualityExecutionContext context = new QualityExecutionContext(CompoundFixtures.DETECTED_AT,
                CompoundFixtures.codeRows(4));
        context.registerCompound(plan, nativeDrl, CompoundMode.NATIVE);

        context.completeNativeCounting(4);
        assertEquals(4, context.getCounters().get(0).getChecked());
        assertMessageContains(() -> context.completeNativeCounting(4), "原生统计只能完成一次");
        assertEquals(4, context.getCounters().get(0).getChecked());
    }

    @Test
    void compiledRuleCacheIsReusedForRepeatedRuns()
    {
        CompoundCheckPlan plan = sortedPlan();
        List<Map<String, Object>> rows = CompoundFixtures.codeRows(200);
        CompiledRuleCache cache = new CompiledRuleCache();
        CompoundCheckExecutor executor = new CompoundCheckExecutor(new DroolsManager(cache));

        GroupResult first = executor.run(plan, CompoundMode.NATIVE, Ordering.SORTED, rows,
                CompoundFixtures.DETECTED_AT, false);
        long builds = cache.getBuildSuccessCount();
        GroupResult second = executor.run(plan, CompoundMode.NATIVE, Ordering.SORTED, rows,
                CompoundFixtures.DETECTED_AT, false);

        assertEquals(1, builds);
        assertEquals(builds, cache.getBuildSuccessCount());
        assertEquals(CompoundFixtures.canonical(first.getViolations()),
                CompoundFixtures.canonical(second.getViolations()));
        assertEquals(0, cache.getBuildFailureCount());
    }

    @Test
    void singleRunRegistersAtMostOneHundredChecks()
    {
        QualityExecutionContext context = new QualityExecutionContext(CompoundFixtures.DETECTED_AT,
                CompoundFixtures.codeRows(2));
        for (int i = 1; i <= CompoundCheckPlan.MAX_COMPOUND_CHECKS; i++)
        {
            CompoundCheckPlan plan = capPlan(i);
            context.registerCompound(plan, CompoundDrlBuilder.build(plan, CompoundMode.NATIVE, false),
                    CompoundMode.NATIVE);
        }
        assertEquals(CompoundCheckPlan.MAX_COMPOUND_CHECKS, context.getCompoundChecks().size());
        CompoundCheckPlan overflow = capPlan(CompoundCheckPlan.MAX_COMPOUND_CHECKS + 1);
        assertMessageContains(() -> context.registerCompound(overflow,
                CompoundDrlBuilder.build(overflow, CompoundMode.NATIVE, false), CompoundMode.NATIVE),
                "最多注册 100 项复合检查");
    }

    // ------------------------------------------------------------------ 辅助

    private static CompoundCheckPlan sortedPlan()
    {
        CompoundCheckPlan plan = CompoundFixtures.codeFormatPlan();
        PlanResult result = new PredicatePlanner().plan(plan, CompoundFixtures.codeRows(2000),
                CompoundFixtures.options(plan));
        assertEquals(PlanResult.Reason.SORTED, result.getReason(), result.describe());
        return result.applyTo(plan);
    }

    private static CompoundCheckPlan capPlan(int index)
    {
        return CompoundCheckPlan.builder("l4-cap-" + index, "上限检查" + index, "code",
                QualityDimension.VALIDITY.name())
                .guard(PredicateKind.NOT_NULL)
                .guard(PredicateKind.IS_TEXT)
                .build();
    }

    private static Map<String, Object> row(int rowId, Object category, Object status, Object code)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(RuleParser.ROW_ID_KEY, rowId);
        row.put("category", category);
        row.put("status", status);
        row.put("code", code);
        return row;
    }

    private static void assertMessageContains(org.junit.jupiter.api.function.Executable executable, String expected)
    {
        ServiceException error = assertThrows(ServiceException.class, executable);
        assertTrue(error.getMessage() != null && error.getMessage().contains(expected),
                "期望包含「" + expected + "」，实际：" + error.getMessage());
    }
}
