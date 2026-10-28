package com.ruoyi.dqservice.service;

import com.ruoyi.dqservice.domain.DroolsRule;

import java.util.List;

public interface DroolsRuleService {
    List<DroolsRule> findAll();

    void addDroolsRule(DroolsRule droolsRule);

    void updateDroolsRule(DroolsRule droolsRule);

    void deleteDroolsRule(Long ruleId, String ruleName);
}
