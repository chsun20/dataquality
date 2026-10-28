package com.ruoyi.dqservice.controller;


import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.DroolsRule;
import com.ruoyi.dqservice.service.DroolsRuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/drools/rule")
public class DroolsRuleController {
    @Autowired
    private DroolsRuleService droolsRuleService;

    @Resource
    private DroolsManager droolsManager;

    @GetMapping("findAll")
    public List<DroolsRule> findAll() {
        return droolsRuleService.findAll();
    }

    @PostMapping("add")
    public String addRule(@RequestBody DroolsRule droolsRule) {
        droolsRuleService.addDroolsRule(droolsRule);
        return "添加成功";
    }

    @PostMapping("update")
    public String updateRule(@RequestBody DroolsRule droolsRule) {
        droolsRuleService.updateDroolsRule(droolsRule);
        return "修改成功";
    }

    @PostMapping("deleteRule")
    public String deleteRule(Long ruleId, String ruleName) {
        droolsRuleService.deleteDroolsRule(ruleId, ruleName);
        return "删除成功";
    }

    @GetMapping("fireRule")
    public String fireRule(String kieBaseName, Integer param) {
        return droolsManager.fireRule(kieBaseName, param);
    }
}
