package com.ruoyi.dqservice.mapper;

import java.util.List;
import com.ruoyi.dqservice.domain.Rule;

/**
 * 规则Mapper接口
 * 
 * @author ruoyi
 * @date 2022-10-26
 */
public interface RuleMapper 
{
    /**
     * 查询规则
     * 
     * @param ruleId 规则主键
     * @return 规则
     */
    public Rule selectRuleByRuleId(Long ruleId);

    /**
     * 查询规则列表
     * 
     * @param rule 规则
     * @return 规则集合
     */
    public List<Rule> selectRuleList(Rule rule);

    /**
     * 新增规则
     * 
     * @param rule 规则
     * @return 结果
     */
    public int insertRule(Rule rule);

    /**
     * 修改规则
     * 
     * @param rule 规则
     * @return 结果
     */
    public int updateRule(Rule rule);

    /**
     * 删除规则
     * 
     * @param ruleId 规则主键
     * @return 结果
     */
    public int deleteRuleByRuleId(Long ruleId);

    /**
     * 批量删除规则
     * 
     * @param ruleIds 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteRuleByRuleIds(Long[] ruleIds);
}
