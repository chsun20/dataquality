package com.ruoyi.dqservice.template.plan;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一的单原子判定。
 *
 * 两种执行表示（Java 显式短路、原生 DRL 约束）都最终落到这里，避免用不同匹配函数制造差异。
 * 判定是全域的：缺失字段、NULL、不兼容类型与非有限数值在类型域检查处直接返回 false，
 * 因此原生 DRL 即使改变物理求值顺序也不会触发空值或类型异常。
 */
public final class PredicateEvaluator
{
    private PredicateEvaluator()
    {
    }

    public static boolean test(PredicatePlan predicate, Map<String, Object> row)
    {
        Object value = row == null ? null : row.get(predicate.getField());
        switch (predicate.getKind())
        {
            case NOT_NULL:
                return value != null;
            case IS_TEXT:
                return value instanceof String;
            case IS_NUMBER:
                return number(value) != null;
            case TEXT_EQ:
                return value instanceof String && value.equals(predicate.getConstant());
            case TEXT_NE:
                return value instanceof String && !value.equals(predicate.getConstant());
            case TEXT_IN:
                return value instanceof String && values(predicate).contains(value);
            case TEXT_NOT_IN:
                return value instanceof String && !values(predicate).contains(value);
            case REGEX_MATCH:
                return value instanceof String && pattern(predicate).matcher((String) value).matches();
            case REGEX_NOT_MATCH:
                return value instanceof String && !pattern(predicate).matcher((String) value).matches();
            case NUMBER_EQ:
            case NUMBER_NE:
            case NUMBER_GT:
            case NUMBER_GE:
            case NUMBER_LT:
            case NUMBER_LE:
                BigDecimal left = number(value);
                if (left == null) return false;
                int cmp = left.compareTo((BigDecimal) predicate.getConstant());
                return relational(predicate.getKind(), cmp);
            default:
                return false;
        }
    }

    private static boolean relational(PredicateKind kind, int cmp)
    {
        switch (kind)
        {
            case NUMBER_EQ: return cmp == 0;
            case NUMBER_NE: return cmp != 0;
            case NUMBER_GT: return cmp > 0;
            case NUMBER_GE: return cmp >= 0;
            case NUMBER_LT: return cmp < 0;
            case NUMBER_LE: return cmp <= 0;
            default: return false;
        }
    }

    /** 只有真正的数值类型参与数值运算；文本数字不隐式转数值，NaN/Infinity 不算有限数值。 */
    private static BigDecimal number(Object value)
    {
        if (!(value instanceof Number)) return null;
        try
        {
            return new BigDecimal(value.toString());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> values(PredicatePlan predicate)
    {
        return (Set<String>) predicate.getConstant();
    }

    private static Pattern pattern(PredicatePlan predicate)
    {
        return (Pattern) predicate.getConstant();
    }
}
