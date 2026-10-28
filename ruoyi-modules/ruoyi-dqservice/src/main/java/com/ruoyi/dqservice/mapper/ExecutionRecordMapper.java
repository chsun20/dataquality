package com.ruoyi.dqservice.mapper;

import com.ruoyi.dqservice.domain.ExecutionRecord;

import java.util.List;

/**
 * 执行记录Mapper接口
 *
 * @author ruoyi
 */
public interface ExecutionRecordMapper
{
    /** 新增执行记录 */
    int insertExecutionRecord(ExecutionRecord record);

    /** 按主键查询执行记录 */
    ExecutionRecord selectExecutionRecordById(Long executionId);

    /** 查询执行记录列表 */
    List<ExecutionRecord> selectExecutionRecordList(ExecutionRecord query);

    /** 批量删除执行记录 */
    int deleteExecutionRecordByIds(Long[] executionIds);
}
