package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.template.RuleTemplateService;
import com.ruoyi.dqservice.template.TemplateCheck;

/** 接口权限预校验；失败不产生执行报告。 */
public final class RuleSourceAccess
{
    private RuleSourceAccess() { }

    public static void templates(Rule rule)
    {
        AuthUtil.checkPermi("dqservice:dataSource:metadata");
        boolean external = rule.getSourceId() != null;
        if (rule.getCols() != null)
            for (ColInfo col : rule.getCols())
                if (col.getRules() != null)
                    for (String code : col.getRules())
                        external |= "referenceExists".equals(code) || "referenceMatch".equals(code);
        if (external) AuthUtil.checkPermi("dqservice:dataSource:use");
    }

    public static void custom(Rule rule)
    {
        AuthUtil.checkPermi("dqservice:rule:custom");
        if (rule.getSourceId() != null) throw new ServiceException("外部数据源只允许模板生成的规则");
    }

    public static void execution(Rule rule)
    {
        if (rule == null) throw new ServiceException("规则不存在");
        if (RuleTemplateService.CUSTOM.equals(rule.getTemplateCode()) || rule.getTemplateCode() == null
                || rule.getTemplateParams() == null || rule.getTemplateParams().isEmpty()) custom(rule);
        if (!TemplateCheck.dependencies(rule).isEmpty()) AuthUtil.checkPermi("dqservice:dataSource:use");
    }
}
