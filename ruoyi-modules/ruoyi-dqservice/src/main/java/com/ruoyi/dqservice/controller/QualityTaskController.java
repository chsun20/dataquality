package com.ruoyi.dqservice.controller;

import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.web.page.TableDataInfo;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.common.security.auth.AuthUtil;
import com.ruoyi.dqservice.domain.QualityTask;
import com.ruoyi.dqservice.domain.QualityTaskRun;
import com.ruoyi.dqservice.service.QualityTaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 质控任务Controller
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/task")
public class QualityTaskController extends BaseController
{
    @Autowired
    private QualityTaskService taskService;

    /** 查询质控任务列表 */
    @RequiresPermissions("dqservice:task:list")
    @GetMapping("/list")
    public TableDataInfo list(QualityTask query)
    {
        startPage();
        List<QualityTask> list = taskService.selectList(query);
        return getDataTable(list);
    }

    /** 查询任务详情与已绑定规则 */
    @RequiresPermissions("dqservice:task:query")
    @GetMapping("/{taskId}")
    public AjaxResult getInfo(@PathVariable Long taskId)
    {
        return AjaxResult.success(taskService.selectById(taskId));
    }

    /** 查询指定数据源与表下可绑定的规则 */
    @RequiresPermissions("dqservice:task:query")
    @GetMapping("/candidates")
    public AjaxResult candidates(@RequestParam(required = false) Integer sourceId,
                                 @RequestParam String tableName)
    {
        return AjaxResult.success(taskService.candidates(sourceId, tableName));
    }

    /** 新增质控任务 */
    @RequiresPermissions("dqservice:task:add")
    @Log(title = "质控任务", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody QualityTask task)
    {
        return toAjax(taskService.insert(task));
    }

    /** 修改质控任务 */
    @RequiresPermissions("dqservice:task:edit")
    @Log(title = "质控任务", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody QualityTask task)
    {
        return toAjax(taskService.update(task));
    }

    /** 删除质控任务 */
    @RequiresPermissions("dqservice:task:remove")
    @Log(title = "质控任务", businessType = BusinessType.DELETE)
    @DeleteMapping("/{taskIds}")
    public AjaxResult remove(@PathVariable Long[] taskIds)
    {
        return toAjax(taskService.delete(taskIds));
    }

    /** 启用或停用定时调度 */
    @RequiresPermissions("dqservice:task:edit")
    @Log(title = "质控任务调度", businessType = BusinessType.UPDATE)
    @PutMapping("/changeStatus")
    public AjaxResult changeStatus(@RequestBody QualityTask task)
    {
        return toAjax(taskService.changeStatus(task.getTaskId(), task.getEnabled(), task.getCronExpression()));
    }

    /** 手动触发一次任务，异步执行并立即返回运行批次 */
    @RequiresPermissions("dqservice:task:execute")
    @Log(title = "质控任务执行", businessType = BusinessType.OTHER)
    @PostMapping("/{taskId}/run")
    public AjaxResult run(@PathVariable Long taskId)
    {
        AuthUtil.checkPermi("dqservice:rule:execute");
        QualityTask task = taskService.selectById(taskId);
        if (task.getSourceId() != null)
        {
            AuthUtil.checkPermi("dqservice:dataSource:use");
        }
        return AjaxResult.success(taskService.runNow(taskId));
    }

    /** 查询任务运行批次 */
    @RequiresPermissions("dqservice:task:run")
    @GetMapping("/runs")
    public TableDataInfo runs(QualityTaskRun query)
    {
        startPage();
        List<QualityTaskRun> list = taskService.selectRuns(query);
        return getDataTable(list);
    }

    /** 查询运行批次详情与各规则报告 */
    @RequiresPermissions("dqservice:task:run")
    @GetMapping("/run/{runId}")
    public AjaxResult runDetail(@PathVariable Long runId)
    {
        return AjaxResult.success(taskService.selectRunDetail(runId));
    }

    /** 删除运行批次记录 */
    @RequiresPermissions("dqservice:task:remove")
    @Log(title = "质控任务运行记录", businessType = BusinessType.DELETE)
    @DeleteMapping("/run/{runIds}")
    public AjaxResult removeRuns(@PathVariable Long[] runIds)
    {
        return toAjax(taskService.deleteRuns(runIds));
    }
}
