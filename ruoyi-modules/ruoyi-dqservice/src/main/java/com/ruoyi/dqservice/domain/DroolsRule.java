package com.ruoyi.dqservice.domain;

import lombok.Data;

@Data
// 用于构建drools工作空间
public class DroolsRule {
    private Long ruleId;

    private String kieBaseName;

    private String kiePackageName;

    private String ruleContent;

    public DroolsRule(Rule rule) {
        this.ruleId = rule.getRuleId();
        this.kieBaseName = rule.getUserName() + "kieBase";
        this.kiePackageName = "com." + rule.getUserName() + "." + rule.getRuleName();
        this.ruleContent = rule.getRuleContext();
    }

    public void validate() {
        if (this.ruleId == null || isBlank(kieBaseName) || isBlank(kiePackageName) || isBlank(ruleContent)) {
            throw new RuntimeException("参数有问题");
        }
    }

    private boolean isBlank(String str) {
        return str == null || str.isEmpty();
    }
}
