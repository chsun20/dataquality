package com.ruoyi.dqservice.template;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.ruoyi.dqservice.util.RuleParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则模板服务：模板清单、DRL 组装、配置持久化与回显。
 *
 * 一条规则可以组合多个字段与多个模板，例如：
 * age 配「数值区间校验」，phone 同时配「空值检测」+「正则格式校验」。
 * 组装结果是一个完整 DRL，仍然只占 rule 表的一行记录。
 *
 * @author ruoyi
 */
@Service
public class RuleTemplateService
{
    private static final Logger log = LoggerFactory.getLogger(RuleTemplateService.class);

    /** 规则使用了多个模板时的 template_code 取值 */
    public static final String MIXED = "mixed";

    /** 手写 DRL 的规则类型编码 */
    public static final String CUSTOM = "custom";

    /** 组合规则的类型名称 */
    public static final String MIXED_NAME = "组合规则";

    /** 自定义规则的类型名称 */
    public static final String CUSTOM_NAME = "自定义规则";

    /** 统计 DRL 中的规则块数量 */
    private static final Pattern RULE_BLOCK = Pattern.compile("(?m)^[ \\t]*rule[ \\t]+\\\"");

    /** 手写 DRL 中回填违规字段的写法：v.put("field", "xxx") */
    private static final Pattern FIELD_WRITE = Pattern.compile("put\\(\\s*\\\"field\\\"\\s*,\\s*\\\"([^\\\"]+)\\\"");

    @Autowired
    private RuleTemplateRegistry registry;

    @Autowired
    private DroolsManager droolsManager;

    @Autowired
    private RuleConfigurationValidator configurationValidator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 模板清单，供前端动态渲染规则选择与参数表单 */
    public List<Map<String, Object>> listTemplates()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RuleTemplate template : registry.all())
        {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", template.getCode());
            item.put("name", template.getName());
            item.put("dimension", template.getDimension());
            item.put("dimensionCode", template.getQualityDimension().name());
            item.put("params", template.getParams());
            item.put("contextTemplate", template instanceof ContextRuleTemplate);
            result.add(item);
        }
        return result;
    }

    /** 维度定义与当前内置模板覆盖情况，不产生未检测维度的评分 */
    public List<Map<String, Object>> listDimensions()
    {
        List<Map<String, Object>> result = new ArrayList<>();
        for (QualityDimension dimension : QualityDimension.values())
        {
            List<String> templates = new ArrayList<>();
            for (RuleTemplate template : registry.all())
            {
                if (template.getQualityDimension() == dimension)
                {
                    templates.add(template.getName());
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", dimension.name());
            item.put("name", dimension.getLabel());
            item.put("description", dimension.getDescription());
            item.put("templates", templates);
            result.add(item);
        }
        return result;
    }

    /** 只生成不落库，用于前端预览 DRL */
    public String preview(Rule rule)
    {
        build(rule);
        return rule.getRuleContext();
    }

    /**
     * 根据 rule.cols 生成 DRL，并回填 columnName / templateCode / templateParams。
     */
    public void build(Rule rule)
    {
        ColInfo[] cols = rule.getCols();
        if (cols == null || cols.length == 0)
        {
            throw new ServiceException("请先选择需要检测的字段与规则模板");
        }
        Map<String, Map<String, String>> fieldTypes = configurationValidator == null
                ? java.util.Collections.emptyMap() : configurationValidator.prepare(rule);
        String ruleName = rule.getRuleName() == null || rule.getRuleName().trim().isEmpty()
                ? "dq_rule" : rule.getRuleName().trim();

        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        List<String> detectedColumns = new ArrayList<>();
        Set<String> usedCodes = new LinkedHashSet<>();
        List<Map<String, Object>> configs = new ArrayList<>();

        for (ColInfo col : cols)
        {
            String field = col.getName();
            if (field == null || field.trim().isEmpty())
            {
                continue;
            }
            field = field.trim();
            String[] selected = col.getRules();
            if (selected == null || selected.length == 0)
            {
                continue;
            }
            boolean detected = false;
            Set<String> seen = new LinkedHashSet<>();
            for (String code : selected)
            {
                if (code == null || code.trim().isEmpty() || !seen.add(code.trim()))
                {
                    continue;
                }
                String templateCode = code.trim();
                RuleTemplate template = registry.get(templateCode);
                if (template == null)
                {
                    throw new ServiceException("未知的规则模板：" + templateCode);
                }
                Map<String, String> params = col.paramsOf(templateCode);
                String checkId = ContextRuleTemplate.checkId(field, templateCode);
                String block = template.buildRule(ruleName, field, col.getType(), params);
                if (!(template instanceof ContextRuleTemplate))
                {
                    String metadata = "v.put(\"checkId\", \"" + checkId + "\"); "
                            + "v.put(\"templateCode\", \"" + templateCode + "\"); "
                            + "v.put(\"dimensionCode\", \"" + template.getQualityDimension().name() + "\"); "
                            + "v.put(\"resultType\", \"VIOLATION\"); violations.add(v);";
                    block = block.replace("violations.add(v);", metadata);
                }
                drl.append(block);
                usedCodes.add(templateCode);
                Map<String, Object> config = configOf(field, templateCode, params);
                config.put("version", 2);
                config.put("checkId", checkId);
                config.put("type", col.getType());
                config.put("fieldTypes", fieldTypes.getOrDefault(checkId, java.util.Collections.emptyMap()));
                configs.add(config);
                detected = true;
            }
            if (detected)
            {
                detectedColumns.add(field);
            }
        }

        if (detectedColumns.isEmpty())
        {
            throw new ServiceException("请至少为一个字段选择规则模板");
        }
        rule.setRuleContext(drl.toString());
        rule.setColumnName(String.join(",", detectedColumns));
        if (usedCodes.size() == 1)
        {
            String code = usedCodes.iterator().next();
            rule.setTemplateCode(code);
            rule.setTypeCode(code);
            rule.setTypeName(registry.get(code).getName());
        }
        else
        {
            rule.setTemplateCode(MIXED);
            rule.setTypeCode(MIXED);
            rule.setTypeName(MIXED_NAME);
        }
        rule.setTemplateParams(writeConfigs(configs));
        fillDimensions(rule);
    }

    /**
     * 手写 DRL 保存：补全执行契约、清空模板配置，规则类型记为自定义规则。
     *
     * @return 归一化后的完整 DRL
     */
    public String applyCustom(Rule rule)
    {
        String drl = validate(rule.getRuleContext());
        rule.setRuleContext(drl);
        String fields = detectFields(drl);
        if (!fields.isEmpty())
        {
            // 从 v.put("field", ...) 中回填检测字段，列表与执行报告都能看到字段归属
            rule.setColumnName(fields);
        }
        rule.setTemplateCode(CUSTOM);
        rule.setTemplateParams("");
        rule.setTypeCode(CUSTOM);
        rule.setTypeName(CUSTOM_NAME);
        rule.setQualityDimensions(new ArrayList<>());
        return drl;
    }

    /** 外部源和新版配置只执行注册模板重新生成的内容，不信任库内任意 DRL。 */
    public String executionDrl(Rule rule, List<TemplateCheck> checks)
    {
        StringBuilder drl = new StringBuilder(DrlSupport.HEADER);
        for (TemplateCheck check : checks)
        {
            RuleTemplate template = registry.get(check.getTemplate());
            if (template == null) throw new ServiceException("未知的规则模板");
            String expectedId = ContextRuleTemplate.checkId(check.getField(), check.getTemplate());
            if (!expectedId.equals(check.getCheckId())) throw new ServiceException("检查标识与字段模板不匹配，请重新保存参数");
            String block = template.buildRule(rule.getRuleName(), check.getField(), check.getType(), check.getParams());
            if (!(template instanceof ContextRuleTemplate))
                block = block.replace("violations.add(v);", "v.put(\"checkId\", \"" + expectedId
                        + "\"); v.put(\"templateCode\", \"" + template.getCode()
                        + "\"); v.put(\"dimensionCode\", \"" + template.getQualityDimension().name()
                        + "\"); v.put(\"resultType\", \"VIOLATION\"); violations.add(v);");
            drl.append(block);
        }
        return drl.toString();
    }

    /** 提取手写 DRL 中写出的违规字段，保持出现顺序并去重 */
    private String detectFields(String drl)
    {
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = FIELD_WRITE.matcher(drl);
        while (matcher.find())
        {
            fields.add(matcher.group(1));
        }
        return String.join(",", fields);
    }

    /**
     * 语法检查：补全头部并编译，返回归一化后的完整 DRL。
     */
    public String validate(String drl)
    {
        String normalized = DrlSupport.normalize(drl);
        DrlSupport.requireViolations(normalized);
        droolsManager.verifyDrl(normalized);
        return normalized;
    }

    /**
     * 列表与详情展示的规则类型统一以模板注册表为准，
     * 历史规则未记模板编码时根据 DRL 内容推断。
     */
    public void decorate(List<Rule> rules)
    {
        if (rules == null)
        {
            return;
        }
        for (Rule rule : rules)
        {
            decorate(rule);
        }
    }

    public void decorate(Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        fillDimensions(rule);
        String code = rule.getTemplateCode();
        if (code != null && registry.contains(code))
        {
            rule.setTypeName(registry.get(code).getName());
            rule.setTypeCode(code);
            return;
        }
        if (MIXED.equals(code))
        {
            rule.setTypeName(MIXED_NAME);
            rule.setTypeCode(MIXED);
            return;
        }
        if (CUSTOM.equals(code))
        {
            rule.setTypeName(CUSTOM_NAME);
            rule.setTypeCode(CUSTOM);
            return;
        }
        // 历史数据：根据 DRL 推断是否只有空值检测
        String inferred = inferTemplate(rule.getRuleContext());
        rule.setTypeName(inferred == null ? CUSTOM_NAME : registry.get(inferred).getName());
        rule.setTypeCode(inferred == null ? CUSTOM : inferred);
    }

    /** 类型编码保持兼容，维度独立回填；组合规则按模板配置去重 */
    private void fillDimensions(Rule rule)
    {
        Set<String> dimensions = new LinkedHashSet<>();
        rule.setQualityDimensions(new ArrayList<>());
        String code = rule.getTemplateCode();
        if (CUSTOM.equals(code))
        {
            return;
        }
        if (MIXED.equals(code))
        {
            try
            {
                List<Map<String, Object>> configs = objectMapper.readValue(rule.getTemplateParams(),
                        new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> config : configs)
                {
                    RuleTemplate template = registry.get(asText(config.get("template")));
                    if (template == null)
                    {
                        return;
                    }
                    dimensions.add(template.getDimension());
                }
            }
            catch (Exception e)
            {
                log.warn("规则 {} 的维度无法从模板配置识别", rule.getRuleId());
                return;
            }
        }
        else
        {
            RuleTemplate template = registry.get(code);
            if (template == null && (code == null || code.trim().isEmpty()))
            {
                template = registry.get(inferTemplate(rule.getRuleContext()));
            }
            if (template != null)
            {
                dimensions.add(template.getDimension());
            }
        }
        rule.setQualityDimensions(new ArrayList<>(dimensions));
    }

    /** 推断历史规则使用的模板，无法判定时返回 null */
    private String inferTemplate(String drl)
    {
        if (drl == null || drl.trim().isEmpty())
        {
            return null;
        }
        Matcher matcher = RULE_BLOCK.matcher(drl);
        int blocks = 0;
        while (matcher.find())
        {
            blocks++;
        }
        int nullColumns = RuleParser.parseNullColumns(drl).size();
        if (blocks > 0 && blocks == nullColumns)
        {
            return "nullCount";
        }
        return null;
    }

    /**
     * 读取规则时回填 cols（字段 + 模板 + 参数）。
     *
     * 优先使用 template_params 中保存的配置；历史规则没有该字段时，
     * 退化为从 DRL 中解析空值检测条件，保证旧数据仍可编辑。
     */
    public void fillCols(Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        String json = rule.getTemplateParams();
        if (json != null && !json.trim().isEmpty())
        {
            try
            {
                List<Map<String, Object>> configs = objectMapper.readValue(json,
                        new TypeReference<List<Map<String, Object>>>()
                        {
                        });
                Map<String, ColInfo> byField = new LinkedHashMap<>();
                for (Map<String, Object> config : configs)
                {
                    String field = asText(config.get("field"));
                    String template = asText(config.get("template"));
                    if (field == null || template == null)
                    {
                        continue;
                    }
                    ColInfo col = byField.get(field);
                    if (col == null)
                    {
                        col = new ColInfo();
                        col.setName(field);
                        col.setType(asText(config.get("type")));
                        byField.put(field, col);
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = (Map<String, String>) config.get("params");
                    col.addRule(template, params);
                }
                if (!byField.isEmpty())
                {
                    rule.setCols(byField.values().toArray(new ColInfo[0]));
                    return;
                }
            }
            catch (Exception e)
            {
                log.warn("规则 {} 的模板配置解析失败，回退为解析 DRL：{}", rule.getRuleId(), e.getMessage());
            }
        }
        RuleParser.fillDetectColumns(rule);
    }

    private Map<String, Object> configOf(String field, String template, Map<String, String> params)
    {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("field", field);
        config.put("template", template);
        config.put("params", params == null ? new LinkedHashMap<String, String>() : params);
        return config;
    }

    private String writeConfigs(List<Map<String, Object>> configs)
    {
        try
        {
            return objectMapper.writeValueAsString(configs);
        }
        catch (Exception e)
        {
            throw new ServiceException("规则模板配置序列化失败：" + e.getMessage());
        }
    }

    private static String asText(Object value)
    {
        if (value == null)
        {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
