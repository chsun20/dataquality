package com.ruoyi.dqservice.service;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.jupiter.api.Assertions.*;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HiveMetaStoreServiceTest {
    @Autowired HiveMetaStoreService hiveMetaStoreService;

    @Test
    public void TestHive() {
        Iterable<String> dbs = hiveMetaStoreService.getAllDatabases();
        for(String s : dbs) {
            System.out.println(s);
        }
    }
}
