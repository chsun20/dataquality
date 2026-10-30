package com.ruoyi.dqservice.service;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.common.core.utils.DateUtils;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.domain.ExecutionViolation;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.mapper.ExecutionRecordMapper;
import com.ruoyi.dqservice.mapper.ExecutionViolationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.ruoyi.dqservice.util.QualityJson;

/**
 * 质量报告服务：执行结果落库与查询。
 *
 * @author ruoyi
 */
@Service
@Slf4j
public class ReportService
{
    /** 与 dq_violation 字段长度保持一致 */
    private static final int LEN_RECORD_ID = 64;

    private static final int LEN_FIELD_NAME = 64;

    private static final int LEN_RULE_NAME = 128;

    private static final int LEN_FIELD_VALUE = 255;

    @Autowired
    private ExecutionRecordMapper executionRecordMapper;

    @Autowired
    private ExecutionViolationMapper executionViolationMapper;

    /**
     * 保存一次执行结果，返回执行批次ID。
     * 批次与明细在同一事务内写入，避免出现只有汇总没有明细的报告。
     *
     * @param rule          执行的规则
     * @param tableName     实际扫描的数据表
     * @param totalRows     扫描行数
     * @param violationRows 命中违规的行数
     * @param passRate      通过率
     * @param costMs        执行耗时
     * @param violations    Drools 产出的违规明细
     * @return 执行批次ID
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveExecution(Rule rule, String tableName, int totalRows, int violationRows,
                              Double passRate, long costMs, List<Map<String, Object>> violations)
    {
        ExecutionRecord record = new ExecutionRecord();
        record.setRuleId(rule.getRuleId());
        record.setRuleName(rule.getRuleName());
        record.setDbName(rule.getDbName());
        record.setTableName(tableName);
        record.setTotalRows(totalRows);
        record.setViolationCount(violations.size());
        record.setViolationRows(violationRows);
        record.setPassRate(passRate);
        record.setCostMs(costMs);
        record.setCreateBy(currentUsername());
        record.setCreateTime(DateUtils.getNowDate());
        executionRecordMapper.insertExecutionRecord(record);

        Long executionId = record.getExecutionId();
        insertDetails(executionId, violations);
        log.info("执行结果已保存: executionId={} 规则[{}] 违规{}条",
                executionId, rule.getRuleName(), violations.size());
        return executionId;
    }

    /** 新执行入口：状态、快照和全部结果明细同一事务保存。 */
    @Transactional(rollbackFor = Exception.class)
    public Long saveEvaluation(ExecutionRecord record, List<Map<String, Object>> results)
    {
        if (!"SUCCESS".equals(record.getEvaluationStatus())) record.setPassRate(null);
        if (record.getCreateBy() == null || record.getCreateBy().trim().isEmpty()) record.setCreateBy(currentUsername());
        record.setCreateTime(DateUtils.getNowDate());
        executionRecordMapper.insertExecutionRecord(record);
        insertDetails(record.getExecutionId(), results);
        log.info("质控报告已保存：批次={}，状态={}", record.getExecutionId(), record.getEvaluationStatus());
        return record.getExecutionId();
    }

    /** 仅保留当前批的转换实体；所有批次仍处于调用方同一事务中。 */
    private void insertDetails(Long executionId, List<Map<String, Object>> results)
    {
        List<ExecutionViolation> batch = new ArrayList<>(200);
        for (Map<String, Object> result : results)
        {
            batch.add(toViolation(executionId, result));
            if (batch.size() == 200)
            {
                executionViolationMapper.insertViolationBatch(batch);
                batch = new ArrayList<>(200);
            }
        }
        if (!batch.isEmpty()) executionViolationMapper.insertViolationBatch(batch);
    }

    /** 查询执行记录列表 */
    public List<ExecutionRecord> selectExecutionList(ExecutionRecord query)
    {
        List<ExecutionRecord> records = executionRecordMapper.selectExecutionRecordList(query);
        records.forEach(this::normalizeEmptyReport);
        return records;
    }

    /** 查询执行记录详情：汇总 + 违规明细 + 字段分布 */
    public ExecutionRecord selectExecutionDetail(Long executionId)
    {
        ExecutionRecord record = executionRecordMapper.selectExecutionRecordById(executionId);
        if (record == null)
        {
            throw new ServiceException("执行记录不存在：" + executionId);
        }
        List<ExecutionViolation> violations = executionViolationMapper.selectViolationByExecutionId(executionId);
        record.setViolations(violations.stream().filter(v -> !"UNVERIFIABLE".equals(v.getResultType())).collect(Collectors.toList()));
        record.setUnverifiable(violations.stream().filter(v -> "UNVERIFIABLE".equals(v.getResultType())).collect(Collectors.toList()));
        record.setFieldStats(buildFieldStats(record.getViolations()));
        normalizeEmptyReport(record);
        return record;
    }

    /** 历史空表报告也按不评价展示，不修改数据库中的历史记录 */
    private void normalizeEmptyReport(ExecutionRecord record)
    {
        if (Integer.valueOf(0).equals(record.getTotalRows()) || "PARTIAL".equals(record.getEvaluationStatus())
                || "FAILED".equals(record.getEvaluationStatus()) || "NO_DATA".equals(record.getEvaluationStatus()))
        {
            record.setPassRate(null);
        }
    }

    /** Drools 产出的 Map 违规明细 -> 数据库实体 */
    private ExecutionViolation toViolation(Long executionId, Map<String, Object> violation)
    {
        ExecutionViolation detail = new ExecutionViolation();
        detail.setExecutionId(executionId);
        detail.setRowId(toInteger(violation.get("rowId")));
        detail.setFieldName(truncate(violation.get("field"), LEN_FIELD_NAME));
        detail.setRuleName(truncate(violation.get("rule"), LEN_RULE_NAME));
        detail.setCheckId(truncate(violation.get("checkId"), 64));
        detail.setTemplateCode(truncate(violation.get("templateCode"), 64));
        detail.setDimensionCode(truncate(violation.get("dimensionCode"), 32));
        detail.setResultType("UNVERIFIABLE".equals(violation.get("resultType")) ? "UNVERIFIABLE" : "VIOLATION");
        detail.setReason(truncate(violation.get("reason"), 255));
        detail.setComparisonValue(truncate(violation.get("comparisonValue"), 255));
        if (violation.get("involvedFields") instanceof Map)
        {
            Map<String, String> values = new LinkedHashMap<>();
            ((Map<?, ?>) violation.get("involvedFields")).forEach((k, v) -> values.put(String.valueOf(k), truncate(v, 255)));
            detail.setInvolvedFields(QualityJson.write(values));
        }

        Object rowObject = violation.get("row");
        if (rowObject instanceof Map)
        {
            Map<?, ?> row = (Map<?, ?>) rowObject;
            detail.setRecordId(truncate(row.get("id"), LEN_RECORD_ID));
            Object value = row.get(detail.getFieldName());
            detail.setFieldValue(truncate(value, LEN_FIELD_VALUE));
        }
        return detail;
    }

    private List<ExecutionRecord.FieldStat> buildFieldStats(List<ExecutionViolation> violations)
    {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (ExecutionViolation violation : violations)
        {
            String field = violation.getFieldName() == null ? "unknown" : violation.getFieldName();
            counter.merge(field, 1, Integer::sum);
        }
        List<ExecutionRecord.FieldStat> stats = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : counter.entrySet())
        {
            ExecutionRecord.FieldStat stat = new ExecutionRecord.FieldStat();
            stat.setField(entry.getKey());
            stat.setCount(entry.getValue());
            stats.add(stat);
        }
        stats.sort((a, b) -> b.getCount().compareTo(a.getCount()));
        return stats;
    }

    private Integer toInteger(Object value)
    {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private String truncate(Object value, int maxLength)
    {
        if (value == null)
        {
            return null;
        }
        String text = value.toString();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private String currentUsername()
    {
        try
        {
            return SecurityUtils.getUsername();
        }
        catch (Exception e)
        {
            // 内部调用或定时任务没有登录上下文，不影响报告落库
            return "system";
        }
    }
}
