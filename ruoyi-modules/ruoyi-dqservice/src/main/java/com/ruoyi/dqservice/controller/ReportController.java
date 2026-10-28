package com.ruoyi.dqservice.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.dqservice.domain.ExecutionRecord;
import com.ruoyi.dqservice.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质量报告Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/report")
public class ReportController extends BaseController
{
    @Autowired
    private ReportService reportService;

    /**
     * 查询执行记录列表
     */
    @RequiresPermissions("dqservice:report:list")
    @GetMapping("/list")
    public TableDataInfo list(ExecutionRecord query)
    {
        startPage();
        List<ExecutionRecord> list = reportService.selectExecutionList(query);
        return getDataTable(list);
    }

    /**
     * 查询执行记录详情，包含违规明细与字段分布
     */
    @RequiresPermissions("dqservice:report:list")
    @GetMapping("/{executionId}")
    public AjaxResult getInfo(@PathVariable("executionId") Long executionId)
    {
        return AjaxResult.success(reportService.selectExecutionDetail(executionId));
    }
}
