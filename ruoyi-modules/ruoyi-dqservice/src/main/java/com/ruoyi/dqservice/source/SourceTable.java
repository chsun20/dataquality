package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class SourceTable
{
    private String databaseName;
    private String tableName;
    private List<Column> columns = new ArrayList<>();
    private List<Index> indexes = new ArrayList<>();

    public Column column(String name)
    {
        SourcePolicy.identifier(name);
        return columns.stream().filter(c -> c.getName().equals(name)).findFirst()
                .orElseThrow(() -> new ServiceException("字段不存在或结构已变更：" + name));
    }

    public List<String> primaryKey()
    {
        return indexes.stream().filter(i -> "PRIMARY".equals(i.getName()))
                .findFirst().map(Index::getColumns).orElseGet(ArrayList::new);
    }

    public boolean indexed(String field, boolean unique)
    {
        return indexes.stream().anyMatch(i -> !i.getColumns().isEmpty()
                && i.getColumns().get(0).equals(field) && !i.isPrefix()
                && (!unique || (i.isUnique() && i.getColumns().size() == 1)));
    }

    @Data
    public static class Column
    {
        private String name;
        private String type;
        private String comment;
        private boolean nullable;
    }

    @Data
    public static class Index
    {
        private String name;
        private boolean unique;
        private boolean prefix;
        private List<String> columns = new ArrayList<>();
    }
}
