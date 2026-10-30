package com.ruoyi.dqservice.l4;

import com.ruoyi.dqservice.template.QualityDimension;
import com.ruoyi.dqservice.template.plan.CompoundCheckPlan;
import com.ruoyi.dqservice.template.plan.PlanOptions;
import com.ruoyi.dqservice.template.plan.PredicateKind;
import com.ruoyi.dqservice.template.plan.PredicatePlan;
import com.ruoyi.dqservice.util.RuleParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * L4 原型固定夹具。
 *
 * 样例是「指定业务类别 + 待审核状态 + 编码保护 + 格式不匹配」的复合违规，
 * 另外附带有限数值比较与枚举条件。所有数据由固定算式构造，不依赖机器速度。
 */
public final class CompoundFixtures
{
    public static final Instant DETECTED_AT = Instant.parse("2026-10-30T00:00:00Z");

    public static final String CODE_PATTERN = "^[A-Z]{2}-[0-9]{4}$";

    public static final String CODE_CHECK_ID = "l4-contract-code";

    public static final String AMOUNT_CHECK_ID = "l4-amount-limit";

    public static final String GUARD_CHAIN_CHECK_ID = "l4-guard-chain";

    public static final String EXPENSIVE_CHECK_ID = "l4-expensive-profile";

    public static final String PHONE_PATTERN = "^1[3-9][0-9]{9}$";

    /** 200 字符备注格式：线性匹配，不使用可回溯的嵌套量词。 */
    public static final String NOTE_PATTERN = "^[A-Za-z0-9][A-Za-z0-9 ;:/\\-]{0,198}$";

    /** 受控地区字典：30 项，模拟真实业务里的大枚举条件。 */
    public static final String[] REGIONS = { "北京", "上海", "广东", "江苏", "浙江", "山东", "河南", "四川",
            "湖北", "湖南", "福建", "安徽", "河北", "陕西", "江西", "重庆", "辽宁", "云南",
            "广西", "山西", "内蒙古", "贵州", "新疆", "甘肃", "海南", "宁夏", "青海", "西藏",
            "吉林", "黑龙江" };

    private CompoundFixtures()
    {
    }

    /**
     * 原始合法顺序刻意让格式检查紧跟在编码保护之后：
     * category 保护 → code 保护 → 正则不匹配 → 类别相等 → status 保护 + 相等。
     * 规划器应当把廉价的类别条件提到昂贵正则之前。
     */
    public static CompoundCheckPlan codeFormatPlan()
    {
        CompoundCheckPlan.Builder builder = CompoundCheckPlan
                .builder(CODE_CHECK_ID, "业务编码格式", "code", QualityDimension.VALIDITY.name())
                .reason("待审核业务记录编码不符合约定格式")
                .comparison(CODE_PATTERN)
                .guard(PredicateKind.NOT_NULL, "category")
                .guard(PredicateKind.IS_TEXT, "category")
                .guard(PredicateKind.NOT_NULL, "code")
                .guard(PredicateKind.IS_TEXT, "code");
        builder.predicate(PredicateKind.REGEX_NOT_MATCH, "code", CODE_PATTERN);
        builder.predicate(PredicateKind.TEXT_EQ, "category", "AUDIT");
        builder.predicate(PredicateKind.TEXT_EQ, "status", "PENDING");
        return builder.build();
    }

    /** 既包含有限数值比较，也包含枚举条件。 */
    public static CompoundCheckPlan amountPlan()
    {
        return CompoundCheckPlan
                .builder(AMOUNT_CHECK_ID, "金额上限", "amount", QualityDimension.VALIDITY.name())
                .reason("受控类别金额超过约定上限")
                .comparison("1000")
                .predicate(PredicateKind.NUMBER_GT, "amount", "1000")
                .predicate(PredicateKind.TEXT_IN, "category", "A,B,C")
                .build();
    }

    /** 非空 → 正则的短保护链；顺序不可交换，因此没有排序空间。 */
    public static CompoundCheckPlan guardChainPlan()
    {
        return CompoundCheckPlan
                .builder(GUARD_CHAIN_CHECK_ID, "编码非空后校验格式", "code", QualityDimension.VALIDITY.name())
                .reason("编码缺失或格式不符")
                .comparison(CODE_PATTERN)
                .notMatches(CODE_PATTERN)
                .build();
    }

    /**
     * 编码类数据：10% 类别为 AUDIT，50% 状态为 PENDING，约 1/7 编码格式不合法，
     * 三者由互质步长构造，近似独立。
     */
    public static List<Map<String, Object>> codeRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("category", i % 10 == 0 ? "AUDIT" : "OTHER");
            row.put("status", (i / 10) % 2 == 0 ? "PENDING" : "DONE");
            row.put("code", i % 7 == 0 ? "BAD-CODE" : String.format(Locale.ROOT, "AB-%04d", i % 10000));
            rows.add(row);
        }
        return rows;
    }

    /** 金额类数据：一半在受控类别，一半金额超限。 */
    public static List<Map<String, Object>> amountRows(int count)
    {
        String[] categories = { "A", "B", "C", "D" };
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("category", categories[i % categories.length]);
            row.put("amount", new BigDecimal(i % 4 == 0 ? "1500.50" : "800.25"));
            row.put("code", String.format(Locale.ROOT, "AB-%04d", i % 10000));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 高成本复合计划：15 个谓词，含 160 字符备注正则、两个短正则与 30 项枚举。
     *
     * 原始顺序模拟规则编辑器的通常产出：先把各字段的非空/类型保护成块前置，再按字段顺序写入
     * 业务条件；廉价且筛选力高的 {@code tier = GOLD}（仅 20% 命中）与待审核状态排在各正则之后，
     * 因此最贵的长文本正则每行都要执行。
     */
    public static CompoundCheckPlan expensivePlan()
    {
        CompoundCheckPlan.Builder builder = CompoundCheckPlan
                .builder(EXPENSIVE_CHECK_ID, "会员资料合规", "tier", QualityDimension.VALIDITY.name())
                .reason("黄金等级会员的编码、备注格式、受控地区或待审核状态不合规")
                .comparison("GOLD")
                .guard(PredicateKind.NOT_NULL, "code")
                .guard(PredicateKind.IS_TEXT, "code")
                .guard(PredicateKind.NOT_NULL, "note")
                .guard(PredicateKind.IS_TEXT, "note")
                .guard(PredicateKind.NOT_NULL, "region")
                .guard(PredicateKind.IS_TEXT, "region")
                .guard(PredicateKind.NOT_NULL, "status")
                .guard(PredicateKind.IS_TEXT, "status")
                .guard(PredicateKind.NOT_NULL, "tier")
                .guard(PredicateKind.IS_TEXT, "tier");
        builder.predicate(PredicateKind.REGEX_NOT_MATCH, "code", CODE_PATTERN);
        builder.predicate(PredicateKind.REGEX_NOT_MATCH, "note", NOTE_PATTERN);
        builder.predicate(PredicateKind.TEXT_IN, "region", String.join(",", REGIONS));
        builder.predicate(PredicateKind.TEXT_EQ, "status", "PENDING");
        builder.textEquals("tier", "GOLD");
        return builder.build();
    }

    /**
     * 高成本夹具：按互质模数注入各类缺陷，使各条件近似独立。
     *
     * 20% 为 GOLD，编码 1/2 格式不符，备注仅 1/20 不合规（但每行都要跑完 160 字符正则），
     * 地区 1/11 为字典外取值，待审核状态占 2/3。五个条件同时成立的概率约为 0.3%，
     * 既保证违规数可观测，也保证“昂贵但不过滤”的备注正则对排序收益具有决定性。
     */
    public static List<Map<String, Object>> expensiveRows(int count)
    {
        List<Map<String, Object>> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(RuleParser.ROW_ID_KEY, i + 1);
            row.put("tier", i % 5 == 0 ? "GOLD" : "BASIC");
            row.put("code", i % 2 == 0 ? "BAD-CODE" : String.format(Locale.ROOT, "AB-%04d", i % 10000));
            row.put("note", note(i));
            row.put("region", i % 11 == 0 ? "UNKNOWN" : REGIONS[i % REGIONS.length]);
            row.put("status", i % 3 == 0 ? "DONE" : "PENDING");
            rows.add(row);
        }
        return rows;
    }

    /** 备注约 160 字符；每 20 行含一个字符类外的 #，使长文本正则不匹配。 */
    private static String note(int index)
    {
        char[] body = new char[150];
        Arrays.fill(body, 'x');
        String text = "note-" + index % 1000 + "-" + new String(body);
        return index % 20 == 0 ? text.replace('x', '#') : text;
    }

    /** 固定成本表：正则最贵，保护条件最便宜，与机器速度无关。 */
    public static double cost(PredicateKind kind)
    {
        switch (kind)
        {
            case NOT_NULL:
            case IS_TEXT:
                return 10;
            case IS_NUMBER:
                return 20;
            case TEXT_EQ:
            case TEXT_NE:
                return 20;
            case TEXT_IN:
            case TEXT_NOT_IN:
                return 50;
            case NUMBER_GT:
            case NUMBER_LT:
            case NUMBER_EQ:
            case NUMBER_NE:
            case NUMBER_GE:
            case NUMBER_LE:
                return 30;
            default:
                return 1000;
        }
    }

    public static PlanOptions.Builder fixedOptions(CompoundCheckPlan plan)
    {
        PlanOptions.Builder builder = PlanOptions.builder();
        for (PredicatePlan predicate : plan.getPredicates())
            builder.fixedCost(predicate.getPredicateId(), cost(predicate.getKind()));
        return builder;
    }

    public static PlanOptions options(CompoundCheckPlan plan)
    {
        return fixedOptions(plan).build();
    }

    /** 明细多重集比较：去掉聚合阶段附加的原始行引用，其余键值逐项对齐。 */
    public static List<String> canonical(List<Map<String, Object>> details)
    {
        List<String> lines = new ArrayList<>(details.size());
        for (Map<String, Object> detail : details) lines.add(render(detail, true));
        Collections.sort(lines);
        return lines;
    }

    private static String render(Object value, boolean skipRow)
    {
        if (value instanceof Map)
        {
            StringBuilder text = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet())
            {
                if (skipRow && "row".equals(entry.getKey())) continue;
                text.append(entry.getKey()).append('=').append(render(entry.getValue(), false)).append(',');
            }
            return text.append('}').toString();
        }
        if (value instanceof List)
        {
            StringBuilder text = new StringBuilder("[");
            for (Object item : (List<?>) value) text.append(render(item, false)).append(',');
            return text.append(']').toString();
        }
        return String.valueOf(value);
    }

    public static Map<String, Object> rowOf(Object... pairs)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) row.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return row;
    }
}
