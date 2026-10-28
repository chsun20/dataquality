package com.sch.test;

import com.sch.order_rules.fact.Order;
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

public class TestDrools {
    // 实现方式1：在drl文件中使用declare关键词定义类，在后端使用FactType获取drl中定义的类，将一个object对象insert
    @Test
    public void testWorkbench() throws IOException, IllegalAccessException, InstantiationException, ClassNotFoundException {
        String url = "http://localhost:8080/drools-wb/maven2/com/myteam/object-rules/1.0.0/object-rules-1.0.0.jar";
        KieServices kieServices = KieServices.Factory.get();
        UrlResource resource = (UrlResource) kieServices.getResources().newUrlResource(url);
        resource.setBasicAuthentication("enabled");
        resource.setPassword("admin");
        resource.setUsername("admin");
        System.out.println(resource.getFile().getName());
        InputStream is = null;
        try {
            is = resource.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        KieRepository kieRepository = kieServices.getRepository();
        KieModule kieModule = kieRepository.addKieModule(kieServices.getResources().newInputStreamResource(is));
        KieContainer kieContainer = kieServices.newKieContainer(kieModule.getReleaseId());

        KieBase kieBase = kieContainer.getKieBase("myKbase1");
        FactType factType = kieBase.getFactType("com.myteam.object_rules", "Order");
        List<Object> list = new ArrayList<>();
        for(int i = 0; i < 10; i++) {
            Object obj = factType.newInstance();
            if(i < 6) factType.set(obj, "amount", i);
            else factType.set(obj, "amount", null);
            list.add(obj);
        }

        KieSession kieSession = kieContainer.newKieSession();

        int total = list.size();

        kieSession.insert(list);

        long pre = System.currentTimeMillis();
        int ruleFiredCount = kieSession.fireAllRules();
        System.out.println("drools cost " + (System.currentTimeMillis() - pre) + "ms");
        System.out.println("触发了" + ruleFiredCount + "条规则");
        System.out.println("共有" + total + "条数据");
//        System.out.println("订单提交之后积分：" + factType.get(obj, "score"));
        kieSession.dispose();
    }
}
