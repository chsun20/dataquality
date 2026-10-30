package com.ruoyi.dqservice.template.plan;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 第一版复合检查支持的原子谓词。
 *
 * 只描述「字段 + 常量 + 比较方式」，不携带任意 Java 或 DRL 表达式。
 * 需要常量的谓词必须带上保护条件：字段存在且非 NULL，数值还要有限、文本还要是字符串，
 * 由构建器补齐依赖，校验器复核，避免原生 DRL 改变物理求值安排后触发空值或类型异常。
 */
public enum PredicateKind
{
    /** 值存在：字段存在且不为 NULL。 */
    NOT_NULL(false),
    /** 值是字符串。 */
    IS_TEXT(false),
    /** 值是有限数值。 */
    IS_NUMBER(false),
    /** 文本相等，区分大小写，不隐式 trim。 */
    TEXT_EQ(true),
    /** 文本不等。 */
    TEXT_NE(true),
    /** 文本枚举命中，逗号分隔，区分大小写。 */
    TEXT_IN(true),
    /** 文本枚举未命中。 */
    TEXT_NOT_IN(true),
    /** 文本完整匹配正则。 */
    REGEX_MATCH(true),
    /** 文本不匹配正则。 */
    REGEX_NOT_MATCH(true),
    NUMBER_EQ(true),
    NUMBER_NE(true),
    NUMBER_GT(true),
    NUMBER_GE(true),
    NUMBER_LT(true),
    NUMBER_LE(true);

    private final boolean valueRequired;

    PredicateKind(boolean valueRequired)
    {
        this.valueRequired = valueRequired;
    }

    /** 是否需要类型化常量。 */
    public boolean isValueRequired()
    {
        return valueRequired;
    }

    /** 保护谓词本身不携带常量，求值只做 NULL 或类型判定。 */
    public boolean isGuard()
    {
        return !valueRequired;
    }

    /**
     * 该谓词求值时必须具备的保护条件。
     * 返回的每一项都必须作为依赖显式声明，否则计划校验失败。
     */
    public List<PredicateKind> requiredGuards()
    {
        switch (this)
        {
            case IS_TEXT:
            case IS_NUMBER:
                return Collections.singletonList(NOT_NULL);
            case TEXT_EQ:
            case TEXT_NE:
            case TEXT_IN:
            case TEXT_NOT_IN:
            case REGEX_MATCH:
            case REGEX_NOT_MATCH:
                return Arrays.asList(NOT_NULL, IS_TEXT);
            case NUMBER_EQ:
            case NUMBER_NE:
            case NUMBER_GT:
            case NUMBER_GE:
            case NUMBER_LT:
            case NUMBER_LE:
                return Arrays.asList(NOT_NULL, IS_NUMBER);
            default:
                return Collections.emptyList();
        }
    }
}
