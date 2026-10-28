package com.ruoyi.dqservice.controller;

import java.util.List;
import java.io.IOException;
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

    /**
     * 查询数据源列表
     */
    @RequiresPermissions("dqservice:dataSource:list")
    @GetMapping("/list")
    public TableDataInfo list(DataSource dataSource)
    {
        startPage();
        List<DataSource> list = dataSourceService.selectDataSourceList(dataSource);
        return getDataTable(list);
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
        return AjaxResult.success(dataSourceService.selectDataSourceById(id));
    }

    /**
     * 新增数据源
     */
    @RequiresPermissions("dqservice:dataSource:add")
    @Log(title = "数据源", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DataSource dataSource)
    {
        return toAjax(dataSourceService.insertDataSource(dataSource));
    }

    /**
     * 修改数据源
     */
    @RequiresPermissions("dqservice:dataSource:edit")
    @Log(title = "数据源", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DataSource dataSource)
    {
        return toAjax(dataSourceService.updateDataSource(dataSource));
    }

    /**
     * 删除数据源
     */
    @RequiresPermissions("dqservice:dataSource:remove")
    @Log(title = "数据源", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Integer[] ids)
    {
        return toAjax(dataSourceService.deleteDataSourceByIds(ids));
    }
}
