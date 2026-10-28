package com.ruoyi.dqservice.template;

import java.util.List;
import java.util.Map;

/**
 * 规则模板：把「一类质量约束」抽象成可配置定义。
 *
 * 每个实现负责两件事：
 * 1. 声明自己需要哪些参数（供前端动态渲染表单）；
 * 2. 根据字段名 + 参数生成一段符合 Drools 契约的 rule 块。
 *
 * 新增一种规则类型只需新增一个实现类并标注 @Component，
 * 注册表会自动收集，前端无需改动。
 *
 * @author ruoyi
 */
public interface RuleTemplate
{
    /** 模板编码，前端提交时放在 cols[].rules 中 */
    String getCode();

    /** 模板名称 */
    String getName();

    /** 所属质量维度（完整性 / 有效性 / 一致性 / 时效性 ...） */
    String getDimension();

    /** 参数定义，无参数时返回空集合 */
    List<TemplateParam> getParams();

    /**
     * 生成单个字段的 rule 块。
     *
     * @param ruleName   规则名称，用于 rule "..." 以及违规明细中的 rule 字段
     * @param field      被检测的字段名
     * @param columnType 字段数据类型（来自元数据，可能为空）
     * @param params     用户填写的参数，未填的键不会出现
     * @return 形如 rule "xxx" when ... then ... end 的文本
     */
    String buildRule(String ruleName, String field, String columnType, Map<String, String> params);
}
