package com.ruoyi.dqservice.service;

import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.metastore.api.Table;

public interface HiveMetaStoreService {

    Iterable<String> getAllDatabases();

    Iterable<String> getAllTableNames(String dbName);

    Map<String, List<String>> getAllTableNames();

    List<Table> getAllTable(String db);

    Map<String, List<Table>> getAllTable();

    Table getTable(String dbName, String tableName);

    void evictHiveCache();
}
