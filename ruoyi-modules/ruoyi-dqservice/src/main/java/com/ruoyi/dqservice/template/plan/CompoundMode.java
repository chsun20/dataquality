package com.ruoyi.dqservice.template.plan;

/** 同一复合检查的两种执行表示；一轮执行内不可混用。 */
public enum CompoundMode
{
    /** 每行一条 Map() 规则，在 then 中调用 Java 显式短路求值。 */
    EXPLICIT,
    /** 逐原子约束放进同一个 Map pattern，由 Drools 自身决定物理求值安排。 */
    NATIVE
}
