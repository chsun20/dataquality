package com.ruoyi.dqservice.template.plan;

import com.ruoyi.dqservice.template.DrlSupport;

/**
 * 为同一复合检查生成两种执行表示。
 *
 * EXPLICIT：每项生成一条 Map() 规则，then 中调用 Java 显式短路求值；
 * NATIVE：逐原子约束放进同一个 Map pattern，由 Drools 自身决定物理求值安排。
 *
 * 两种表示都写入选定计划指纹，注册与调用边界据此校验 DRL 与计划一致；
 * 顺序或参数变化会产生新的 DRL 文本，从而自然得到新的 L5 编译缓存键。
 */
public final class CompoundDrlBuilder
{
    private CompoundDrlBuilder()
    {
    }

    public static String build(CompoundCheckPlan plan, CompoundMode mode, boolean diagnostic)
    {
        if (plan == null) throw new IllegalArgumentException("复合检查计划不能为空");
        if (mode == null) throw new IllegalArgumentException("执行表示不能为空");
        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        drl.append("// plan-fingerprint: ").append(plan.getFingerprint()).append('\n');
        drl.append("// plan-order: ").append(String.join(",", plan.getSelectedOrder())).append("\n\n");
        String ruleName = DrlSupport.escape(plan.getRuleName() + "_" + plan.getCheckId() + "_" + mode.name().toLowerCase());
        if (mode == CompoundMode.EXPLICIT)
        {
            drl.append("rule \"").append(ruleName).append("\"\n")
                    .append("    when\n        $row: Map()\n")
                    .append("    then\n")
                    .append("        qualityContext.")
                    .append(diagnostic ? "evaluateCompoundDiagnostic" : "evaluateCompound")
                    .append("(\"").append(DrlSupport.escape(plan.getCheckId())).append("\", $row, violations);\n")
                    .append("end\n");
            return drl.toString();
        }
        drl.append("rule \"").append(ruleName).append("\"\n")
                .append("    when\n        $row: Map(");
        String method = diagnostic ? "holdsDiagnostic" : "holds";
        boolean first = true;
        for (String predicateId : plan.getSelectedOrder())
        {
            if (!first) drl.append(",");
            first = false;
            drl.append("\n            qualityContext.").append(method)
                    .append("(\"").append(DrlSupport.escape(plan.getCheckId())).append("\", \"")
                    .append(DrlSupport.escape(predicateId)).append("\", this)");
        }
        drl.append(")\n")
                .append("    then\n")
                .append("        qualityContext.recordCompoundHit(\"")
                .append(DrlSupport.escape(plan.getCheckId())).append("\", $row, violations);\n")
                .append("end\n");
        return drl.toString();
    }
}
