package com.ruoyi.dqservice.mapper;

import com.ruoyi.dqservice.domain.ExecutionViolation;

import java.util.List;

/**
 * 违规明细Mapper接口
 *
 * @author ruoyi
 */
public interface ExecutionViolationMapper
{
    /** 批量新增违规明细 */
    int insertViolationBatch(List<ExecutionViolation> violations);

    /** 按执行批次查询违规明细 */
    List<ExecutionViolation> selectViolationByExecutionId(Long executionId);

    /** 按执行批次批量删除违规明细 */
    int deleteViolationByExecutionIds(Long[] executionIds);
}
