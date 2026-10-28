package com.ruoyi.dqservice.controller;

import java.util.List;
import java.util.Map;

import com.ruoyi.dqservice.service.HiveMetaStoreService;

import org.apache.hadoop.hive.metastore.api.Table;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/metadata/hive")
public class HiveMetaStoreController {

    @Autowired
    @Qualifier(value = "mysqlMetaStore")
    private HiveMetaStoreService hiveMetaStoreService;

    @RequestMapping(value = "/dbs", method = RequestMethod.GET)
    public Iterable<String> getAllDatabases() {
        return hiveMetaStoreService.getAllDatabases();
    }


    @RequestMapping(value = "/tables/names", method = RequestMethod.GET)
    public Iterable<String> getAllTableNames(@RequestParam("db") String dbName) {
        return hiveMetaStoreService.getAllTableNames(dbName);
    }

    @RequestMapping(value = "/tables", method = RequestMethod.GET)
    public List<Table> getAllTables(@RequestParam("db") String dbName) {
        return hiveMetaStoreService.getAllTable(dbName);
    }

    @RequestMapping(value = "/dbs/tables", method = RequestMethod.GET)
    public Map<String, List<Table>> getAllTables() {
        return hiveMetaStoreService.getAllTable();
    }

    @RequestMapping(value = "/dbs/tables/names", method = RequestMethod.GET)
    public Map<String, List<String>> getAllTableNames() {
        return hiveMetaStoreService.getAllTableNames();
    }

    @RequestMapping(value = "/table", method = RequestMethod.GET)
    public Table getTable(@RequestParam("db") String dbName,
                          @RequestParam("table") String tableName) {
        return hiveMetaStoreService.getTable(dbName, tableName);
    }


}
