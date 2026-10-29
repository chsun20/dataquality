package com.ruoyi.dqservice.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import com.ruoyi.dqservice.mapper.RuleSourceRefMapper;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.template.TemplateCheck;
import com.ruoyi.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;
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
    @Autowired private RuleSourceRefMapper references;
    @Autowired private DataSourceMapper sources;

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
    @Transactional(rollbackFor = Exception.class)
    public int insertRule(Rule rule)
    {
        Set<Integer> dependencies = lockSources(rule);
        Date now = DateUtils.getNowDate();
        rule.setCreateTime(now);
        rule.setUpdateTime(now);
        int changed = ruleMapper.insertRule(rule);
        for (Integer id : dependencies) references.add(rule.getRuleId(), id);
        return changed;
    }

    /**
     * 修改规则
     * 
     * @param rule 规则
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateRule(Rule rule)
    {
        if (references.lockRule(rule.getRuleId()) == null) throw new ServiceException("规则已删除");
        Rule dependencyRule = rule.getTemplateParams() == null ? ruleMapper.selectRuleByRuleId(rule.getRuleId()) : rule;
        Set<Integer> dependencies = lockSources(dependencyRule);
        rule.setUpdateTime(DateUtils.getNowDate());
        int changed = ruleMapper.updateRule(rule);
        references.clear(rule.getRuleId());
        for (Integer id : dependencies) references.add(rule.getRuleId(), id);
        return changed;
    }

    /**
     * 批量删除规则
     * 
     * @param ruleIds 需要删除的规则主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteRuleByRuleIds(Long[] ruleIds)
    {
        for (Long id : Arrays.stream(ruleIds).distinct().sorted().toArray(Long[]::new))
        {
            references.lockRule(id);
            references.clear(id);
        }
        return ruleMapper.deleteRuleByRuleIds(ruleIds);
    }

    /**
     * 删除规则信息
     * 
     * @param ruleId 规则主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteRuleByRuleId(Long ruleId)
    {
        return deleteRuleByRuleIds(new Long[]{ruleId});
    }

    private Set<Integer> lockSources(Rule rule)
    {
        Set<Integer> ids = TemplateCheck.dependencies(rule);
        for (Integer id : ids)
        {
            com.ruoyi.dqservice.domain.DataSource source = sources.lockById(id);
            if (source == null || source.getHost() == null || !Boolean.TRUE.equals(source.getEnabled()))
                throw new ServiceException("规则引用的数据源已删除、停用或未配置");
        }
        return ids;
    }
}
