package com.ruoyi.dqservice.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletResponse;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.dqservice.domain.DroolsRule;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.RuleTemplateService;
import com.ruoyi.dqservice.source.RuleSourceAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.service.IRuleService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 规则Controller
 *
 * @author ruoyi
 * @date 2022-10-26
 */
@RestController
@RequestMapping("/rule")
public class RuleController extends BaseController
{
    @Autowired
    private IRuleService ruleService;

    @Autowired
    private RuleTemplateService templateService;

    /**
     * 查询规则列表
     */
    @RequiresPermissions("dqservice:rule:list")
    @GetMapping("/list")
    public TableDataInfo list(Rule rule)
    {
        startPage();
        List<Rule> list = ruleService.selectRuleList(rule);
        // 规则类型名称统一按模板注册表展示，避免历史数据里 type_name 杂乱
        templateService.decorate(list);
        return getDataTable(list);
    }

    /**
     * 导出规则列表
     */
    @RequiresPermissions("dqservice:rule:export")
    @Log(title = "规则", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Rule rule)
    {
        List<Rule> list = ruleService.selectRuleList(rule);
        ExcelUtil<Rule> util = new ExcelUtil<Rule>(Rule.class);
        util.exportExcel(response, list, "规则数据");
    }

    /**
     * 获取规则详细信息
     */
    @RequiresPermissions("dqservice:rule:query")
    @GetMapping(value = "/{ruleId}")
    public AjaxResult getInfo(@PathVariable("ruleId") Long ruleId)
    {
        Rule rule = ruleService.selectRuleByRuleId(ruleId);
        if (rule != null)
        {
            templateService.decorate(rule);
            // 回填字段与模板配置，供编辑弹窗回显
            templateService.fillCols(rule);
        }
        return AjaxResult.success(rule);
    }

    /**
     * 新增规则
     */
    @RequiresPermissions("dqservice:rule:add")
    @Log(title = "规则", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody Rule rule)
    {
        rule.setUserId(SecurityUtils.getUserId());
        rule.setUserName(SecurityUtils.getUsername());
        if (isCustom(rule))
        {
            // 自定义规则：手写 DRL，补全头部后保存，规则类型记为自定义规则
            RuleSourceAccess.custom(rule);
            templateService.applyCustom(rule);
        }
        else
        {
            // 按所选模板生成 DRL，并回填字段名与模板配置
            RuleSourceAccess.templates(rule);
            templateService.build(rule);
        }
        return toAjax(ruleService.insertRule(rule));
    }

    /**
     * 修改规则
     *
     * 修改弹窗只编辑 DRL 主体，因此分三种情况：
     * 1. 主体未变 -> 只更新名称等元数据，规则类型保持原样
     * 2. 主体有改动 -> 收归为自定义规则，旧模板参数失效
     * 3. 仅传 cols（历史调用方式）-> 按最新字段与模板重建 DRL
     */
    @RequiresPermissions("dqservice:rule:edit")
    @Log(title = "规则", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Rule rule)
    {
        if (rule.getRuleId() == null)
        {
            return AjaxResult.error("规则ID不能为空");
        }
        Rule existing = ruleService.selectRuleByRuleId(rule.getRuleId());
        if (existing == null)
        {
            return AjaxResult.error("规则不存在：" + rule.getRuleId());
        }

        if (!RuleTemplateService.CUSTOM.equals(existing.getTemplateCode())
                && existing.getTemplateParams() != null && !existing.getTemplateParams().isEmpty())
        {
            if (rule.getCols() == null || rule.getCols().length == 0)
                return AjaxResult.error("模板规则请通过参数向导编辑，DRL 仅支持只读预览");
            RuleSourceAccess.templates(rule);
            templateService.build(rule);
            return toAjax(ruleService.updateRule(rule));
        }
        RuleSourceAccess.custom(rule);
        String submitted = rule.getRuleContext();
        if (submitted != null && !submitted.trim().isEmpty())
        {
            if (DrlSupport.body(submitted).equals(DrlSupport.body(existing.getRuleContext())))
            {
                // 内容没有实质变化：不动 DRL，也不改规则类型
                rule.setRuleContext(null);
                rule.setTemplateCode(null);
                rule.setTemplateParams(null);
                rule.setTypeCode(null);
                rule.setTypeName(null);
            }
            else
            {
                // 内容有改动：视为自定义规则
                templateService.applyCustom(rule);
            }
        }
        else if (rule.getCols() != null && rule.getCols().length > 0)
        {
            // 置空后由模板服务重新生成，保证 DRL 与最新选择一致
            rule.setRuleContext("");
            templateService.build(rule);
        }
        else
        {
            rule.setRuleContext(null);
        }
        return toAjax(ruleService.updateRule(rule));
    }

    /**
     * 校验手写 DRL 的语法，供「新增自定义规则」与「修改规则」弹窗使用
     */
    @RequiresPermissions("dqservice:rule:custom")
    @PostMapping("/validate")
    public AjaxResult validate(@RequestBody Rule rule)
    {
        // 包一层 Map 返回，避免 AjaxResult.success(String) 被当成提示语
        Map<String, Object> data = new LinkedHashMap<>();
        RuleSourceAccess.custom(rule);
        data.put("drl", templateService.validate(rule.getRuleContext()));
        return AjaxResult.success(data);
    }

    /**
     * 删除规则
     */
    @RequiresPermissions("dqservice:rule:remove")
    @Log(title = "规则", businessType = BusinessType.DELETE)
	@DeleteMapping("/{ruleIds}")
    public AjaxResult remove(@PathVariable Long[] ruleIds)
    {
        return toAjax(ruleService.deleteRuleByRuleIds(ruleIds));
    }

    /**
     * 是否为手写 DRL 的自定义规则：显式标记或未选字段但带 DRL 内容
     */
    private boolean isCustom(Rule rule)
    {
        if (RuleTemplateService.CUSTOM.equals(rule.getTemplateCode()))
        {
            return true;
        }
        boolean noCols = rule.getCols() == null || rule.getCols().length == 0;
        String drl = rule.getRuleContext();
        return noCols && drl != null && !drl.trim().isEmpty();
    }
}
