package com.ruoyi.dqservice.l4;

import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.PredicateKind;
import com.ruoyi.dqservice.template.plan.PredicatePlan;
import com.ruoyi.dqservice.util.RuleParser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 独立参考求值器：固定按原始合法顺序、纯 Java 实现。
 *
 * 刻意不复用生产的 PredicateEvaluator，也不经过 Drools，用来给四组对照提供第三方期望。
 */
public final class ReferenceCompoundEvaluator
{
    private ReferenceCompoundEvaluator()
    {
    }

    public static List<Map<String, Object>> evaluate(CompoundCheckPlan plan, List<Map<String, Object>> rows)
    {
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> row : rows)
        {
            boolean hit = true;
            for (String predicateId : plan.originalOrder())
            {
                if (!holds(plan.predicate(predicateId), row))
                {
                    hit = false;
                    break;
                }
            }
            if (hit) details.add(detail(plan, row));
        }
        return details;
    }

    private static boolean holds(PredicatePlan predicate, Map<String, Object> row)
    {
        Object value = row.get(predicate.getField());
        switch (predicate.getKind())
        {
            case NOT_NULL:
                return value != null;
            case IS_TEXT:
                return value instanceof String;
            case IS_NUMBER:
                return value instanceof java.lang.Number && finite(value);
            case TEXT_EQ:
                return value instanceof String && value.equals(predicate.getLiteral());
            case TEXT_NE:
                return value instanceof String && !value.equals(predicate.getLiteral());
            case TEXT_IN:
                return value instanceof String && allowed(predicate).contains(value);
            case TEXT_NOT_IN:
                return value instanceof String && !allowed(predicate).contains(value);
            case REGEX_MATCH:
                return value instanceof String && matcher(predicate).matcher((String) value).matches();
            case REGEX_NOT_MATCH:
                return value instanceof String && !matcher(predicate).matcher((String) value).matches();
            case NUMBER_EQ:
            case NUMBER_NE:
            case NUMBER_GT:
            case NUMBER_GE:
            case NUMBER_LT:
            case NUMBER_LE:
                if (!(value instanceof java.lang.Number) || !finite(value)) return false;
                return compare(predicate.getKind(), new BigDecimal(value.toString()),
                        new BigDecimal(predicate.getLiteral().trim()));
            default:
                throw new IllegalStateException("参考求值器不支持：" + predicate.getKind());
        }
    }

    private static boolean finite(Object value)
    {
        try
        {
            new BigDecimal(value.toString());
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }

    private static boolean compare(PredicateKind kind, BigDecimal left, BigDecimal right)
    {
        int cmp = left.compareTo(right);
        switch (kind)
        {
            case NUMBER_EQ: return cmp == 0;
            case NUMBER_NE: return cmp != 0;
            case NUMBER_GT: return cmp > 0;
            case NUMBER_GE: return cmp >= 0;
            case NUMBER_LT: return cmp < 0;
            case NUMBER_LE: return cmp <= 0;
            default: throw new IllegalStateException("参考求值器不支持比较：" + kind);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> allowed(PredicatePlan predicate)
    {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>();
        for (String part : predicate.getLiteral().split(","))
        {
            String value = part.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private static Pattern matcher(PredicatePlan predicate)
    {
        return Pattern.compile(predicate.getLiteral());
    }

    private static Map<String, Object> detail(CompoundCheckPlan plan, Map<String, Object> row)
    {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", plan.getRuleName());
        detail.put("field", plan.getField());
        detail.put("rowId", row.get(RuleParser.ROW_ID_KEY));
        detail.put("checkId", plan.getCheckId());
        detail.put("templateCode", plan.getTemplateCode());
        detail.put("dimensionCode", plan.getDimension());
        detail.put("resultType", "VIOLATION");
        detail.put("reason", plan.getReason());
        detail.put("comparisonValue", plan.getComparisonValue());
        Map<String, Object> involved = new LinkedHashMap<>();
        for (String field : plan.getInvolvedFields()) involved.put(field, row.get(field));
        detail.put("involvedFields", involved);
        return detail;
    }
}
