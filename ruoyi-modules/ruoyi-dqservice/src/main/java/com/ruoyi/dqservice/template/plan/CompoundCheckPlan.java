package com.ruoyi.dqservice.template.plan;

import com.ruoyi.common.core.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一项复合检查的不可变描述：平坦 AND、固定违规说明、原始条件顺序与选定顺序。
 *
 * 结果元数据（rule / field / 维度 / reason / comparisonValue）不依赖排序位置，
 * 因此改变顺序不会改变明细内容。指纹由版本、条件类型/字段/常量、依赖、结果定义与选定顺序
 * 规范化后生成 SHA-256，用于校验 DRL 与计划一致，并自然进入 L5 编译缓存键。
 */
public final class CompoundCheckPlan
{
    public static final int VERSION = 1;

    /** 单个原型运行最多 100 项检查；单项条件数量上限 16。 */
    public static final int MAX_PREDICATES = 16;

    public static final int MAX_COMPOUND_CHECKS = 100;

    public static final String DEFAULT_TEMPLATE_CODE = "compoundExperimental";

    private final String checkId;
    private final String ruleName;
    private final String field;
    private final String dimension;
    private final String templateCode;
    private final String reason;
    private final Object comparisonValue;
    private final List<PredicatePlan> predicates;
    private final List<String> selectedOrder;
    private final String fingerprint;
    private final Map<String, PredicatePlan> byId;

    private CompoundCheckPlan(String checkId, String ruleName, String field, String dimension, String templateCode,
            String reason, Object comparisonValue, List<PredicatePlan> predicates, List<String> selectedOrder)
    {
        this.checkId = require(checkId, "检查标识");
        this.ruleName = require(ruleName, "规则名称");
        this.field = require(field, "目标字段");
        this.dimension = require(dimension, "质量维度");
        this.templateCode = require(templateCode, "模板代码");
        this.reason = reason == null ? "" : reason;
        this.comparisonValue = comparisonValue;
        this.predicates = Collections.unmodifiableList(new ArrayList<>(predicates));
        this.selectedOrder = Collections.unmodifiableList(new ArrayList<>(selectedOrder));
        Map<String, PredicatePlan> index = new LinkedHashMap<>();
        for (PredicatePlan predicate : this.predicates) index.put(predicate.getPredicateId(), predicate);
        this.byId = Collections.unmodifiableMap(index);
        this.fingerprint = fingerprint();
        validate(this.predicates, this.selectedOrder);
    }

    private static String require(String value, String label)
    {
        if (value == null || value.trim().isEmpty()) throw new ServiceException("复合检查缺少" + label);
        return value;
    }

    /**
     * 校验：标识唯一、依赖存在、依赖先后合法、保护条件完整，并且给定顺序是合法拓扑序。
     * 有序性保证不会出现「依赖已为 false 仍去求值」的情形。
     */
    private static void validate(List<PredicatePlan> predicates, List<String> order)
    {
        if (predicates.isEmpty()) throw new ServiceException("复合检查至少需要 1 个条件");
        if (predicates.size() > MAX_PREDICATES)
            throw new ServiceException("复合检查最多支持 " + MAX_PREDICATES + " 个条件");
        Set<String> ids = new LinkedHashSet<>();
        for (PredicatePlan predicate : predicates)
            if (!ids.add(predicate.getPredicateId()))
                throw new ServiceException("复合检查条件标识重复：" + predicate.getPredicateId());
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++)
            if (index.put(order.get(i), i) != null) throw new ServiceException("执行顺序存在重复条件：" + order.get(i));
        if (index.size() != predicates.size()) throw new ServiceException("执行顺序与条件集合不一致");
        Map<String, PredicatePlan> byId = new HashMap<>();
        for (PredicatePlan predicate : predicates) byId.put(predicate.getPredicateId(), predicate);
        for (String id : order)
            if (!byId.containsKey(id)) throw new ServiceException("执行顺序包含未知条件：" + id);
        rejectCycles(byId);
        for (PredicatePlan predicate : predicates)
        {
            int position = index.get(predicate.getPredicateId());
            for (String dependency : predicate.getDependsOn())
            {
                Integer dependencyPosition = index.get(dependency);
                if (dependencyPosition == null)
                    throw new ServiceException("条件依赖不存在：" + predicate.getPredicateId() + " -> " + dependency);
                if (dependencyPosition >= position)
                    throw new ServiceException("执行顺序违反依赖：" + predicate.getPredicateId() + " 需要先于 " + dependency);
            }
            for (PredicateKind guard : predicate.getKind().requiredGuards())
            {
                String guardId = findGuard(byId.values(), guard, predicate.getField());
                if (guardId == null)
                    throw new ServiceException("条件缺少保护条件：" + predicate.getPredicateId() + " 需要 " + guard);
                if (!predicate.getDependsOn().contains(guardId))
                    throw new ServiceException("条件未声明保护依赖：" + predicate.getPredicateId() + " 需要 " + guardId);
            }
        }
    }

    /** 显式拒绝依赖成环，避免报错只表现为顺序不合法。 */
    private static void rejectCycles(Map<String, PredicatePlan> byId)
    {
        for (PredicatePlan predicate : byId.values())
        {
            Set<String> visited = new LinkedHashSet<>();
            String cursor = predicate.getPredicateId();
            while (cursor != null)
            {
                if (!visited.add(cursor))
                    throw new ServiceException("条件依赖成环：" + predicate.getPredicateId());
                PredicatePlan current = byId.get(cursor);
                if (current == null || current.getDependsOn().isEmpty()) break;
                cursor = current.getDependsOn().get(0);
            }
        }
    }

    private static String findGuard(Iterable<PredicatePlan> predicates, PredicateKind kind, String field)
    {
        for (PredicatePlan predicate : predicates)
            if (predicate.getKind() == kind && predicate.getField().equals(field)) return predicate.getPredicateId();
        return null;
    }

    public String getCheckId()
    {
        return checkId;
    }

    public String getRuleName()
    {
        return ruleName;
    }

    /** 明细里的 field：复合检查的目标字段，不随排序变化。 */
    public String getField()
    {
        return field;
    }

    public String getDimension()
    {
        return dimension;
    }

    public String getTemplateCode()
    {
        return templateCode;
    }

    public String getReason()
    {
        return reason;
    }

    public Object getComparisonValue()
    {
        return comparisonValue;
    }

    public List<PredicatePlan> getPredicates()
    {
        return predicates;
    }

    /** 原始合法拓扑序。 */
    public List<String> getOriginalOrder()
    {
        return originalOrder();
    }

    public List<String> originalOrder()
    {
        List<String> order = new ArrayList<>(predicates.size());
        for (PredicatePlan predicate : predicates) order.add(predicate.getPredicateId());
        return Collections.unmodifiableList(order);
    }

    /** 规划器选定的顺序；未排序时等于原始顺序。 */
    public List<String> getSelectedOrder()
    {
        return selectedOrder;
    }

    public PredicatePlan predicate(String predicateId)
    {
        PredicatePlan predicate = byId.get(predicateId);
        if (predicate == null) throw new ServiceException("复合检查缺少条件：" + predicateId);
        return predicate;
    }

    /** 明细 involvedFields 的字段顺序：条件首次出现的顺序，与排序无关。 */
    public List<String> getInvolvedFields()
    {
        Set<String> fields = new LinkedHashSet<>();
        for (PredicatePlan predicate : predicates) fields.add(predicate.getField());
        return Collections.unmodifiableList(new ArrayList<>(fields));
    }

    public String getFingerprint()
    {
        return fingerprint;
    }

    public boolean isSorted()
    {
        return !selectedOrder.equals(originalOrder());
    }

    /** 返回只改变执行顺序的新计划，指纹随之改变。 */
    public CompoundCheckPlan withOrder(List<String> order)
    {
        if (order == null) throw new ServiceException("执行顺序不能为空");
        return new CompoundCheckPlan(checkId, ruleName, field, dimension, templateCode, reason, comparisonValue,
                predicates, order);
    }

    private String fingerprint()
    {
        StringBuilder text = new StringBuilder();
        text.append("version=").append(VERSION).append('\n');
        text.append("check=").append(escape(checkId)).append('\n');
        text.append("rule=").append(escape(ruleName)).append('\n');
        text.append("field=").append(escape(field)).append('\n');
        text.append("dimension=").append(escape(dimension)).append('\n');
        text.append("template=").append(escape(templateCode)).append('\n');
        text.append("reason=").append(escape(reason)).append('\n');
        text.append("comparison=").append(escape(String.valueOf(comparisonValue))).append('\n');
        for (PredicatePlan predicate : predicates) text.append("predicate=").append(escape(predicate.describe())).append('\n');
        text.append("order=").append(String.join(",", selectedOrder)).append('\n');
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest)
            {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    private static String escape(String value)
    {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\0", "\\0");
    }

    /**
     * 用显式条件列表与显式顺序定义计划；校验逻辑与构造器完全一致。
     * 供反序列化与边界测试使用，不会自动补齐依赖。
     */
    public static CompoundCheckPlan define(String checkId, String ruleName, String field, String dimension,
            String templateCode, String reason, Object comparisonValue, List<PredicatePlan> predicates,
            List<String> order)
    {
        return new CompoundCheckPlan(checkId, ruleName, field, dimension, templateCode, reason, comparisonValue,
                predicates, order);
    }

    /** 用显式条件列表定义计划，选定顺序等于给定顺序。 */
    public static CompoundCheckPlan define(String checkId, String ruleName, String field, String dimension,
            String templateCode, String reason, Object comparisonValue, List<PredicatePlan> predicates)
    {
        List<String> order = new ArrayList<>(predicates.size());
        for (PredicatePlan predicate : predicates) order.add(predicate.getPredicateId());
        return define(checkId, ruleName, field, dimension, templateCode, reason, comparisonValue, predicates, order);
    }

    /**
     * 条件构造器：补齐保护依赖并保证依赖先于被依赖项加入，避免手写依赖遗漏或成环。
     */
    public static Builder builder(String checkId, String ruleName, String field, String dimension)
    {
        return new Builder(checkId, ruleName, field, dimension);
    }

    public static final class Builder
    {
        private final String checkId;
        private final String ruleName;
        private final String field;
        private final String dimension;
        private String templateCode = DEFAULT_TEMPLATE_CODE;
        private String reason = "";
        private Object comparisonValue;
        private final List<PredicatePlan> predicates = new ArrayList<>();
        private final Map<String, String> guards = new LinkedHashMap<>();
        private final Map<String, Integer> occurrences = new HashMap<>();

        private Builder(String checkId, String ruleName, String field, String dimension)
        {
            this.checkId = checkId;
            this.ruleName = ruleName;
            this.field = field;
            this.dimension = dimension;
        }

        public Builder template(String code)
        {
            this.templateCode = code;
            return this;
        }

        public Builder reason(String text)
        {
            this.reason = text;
            return this;
        }

        public Builder comparison(Object value)
        {
            this.comparisonValue = value;
            return this;
        }

        /** 显式声明目标字段的某个保护条件；重复声明只保留一份。 */
        public Builder guard(PredicateKind kind)
        {
            return guard(kind, field);
        }

        /** 显式声明任意字段的保护条件，供「同一条检查引用多个字段」的复合条件使用。 */
        public Builder guard(PredicateKind kind, String target)
        {
            if (kind == null || !kind.isGuard()) throw new ServiceException("保护条件只能是 NULL 或类型判定");
            ensureGuard(kind, target);
            return this;
        }

        public Builder textEquals(String literal)
        {
            return predicate(PredicateKind.TEXT_EQ, field, literal);
        }

        public Builder textEquals(String target, String literal)
        {
            return predicate(PredicateKind.TEXT_EQ, target, literal);
        }

        public Builder textNotEquals(String literal)
        {
            return predicate(PredicateKind.TEXT_NE, field, literal);
        }

        public Builder textIn(String literal)
        {
            return predicate(PredicateKind.TEXT_IN, field, literal);
        }

        public Builder textNotIn(String literal)
        {
            return predicate(PredicateKind.TEXT_NOT_IN, field, literal);
        }

        public Builder matches(String regex)
        {
            return predicate(PredicateKind.REGEX_MATCH, field, regex);
        }

        public Builder notMatches(String regex)
        {
            return predicate(PredicateKind.REGEX_NOT_MATCH, field, regex);
        }

        public Builder numberEquals(String literal)
        {
            return predicate(PredicateKind.NUMBER_EQ, field, literal);
        }

        public Builder numberGreaterThan(String literal)
        {
            return predicate(PredicateKind.NUMBER_GT, field, literal);
        }

        public Builder numberLessThan(String literal)
        {
            return predicate(PredicateKind.NUMBER_LT, field, literal);
        }

        /** 加入目标字段上的取值条件，并自动补齐所需的非空/类型保护依赖。 */
        public Builder predicate(PredicateKind kind, String literal)
        {
            return predicate(kind, field, literal);
        }

        /** 加入任意字段上的取值条件；保护条件按该字段独立补齐且不重复。 */
        public Builder predicate(PredicateKind kind, String target, String literal)
        {
            if (kind == null || kind.isGuard()) throw new ServiceException("取值条件必须携带常量，请使用 guard 声明保护条件");
            List<String> dependencies = new ArrayList<>();
            for (PredicateKind guard : kind.requiredGuards()) dependencies.add(ensureGuard(guard, target));
            String id = uniqueId(target + "#" + kind);
            predicates.add(PredicatePlan.create(id, kind, target, literal, dependencies, predicates.size()));
            return this;
        }

        /** 保护条件也要登记自身的保护依赖（类型判定先要非空），保证顺序始终是合法拓扑序。 */
        private String ensureGuard(PredicateKind kind, String target)
        {
            String key = target + "#" + kind;
            String existing = guards.get(key);
            if (existing != null) return existing;
            List<String> dependencies = new ArrayList<>();
            for (PredicateKind required : kind.requiredGuards()) dependencies.add(ensureGuard(required, target));
            String id = uniqueId(key);
            predicates.add(PredicatePlan.create(id, kind, target, null, dependencies, predicates.size()));
            guards.put(key, id);
            return id;
        }

        private String uniqueId(String base)
        {
            int count = occurrences.containsKey(base) ? occurrences.get(base) + 1 : 1;
            occurrences.put(base, count);
            return count == 1 ? base : base + "#" + count;
        }

        public CompoundCheckPlan build()
        {
            CompoundCheckPlan plan = new CompoundCheckPlan(checkId, ruleName, field, dimension, templateCode, reason,
                    comparisonValue, predicates, originalOrder());
            return plan;
        }

        private List<String> originalOrder()
        {
            List<String> order = new ArrayList<>(predicates.size());
            for (PredicatePlan predicate : predicates) order.add(predicate.getPredicateId());
            return order;
        }
    }
}
