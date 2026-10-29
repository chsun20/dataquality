package com.ruoyi.dqservice.mapper;

import org.apache.ibatis.annotations.*;

public interface RuleSourceRefMapper
{
    @Select("SELECT rule_id FROM `rule` WHERE rule_id = #{id} FOR UPDATE")
    Long lockRule(Long id);

    @Delete("DELETE FROM dq_rule_source_ref WHERE rule_id = #{id}")
    int clear(Long id);

    @Insert("INSERT INTO dq_rule_source_ref(rule_id, source_id) VALUES(#{ruleId}, #{sourceId})")
    int add(@Param("ruleId") Long ruleId, @Param("sourceId") Integer sourceId);
}
