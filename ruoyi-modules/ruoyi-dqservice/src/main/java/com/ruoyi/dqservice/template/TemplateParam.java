package com.ruoyi.dqservice.template;

import lombok.Data;

import java.util.List;

/**
 * 规则模板的参数定义。
 *
 * 前端拿到这个描述后动态渲染参数表单，无需为每个模板硬编码页面。
 *
 * @author ruoyi
 */
@Data
public class TemplateParam
{
    /** 参数键，也是生成 DRL 时取值的 key */
    private String key;

    /** 参数名称，展示用 */
    private String label;

    /** 控件类型：text / number / date / select */
    private String type = "text";

    /** 是否必填 */
    private boolean required;

    /** 默认值 */
    private String defaultValue;

    /** 输入提示 */
    private String placeholder;

    /** type=select 时的候选值 */
    private List<String> options;

    public TemplateParam()
    {
    }

    public TemplateParam(String key, String label, String type, boolean required, String placeholder)
    {
        this.key = key;
        this.label = label;
        this.type = type;
        this.required = required;
        this.placeholder = placeholder;
    }

    public TemplateParam withDefault(String value)
    {
        this.defaultValue = value;
        return this;
    }

    public TemplateParam withOptions(List<String> values)
    {
        this.type = "select";
        this.options = values;
        return this;
    }
}
