package com.ruoyi.dqservice.template;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.source.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 保存、预览及执行共用真实元数据校验，不相信浏览器提交的字段类型。 */
@Service
public class RuleConfigurationValidator
{
    @Autowired private MysqlSourceClient mysql;
    @Autowired private RuleTemplateRegistry registry;

    public Map<String, Map<String, String>> prepare(Rule rule)
    {
        List<TemplateCheck> checks = new ArrayList<>();
        for (ColInfo col : rule.getCols())
        {
            if (col.getRules() == null) continue;
            for (String code : col.getRules())
            {
                TemplateCheck c = new TemplateCheck();
                c.setField(col.getName());
                c.setTemplate(code);
                c.setParams(new LinkedHashMap<>(col.paramsOf(code)));
                c.setCheckId(ContextRuleTemplate.checkId(col.getName(), code));
                checks.add(c);
            }
        }
        boolean external = rule.getSourceId() != null || checks.stream().anyMatch(TemplateCheck::isReference);
        try (MysqlSourceClient.Session session = mysql.session(external))
        {
            SourceTable table = validate(session, rule, checks, false);
            Map<String, Map<String, String>> types = new HashMap<>();
            for (ColInfo col : rule.getCols()) col.setType(table.column(col.getName()).getType());
            for (TemplateCheck check : checks) types.put(check.getCheckId(), check.getFieldTypes());
            return types;
        }
    }

    public SourceTable validate(MysqlSourceClient.Session session, Rule rule, List<TemplateCheck> checks, boolean execution)
    {
        SourceTable source = session.table(rule.getSourceId(), rule.getDbName(), rule.getTableName());
        rule.setDbName(source.getDatabaseName());
        rule.setSourceName(rule.getSourceId() == null ? "内置本地数据源" : session.config(rule.getSourceId()).getName());
        Set<Set<String>> composites = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (TemplateCheck c : checks)
        {
            RuleTemplate template = registry.get(c.getTemplate());
            if (template == null) throw new ServiceException("未知的规则模板");
            ContextRuleTemplate.require(ids.add(c.getCheckId()), "同一字段的模板不能重复配置");
            Map<String, String> p = c.getParams();
            Set<String> allowed = new HashSet<>();
            template.getParams().forEach(param -> allowed.add(param.getKey()));
            ContextRuleTemplate.require(allowed.containsAll(p.keySet()), "模板包含不支持的参数，请重新选择模板");
            String type = source.column(c.getField()).getType();
            if (execution && c.getType() != null && !c.getType().equalsIgnoreCase(type))
                throw new ServiceException("检测字段类型已变更，请重新保存规则参数");
            c.setType(type);
            Map<String, String> types = new LinkedHashMap<>();
            types.put("source:" + c.getField(), type);
            if (template instanceof ContextRuleTemplate) ((ContextRuleTemplate) template).validate(c.getField(), type, p);
            if ("compositeUnique".equals(c.getTemplate()))
            {
                List<String> fields = ContextRuleTemplate.fields(p);
                ContextRuleTemplate.require(composites.add(new HashSet<>(fields)), "相同组合字段集合只允许配置一次");
                for (String field : fields)
                {
                    String fieldType = source.column(field).getType();
                    ContextRuleTemplate.require(!"OTHER".equals(QualityTypes.kind(fieldType)), "组合唯一仅支持文本、数值和日期时间字段");
                    types.put("source:" + field, fieldType);
                }
            }
            if ("fieldCompare".equals(c.getTemplate()))
            {
                String otherType = source.column(p.get("otherField")).getType();
                compatible(type, otherType);
                types.put("source:" + p.get("otherField"), otherType);
            }
            if (c.isReference())
            {
                int id = ContextRuleTemplate.referenceSource(p);
                SourceTable reference = session.table(id, null, p.get("referenceTable"));
                boolean match = "referenceMatch".equals(c.getTemplate());
                String sourceKey = match ? p.get("sourceKey") : c.getField();
                String keyType = source.column(sourceKey).getType();
                String refType = reference.column(p.get("referenceKey")).getType();
                compatible(keyType, refType);
                ContextRuleTemplate.require(Arrays.asList("TEXT", "NUMBER").contains(QualityTypes.kind(keyType)), "参考业务键只支持文本或数值");
                ContextRuleTemplate.require(reference.indexed(p.get("referenceKey"), match),
                        match ? "参考键必须为单字段主键或完整唯一索引" : "参考字段必须为完整索引的首列");
                types.put("source:" + sourceKey, keyType);
                types.put("reference:key", refType);
                if (match)
                {
                    String refValueType = reference.column(p.get("referenceValue")).getType();
                    compatible(type, refValueType);
                    types.put("reference:value", refValueType);
                }
            }
            if (execution && c.getFieldTypes() != null && !c.getFieldTypes().isEmpty() && !types.equals(c.getFieldTypes()))
                throw new ServiceException("相关字段结构已变更，请重新保存规则参数");
            c.setFieldTypes(types);
        }
        return source;
    }

    private void compatible(String a, String b)
    {
        ContextRuleTemplate.require(!"OTHER".equals(QualityTypes.kind(a)) && QualityTypes.kind(a).equals(QualityTypes.kind(b)),
                "字段类型不兼容，禁止隐式转换文本数字");
    }
}
