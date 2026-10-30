package com.ruoyi.dqservice.template.plan;

import com.ruoyi.common.core.exception.ServiceException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 不可变的原子条件描述。
 *
 * 常量（文本、枚举集合、正则、数值）在创建时一次性准备；非法正则或非法数值立即失败，
 * 不伪装成「不匹配」。求值只读输入，不修改行 Map、不写报告、不访问数据库与当前时间。
 */
public final class PredicatePlan
{
    private final String predicateId;
    private final PredicateKind kind;
    private final String field;
    private final String literal;
    private final Object constant;
    private final List<String> dependsOn;
    private final int originalPosition;

    private PredicatePlan(String predicateId, PredicateKind kind, String field, String literal,
            List<String> dependsOn, int originalPosition)
    {
        if (predicateId == null || predicateId.trim().isEmpty()) throw new ServiceException("谓词标识不能为空");
        if (kind == null) throw new ServiceException("谓词类型不能为空");
        if (field == null || field.trim().isEmpty()) throw new ServiceException("谓词字段不能为空");
        if (kind.isValueRequired() && literal == null) throw new ServiceException("谓词缺少常量：" + predicateId);
        if (!kind.isValueRequired() && literal != null) throw new ServiceException("保护谓词不接受常量：" + predicateId);
        if (originalPosition < 0) throw new ServiceException("谓词原始位置不合法：" + predicateId);
        this.predicateId = predicateId.trim();
        this.kind = kind;
        this.field = field;
        this.literal = literal;
        this.constant = prepare(this.predicateId, kind, literal);
        this.dependsOn = Collections.unmodifiableList(new ArrayList<>(
                dependsOn == null ? Collections.<String>emptyList() : dependsOn));
        this.originalPosition = originalPosition;
    }

    public static PredicatePlan create(String predicateId, PredicateKind kind, String field, String literal,
            List<String> dependsOn, int originalPosition)
    {
        return new PredicatePlan(predicateId, kind, field, literal, dependsOn, originalPosition);
    }

    private static Object prepare(String id, PredicateKind kind, String literal)
    {
        switch (kind)
        {
            case NOT_NULL:
            case IS_TEXT:
            case IS_NUMBER:
                return null;
            case TEXT_EQ:
            case TEXT_NE:
                return literal;
            case TEXT_IN:
            case TEXT_NOT_IN:
                Set<String> values = new LinkedHashSet<>();
                for (String part : literal.split(","))
                {
                    String value = part.trim();
                    if (!value.isEmpty()) values.add(value);
                }
                if (values.isEmpty()) throw new ServiceException("枚举谓词缺少有效取值：" + id);
                return Collections.unmodifiableSet(values);
            case REGEX_MATCH:
            case REGEX_NOT_MATCH:
                try
                {
                    return Pattern.compile(literal);
                }
                catch (PatternSyntaxException e)
                {
                    throw new ServiceException("谓词正则不合法：" + id + "，" + e.getDescription());
                }
            case NUMBER_EQ:
            case NUMBER_NE:
            case NUMBER_GT:
            case NUMBER_GE:
            case NUMBER_LT:
            case NUMBER_LE:
                try
                {
                    return new BigDecimal(literal.trim());
                }
                catch (NumberFormatException e)
                {
                    throw new ServiceException("谓词数值常量不合法：" + id);
                }
            default:
                throw new ServiceException("未知谓词类型：" + kind);
        }
    }

    public String getPredicateId()
    {
        return predicateId;
    }

    public PredicateKind getKind()
    {
        return kind;
    }

    public String getField()
    {
        return field;
    }

    public String getLiteral()
    {
        return literal;
    }

    /** 准备阶段构造好的只读常量：String / Set / Pattern / BigDecimal，保护谓词为 null。 */
    public Object getConstant()
    {
        return constant;
    }

    public List<String> getDependsOn()
    {
        return dependsOn;
    }

    public int getOriginalPosition()
    {
        return originalPosition;
    }

    /** 指纹用的规范化描述，不含随机数、执行时间或样本行。 */
    public String describe()
    {
        return predicateId + "|" + kind + "|" + field + "|" + (literal == null ? "" : literal)
                + "|" + String.join(",", dependsOn) + "|" + originalPosition;
    }

    @Override
    public String toString()
    {
        return describe();
    }
}
