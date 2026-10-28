package com.ruoyi.dqservice.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.template.RuleTemplateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规则模板Controller
 *
 * 为前端「规则制定」向导提供模板清单与 DRL 预览。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/rule/template")
public class RuleTemplateController extends BaseController
{
    @Autowired
    private RuleTemplateService templateService;

    /**
     * 查询全部规则模板（含参数定义）
     */
    @GetMapping("/list")
    public AjaxResult list()
    {
        return AjaxResult.success(templateService.listTemplates());
    }

    /**
     * 根据当前选择的字段与模板预览生成的 DRL，不落库
     */
    @PostMapping("/preview")
    public AjaxResult preview(@RequestBody Rule rule)
    {
        // 包一层 Map 返回，避免 AjaxResult.success(String) 被当成提示语
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("drl", templateService.preview(rule));
        return AjaxResult.success(data);
    }
}
