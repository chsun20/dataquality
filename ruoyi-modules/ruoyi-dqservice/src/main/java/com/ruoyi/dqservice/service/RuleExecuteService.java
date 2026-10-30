package com.ruoyi.dqservice.service;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.connecter.DroolsManager;
import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.source.*;
import com.ruoyi.dqservice.template.*;
import com.ruoyi.dqservice.util.QualityJson;
import java.time.Instant;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.stream.Collectors;
import com.ruoyi.dqservice.mapper.RuleMapper;
import com.ruoyi.dqservice.util.QualityStatistics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 规则执行服务
 *
 * 把「规则」和「数据」真正接通：
 * 1. 按规则绑定的表名从主数据源读取数据，每行转成 Map（字段名 -> 字段值）；
 * 2. 交给 DroolsManager 编译执行 DRL，回收 then 块产出的违规明细；
 * 3. 汇总成可直接展示的质量评估结果。
 *
 * @author ruoyi
 */
@Service
@Slf4j
public class RuleExecuteService
{
    /** 单次执行默认扫描行数，避免大表拖慢演示环境 */
    private static final int DEFAULT_LIMIT = 1000;

    /** 单次执行扫描行数上限 */
    private static final int MAX_LIMIT = 10000;

    @Autowired
    private RuleMapper ruleMapper;

    @Autowired
    private DroolsManager droolsManager;

    @Autowired private MysqlSourceClient mysql;
    @Autowired private RuleConfigurationValidator validator;
    @Autowired private RuleTemplateService templates;
    @Autowired private RuleTemplateRegistry registry;
    @Autowired private ReferenceLookupService references;

    @Autowired
    private ReportService reportService;

    /**
     * 执行指定规则
     *
     * @param ruleId 规则主键
     * @param limit  最多扫描的数据行数，为空时取默认值
     * @return 质量评估结果：扫描行数、违规条数、通过率、字段分布、违规明细
     */
    public Map<String, Object> execute(Long ruleId, Integer limit)
    {
        return execute(ruleId, limit, null, null, null);
    }

    /**
     * 任务触发入口：回填运行批次与任务ID；定时执行无登录上下文时由 operator 记录触发身份。
     */
    public Map<String, Object> execute(Long ruleId, Integer limit, Long runId, Long taskId, String operator)
    {
        Rule rule = ruleMapper.selectRuleByRuleId(ruleId);
        if (rule == null)
        {
            throw new ServiceException("规则不存在：ruleId=" + ruleId);
        }
        SourcePolicy.identifier(rule.getTableName());
        if (rule.getDbName() != null && !rule.getDbName().trim().isEmpty()) SourcePolicy.identifier(rule.getDbName());
        int maxRows = limit == null ? DEFAULT_LIMIT : limit;
        if (maxRows < 1 || maxRows > MAX_LIMIT) throw new ServiceException("扫描上限必须为 1～10000");
        List<TemplateCheck> checks = TemplateCheck.read(rule);
        boolean external = !TemplateCheck.dependencies(rule).isEmpty();
        boolean generated = !checks.isEmpty() && checks.stream().allMatch(c -> Objects.equals(2, c.getVersion()));
        if (external && (!generated || RuleTemplateService.CUSTOM.equals(rule.getTemplateCode())))
            throw new ServiceException("外部数据源只能执行通过参数向导保存的新版模板规则");
        String drl = generated ? templates.executionDrl(rule, checks) : rule.getRuleContext();
        if (drl == null || drl.trim().isEmpty()) throw new ServiceException("规则内容为空，无法执行");

        long started = System.nanoTime();
        Instant detectedAt = Instant.now();
        ExecutionRecord record = new ExecutionRecord();
        record.setRunId(runId);
        record.setTaskId(taskId);
        record.setCreateBy(operator);
        record.setRuleId(rule.getRuleId());
        record.setRuleName(rule.getRuleName());
        record.setDbName(rule.getDbName());
        record.setTableName(rule.getTableName());
        record.setSourceId(rule.getSourceId());
        record.setSourceName(rule.getSourceId() == null ? "内置本地数据源" : rule.getSourceName());
        record.setDetectedAt(detectedAt.toString());
        record.setTotalRows(0);
        record.setViolationRows(0);
        record.setViolationCount(0);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", 2);
        snapshot.put("checks", checks);
        snapshot.put("limit", maxRows);
        snapshot.put("referenceConsistency", "参考按需读取，不提供跨数据库一致性快照；演示数据不是权威依据");
        List<Map<String, Object>> results = Collections.emptyList();
        List<Map<String, Object>> violations = Collections.emptyList();
        List<Map<String, Object>> unverifiable = Collections.emptyList();
        List<Map<String, Object>> fieldStatistics = Collections.emptyList();
        long aggregateNs = 0;
        String stage = "数据源连接或元数据复验";
        // 参数与权限预校验已完成；此后所有运行失败均产生 FAILED 报告。
        try (MysqlSourceClient.Session session = mysql.session(external))
        {
            SourceTable table = validator.validate(session, rule, checks, true);
            record.setDbName(table.getDatabaseName());
            record.setSourceName(rule.getSourceName());
            snapshot.put("sourceConfigVersion", rule.getSourceId() == null ? null : session.config(rule.getSourceId()).getConfigVersion());
            record.setScanNote(table.primaryKey().isEmpty()
                    ? "仅检测本次最多 " + maxRows + " 行；无主键，扫描范围不可稳定复现；唯一性不代表全表"
                    : "按主键 " + String.join("、", table.primaryKey()) + " 排序，最多 " + maxRows + " 行；唯一性仅对本次扫描有效");
            stage = "源数据读取";
            List<Map<String, Object>> rows = session.scan(rule.getSourceId(), table, maxRows);
            record.setTotalRows(rows.size());
            // 保留历史本地 DRL 的 JDBC Date 事实契约；新版时间模板使用显式时区解释 LocalDateTime。
            if (!generated) for (Map<String, Object> row : rows)
                row.replaceAll((key, value) -> value instanceof LocalDateTime ? Timestamp.valueOf((LocalDateTime) value) : value);
            QualityExecutionContext context = new QualityExecutionContext(detectedAt, rows);
            List<TemplateCheck> legacyChecks = new ArrayList<>();
            if (generated) for (TemplateCheck check : checks)
            {
                RuleTemplate template = registry.get(check.getTemplate());
                context.register(check.getCheckId(), check.getTemplate(), check.getField(), template.getQualityDimension().name());
                if (!(template instanceof ContextRuleTemplate)) legacyChecks.add(check);
            }
            stage = "参考数据准备";
            snapshot.put("references", references.load(session, table, checks, rows, context));
            session.checkBudget();
            stage = "规则执行或结果校验";
            results = droolsManager.fireRules(drl, rows, context);
            long aggregateStarted = System.nanoTime();
            // 对所有明细规范化行号，无法核验也必须指向真实源记录。
            QualityStatistics.Summary summary = QualityStatistics.aggregate(results, rows,
                    legacyChecks.stream().map(TemplateCheck::getField).collect(Collectors.toSet()));
            violations = summary.getViolations();
            unverifiable = summary.getUnverifiable();
            fieldStatistics = summary.getFieldStats();
            for (TemplateCheck check : legacyChecks)
                context.countLegacyTemplate(check, registry.get(check.getTemplate()).getQualityDimension().name(), summary);
            record.setCheckCounts(generated ? QualityJson.write(context.getCounters()) : null);
            record.setViolationCount(violations.size());
            record.setViolationRows(summary.getViolationRows());
            aggregateNs = System.nanoTime() - aggregateStarted;
            record.setUnverifiableCount(unverifiable.size());
            record.setEvaluationStatus(rows.isEmpty() ? "NO_DATA" : unverifiable.isEmpty() ? "SUCCESS" : "PARTIAL");
            record.setPassRate("SUCCESS".equals(record.getEvaluationStatus())
                    ? QualityStatistics.passRate(rows.size(), record.getViolationRows()) : null);
        }
        catch (RuntimeException e)
        {
            // 不能把驱动异常、任意 DRL 异常或半批结果写入报告/日志。
            record.setEvaluationStatus("FAILED");
            record.setErrorMessage(stage + "失败；请检查连接启用状态、网络与权限、字段结构及规则参数。未完成评价。");
            record.setPassRate(null);
            record.setViolationCount(0);
            record.setViolationRows(0);
            record.setUnverifiableCount(0);
            record.setCheckCounts(null);
            results = violations = unverifiable = fieldStatistics = Collections.emptyList();
            log.warn("规则执行失败：ruleId={}，阶段={}", ruleId, stage);
        }
        record.setCostMs(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        record.setConfigSnapshot(QualityJson.write(snapshot));
        // 持久化失败单独向上传递，不得把未保存的报告包装为成功。
        Long executionId;
        long persistStarted = System.nanoTime();
        try
        {
            executionId = reportService.saveEvaluation(record, results);
        }
        finally
        {
            log.debug("质控执行阶段：ruleId={}，aggregate_ns={}，persist_ns={}，total_ns={}",
                    ruleId, aggregateNs, System.nanoTime() - persistStarted, System.nanoTime() - started);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executionId", executionId);
        result.put("ruleId", record.getRuleId());
        result.put("ruleName", record.getRuleName());
        result.put("dbName", record.getDbName());
        result.put("tableName", record.getTableName());
        result.put("sourceId", record.getSourceId());
        result.put("sourceName", record.getSourceName());
        result.put("totalRows", record.getTotalRows());
        result.put("violationCount", record.getViolationCount());
        result.put("violationRows", record.getViolationRows());
        result.put("unverifiableCount", record.getUnverifiableCount());
        result.put("passRate", record.getPassRate());
        result.put("costMs", record.getCostMs());
        result.put("evaluationStatus", record.getEvaluationStatus());
        result.put("errorMessage", record.getErrorMessage());
        result.put("detectedAt", record.getDetectedAt());
        result.put("scanNote", record.getScanNote());
        result.put("configSnapshot", record.getConfigSnapshot());
        result.put("checkCounts", record.getCheckCounts());
        result.put("fieldStats", fieldStatistics);
        result.put("violations", violations);
        result.put("unverifiable", unverifiable);
        return result;
    }

}
