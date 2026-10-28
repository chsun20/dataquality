package com.sch.test;

import com.ruoyi.dqservice.util.RuleParser;
import com.sch.order_rules.fact.Bank;
import com.sch.order_rules.fact.Order;
import com.sch.order_rules.fact.Person;
import com.sch.order_rules.fact.TestObject;
import org.drools.core.impl.KnowledgeBaseImpl;
import org.drools.core.io.impl.UrlResource;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.KieRepository;
import org.kie.api.definition.type.FactType;
import org.kie.api.runtime.ClassObjectFilter;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;

public class TestDroolsLocal {
    // 实现方式3：本地resource目录下创建kmodule.xml,drl规则文件,javabean定义,在本地构建drools工作空间
    private static KieContainer container = null;
    private KieSession statefulKieSession = null;
    @Test
    public void testWorkbench() throws IOException, IllegalAccessException, InstantiationException, ClassNotFoundException {
        KieServices kieServices = KieServices.Factory.get();
        container = kieServices.getKieClasspathContainer();
        statefulKieSession = container.newKieSession("kession-rule");
        List<Bank> list = RuleParser.saveBankData("C:\\Users\\TUF\\Downloads\\bank\\bank.csv");
        statefulKieSession.insert(list);
        long pre = System.currentTimeMillis();
        statefulKieSession.fireAllRules();
        System.out.println("drools cost " + (System.currentTimeMillis() - pre) + "ms");
        statefulKieSession.dispose();
    }
}
