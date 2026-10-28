package com.ruoyi.dqservice.mapper;

import java.util.List;
import com.ruoyi.dqservice.domain.DataSource;

/**
 * 数据源Mapper接口
 *
 * @author sch
 * @date 2022-05-24
 */
public interface DataSourceMapper
{
    /**
     * 查询数据源
     *
     * @param id 数据源主键
     * @return 数据源
     */
    public DataSource selectDataSourceById(Integer id);

    /**
     * 查询数据源列表
     *
     * @param dataSource 数据源
     * @return 数据源集合
     */
    public List<DataSource> selectDataSourceList(DataSource dataSource);

    /**
     * 新增数据源
     *
     * @param dataSource 数据源
     * @return 结果
     */
    public int insertDataSource(DataSource dataSource);

    /**
     * 修改数据源
     *
     * @param dataSource 数据源
     * @return 结果
     */
    public int updateDataSource(DataSource dataSource);

    /**
     * 删除数据源
     *
     * @param id 数据源主键
     * @return 结果
     */
    public int deleteDataSourceById(Integer id);

    /**
     * 批量删除数据源
     *
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteDataSourceByIds(Integer[] ids);
}
