package com.ruoyi.dqservice.service.impl;

import java.util.Date;
import java.util.List;
import com.ruoyi.common.core.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.service.IRuleService;

/**
 * 规则Service业务层处理
 * 
 * @author ruoyi
 * @date 2022-10-26
 */
@Service
public class RuleServiceImpl implements IRuleService 
{
    @Autowired
    private RuleMapper ruleMapper;

    /**
     * 查询规则
     * 
     * @param ruleId 规则主键
     * @return 规则
     */
    @Override
    public Rule selectRuleByRuleId(Long ruleId)
    {
        return ruleMapper.selectRuleByRuleId(ruleId);
    }

    /**
     * 查询规则列表
     * 
     * @param rule 规则
     * @return 规则
     */
    @Override
    public List<Rule> selectRuleList(Rule rule)
    {
        return ruleMapper.selectRuleList(rule);
    }

    /**
     * 新增规则
     * 
     * @param rule 规则
     * @return 结果
     */
    @Override
    public int insertRule(Rule rule)
    {
        Date now = DateUtils.getNowDate();
        rule.setCreateTime(now);
        rule.setUpdateTime(now);
        return ruleMapper.insertRule(rule);
    }

    /**
     * 修改规则
     * 
     * @param rule 规则
     * @return 结果
     */
    @Override
    public int updateRule(Rule rule)
    {
        rule.setUpdateTime(DateUtils.getNowDate());
        return ruleMapper.updateRule(rule);
    }

    /**
     * 批量删除规则
     * 
     * @param ruleIds 需要删除的规则主键
     * @return 结果
     */
    @Override
    public int deleteRuleByRuleIds(Long[] ruleIds)
    {
        return ruleMapper.deleteRuleByRuleIds(ruleIds);
    }

    /**
     * 删除规则信息
     * 
     * @param ruleId 规则主键
     * @return 结果
     */
    @Override
    public int deleteRuleByRuleId(Long ruleId)
    {
        return ruleMapper.deleteRuleByRuleId(ruleId);
    }
}
