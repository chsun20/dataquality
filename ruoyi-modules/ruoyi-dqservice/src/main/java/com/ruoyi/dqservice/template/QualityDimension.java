package com.ruoyi.dqservice.template;

/**
 * 统一的质量维度定义。维度不等于模板类型，也不代表该维度已经实现检测。
 */
public enum QualityDimension
{
    COMPLETENESS("完整性", "必填值是否缺失；当前空值模板只检测 NULL，不包含空字符串或纯空白字符串。"),
    VALIDITY("有效性", "字段值是否满足约定的格式、取值域、长度或范围；固定日期区间也属于有效性。"),
    UNIQUENESS("唯一性", "约定字段在检测范围内是否重复；同值组超过一条时，组内全部记录均违规。"),
    CONSISTENCY("一致性", "字段之间、表之间或系统之间是否满足约定的一致关系。"),
    TIMELINESS("时效性", "数据更新或到达是否满足相对检测时间的时限，不等同于固定日期区间。"),
    ACCURACY("准确性", "数据值是否符合权威参考或真实事实，不等同于格式合法或规则通过率。");

    private final String label;

    private final String description;

    QualityDimension(String label, String description)
    {
        this.label = label;
        this.description = description;
    }

    public String getLabel()
    {
        return label;
    }

    public String getDescription()
    {
        return description;
    }
}
