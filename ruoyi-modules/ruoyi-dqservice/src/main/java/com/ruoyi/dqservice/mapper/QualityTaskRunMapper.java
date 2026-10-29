package com.ruoyi.dqservice.mapper;

import com.ruoyi.dqservice.domain.QualityTaskRun;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 质控任务运行批次Mapper接口
 *
 * @author ruoyi
 */
public interface QualityTaskRunMapper
{
    /** 创建运行批次，RUNNING 时 running_key 等于 task_id 用于防并发 */
    int insertRun(QualityTaskRun run);

    /** 结束运行批次并释放并发占位 */
    int finishRun(QualityTaskRun run);

    /** 按主键查询运行批次 */
    QualityTaskRun selectRunById(Long runId);

    /** 查询运行批次列表 */
    List<QualityTaskRun> selectRunList(QualityTaskRun query);

    /** 服务重启时把遗留的 RUNNING 批次标记为失败 */
    int failInterruptedRuns(@Param("before") Date before, @Param("message") String message);

    /** 批量删除运行批次 */
    int deleteRunByIds(Long[] runIds);
}
