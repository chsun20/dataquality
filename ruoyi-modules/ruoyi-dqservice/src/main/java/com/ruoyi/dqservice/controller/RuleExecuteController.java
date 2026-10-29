package com.ruoyi.dqservice.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.dqservice.service.RuleExecuteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规则执行Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/execute")
public class RuleExecuteController extends BaseController
{
    @Autowired
    private RuleExecuteService ruleExecuteService;
    @Autowired private com.ruoyi.dqservice.service.IRuleService rules;

    /**
     * 执行指定规则，返回违规明细与通过率。
     * limit 可选，表示本次最多扫描的数据行数。
     */
    @RequiresPermissions("dqservice:rule:execute")
    @Log(title = "规则执行", businessType = BusinessType.OTHER)
    @PostMapping("/{ruleId}")
    public AjaxResult execute(@PathVariable("ruleId") Long ruleId,
                              @RequestParam(value = "limit", required = false) Integer limit)
    {
        com.ruoyi.dqservice.source.RuleSourceAccess.execution(rules.selectRuleByRuleId(ruleId));
        return AjaxResult.success(ruleExecuteService.execute(ruleId, limit));
    }
}
