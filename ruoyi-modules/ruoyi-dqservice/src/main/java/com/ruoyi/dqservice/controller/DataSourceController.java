package com.ruoyi.dqservice.controller;

import java.util.List;
import java.util.stream.Collectors;
import com.ruoyi.dqservice.source.*;
import org.springframework.web.bind.annotation.RequestParam;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.log.annotation.Log;
import com.ruoyi.common.log.enums.BusinessType;
import com.ruoyi.common.security.annotation.RequiresPermissions;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.service.IDataSourceService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.utils.poi.ExcelUtil;
import com.ruoyi.common.core.web.page.TableDataInfo;

/**
 * 数据源Controller
 *
 * @author sch
 * @date 2022-05-24
 */
@RestController
@RequestMapping("/dataSource")
public class DataSourceController extends BaseController
{
    @Autowired
    private IDataSourceService dataSourceService;
    @Autowired private SourceManagementService management;
    @Autowired private MysqlSourceClient mysql;

    /**
     * 查询数据源列表
     */
    @RequiresPermissions("dqservice:dataSource:list")
    @GetMapping("/list")
    public TableDataInfo list(DataSource dataSource)
    {
        startPage();
        List<DataSource> list = dataSourceService.selectDataSourceList(dataSource);
        TableDataInfo result = getDataTable(list);
        result.setRows(list.stream().map(SourceView::new).collect(Collectors.toList()));
        return result;
    }

    /**
     * 导出数据源列表
     */
    @RequiresPermissions("dqservice:dataSource:export")
    @Log(title = "数据源", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DataSource dataSource)
    {
        List<DataSource> list = dataSourceService.selectDataSourceList(dataSource);
        ExcelUtil<DataSource> util = new ExcelUtil<DataSource>(DataSource.class);
        util.exportExcel(response, list, "数据源数据");
    }

    /**
     * 获取数据源详细信息
     */
    @RequiresPermissions("dqservice:dataSource:query")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Integer id)
    {
        return AjaxResult.success(management.get(id));
    }

    /**
     * 新增数据源
     */
    @RequiresPermissions("dqservice:dataSource:add")
    @Log(title = "数据源", businessType = BusinessType.INSERT, isSaveRequestData = false, isSaveResponseData = false)
    @PostMapping
    public AjaxResult add(@RequestBody SourceRequest dataSource)
    {
        return toAjax(management.save(dataSource, false));
    }

    /**
     * 修改数据源
     */
    @RequiresPermissions("dqservice:dataSource:edit")
    @Log(title = "数据源", businessType = BusinessType.UPDATE, isSaveRequestData = false, isSaveResponseData = false)
    @PutMapping
    public AjaxResult edit(@RequestBody SourceRequest dataSource)
    {
        return toAjax(management.save(dataSource, true));
    }

    /**
     * 删除数据源
     */
    @RequiresPermissions("dqservice:dataSource:remove")
    @Log(title = "数据源", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(management.remove(ids));
    }

    @RequiresPermissions("dqservice:dataSource:use")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return AjaxResult.success(management.options());
    }

    @RequiresPermissions("dqservice:dataSource:test")
    @PostMapping("/test")
    public AjaxResult test(@RequestBody SourceRequest request)
    {
        management.testDraft(request);
        return AjaxResult.success("连接测试成功（未保存）");
    }

    @RequiresPermissions("dqservice:dataSource:test")
    @PostMapping("/{id}/test")
    public AjaxResult testSaved(@PathVariable Integer id)
    {
        management.testSaved(id);
        return AjaxResult.success("连接测试成功");
    }

    @RequiresPermissions("dqservice:dataSource:metadata")
    @GetMapping("/tables")
    public AjaxResult tables(@RequestParam(required = false) Integer sourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize)
    {
        if (sourceId != null) com.ruoyi.common.security.auth.AuthUtil.checkPermi("dqservice:dataSource:use");
        try (MysqlSourceClient.Session session = mysql.session(sourceId != null))
        {
            return AjaxResult.success(session.listTables(sourceId, databaseName, search, pageNum, pageSize));
        }
    }

    @RequiresPermissions("dqservice:dataSource:metadata")
    @GetMapping("/columns")
    public AjaxResult columns(@RequestParam(required = false) Integer sourceId,
            @RequestParam(required = false) String databaseName, @RequestParam String tableName)
    {
        if (sourceId != null) com.ruoyi.common.security.auth.AuthUtil.checkPermi("dqservice:dataSource:use");
        try (MysqlSourceClient.Session session = mysql.session(sourceId != null))
        {
            return AjaxResult.success(session.table(sourceId, databaseName, tableName));
        }
    }

    @RequiresPermissions("dqservice:dataSource:preview")
    @GetMapping("/preview")
    public AjaxResult preview(@RequestParam(required = false) Integer sourceId,
            @RequestParam(required = false) String databaseName, @RequestParam String tableName,
            @RequestParam(defaultValue = "20") int limit)
    {
        if (sourceId != null) com.ruoyi.common.security.auth.AuthUtil.checkPermi("dqservice:dataSource:use");
        try (MysqlSourceClient.Session session = mysql.session(sourceId != null))
        {
            return AjaxResult.success(session.scan(sourceId, session.table(sourceId, databaseName, tableName), Math.min(100, limit)));
        }
    }
}
