package com.ruoyi.dqservice.service.impl;

import java.util.List;
import com.ruoyi.common.core.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.service.IDataSourceService;

/**
 * 数据源Service业务层处理
 *
 * @author sch
 * @date 2022-05-24
 */
@Service
public class DataSourceServiceImpl implements IDataSourceService
{
    @Autowired
    private DataSourceMapper dataSourceMapper;

    /**
     * 查询数据源
     *
     * @param id 数据源主键
     * @return 数据源
     */
    @Override
    public DataSource selectDataSourceById(Integer id)
    {
        return dataSourceMapper.selectDataSourceById(id);
    }

    /**
     * 查询数据源列表
     *
     * @param dataSource 数据源
     * @return 数据源
     */
    @Override
    public List<DataSource> selectDataSourceList(DataSource dataSource)
    {
        return dataSourceMapper.selectDataSourceList(dataSource);
    }

    /**
     * 新增数据源
     *
     * @param dataSource 数据源
     * @return 结果
     */
    @Override
    public int insertDataSource(DataSource dataSource)
    {
        return dataSourceMapper.insertDataSource(dataSource);
    }

    /**
     * 修改数据源
     *
     * @param dataSource 数据源
     * @return 结果
     */
    @Override
    public int updateDataSource(DataSource dataSource)
    {
        dataSource.setUpdateTime(DateUtils.getNowDate());
        return dataSourceMapper.updateDataSource(dataSource);
    }

    /**
     * 批量删除数据源
     *
     * @param ids 需要删除的数据源主键
     * @return 结果
     */
    @Override
    public int deleteDataSourceByIds(Integer[] ids)
    {
        return dataSourceMapper.deleteDataSourceByIds(ids);
    }

    /**
     * 删除数据源信息
     *
     * @param id 数据源主键
     * @return 结果
     */
    @Override
    public int deleteDataSourceById(Integer id)
    {
        return dataSourceMapper.deleteDataSourceById(id);
    }
}
