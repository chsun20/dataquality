package com.sch.test;

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

public class TestDrools2 {
    // 实现方式2：在workbench配置javabean，在后端定义与其相同的javabean，再将其insert
    @Test
    public void testWorkbench() throws IOException, IllegalAccessException, InstantiationException, ClassNotFoundException {
        String url = "file:///c:\\Users\\TUF\\dockerRepo\\kie\\global\\com\\sch\\order-rules\\1.0.2\\order-rules-1.0.2.jar";
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

        KieSession kieSession = kieContainer.newKieSession();

        List list = new ArrayList();
        kieSession.setGlobal("list", list);
        Order order = new Order();
        order.setAmout(1);
        order.setScore(1);
        order.setType("ab");
        order.setInfo("ab");
        kieSession.insert(order);
//        String[] tmp = new String[]{"aaaaaaaab", "ab", "aaaaaaaaaaaaaaaaaaaaaaaa"};

//        List<Order> list = new ArrayList<>();
//        for(int i = 0; i < 1; i++) {
//            TestObject testObject = new TestObject();
//            Random random = new Random();
//            testObject.setC1(0);
//            testObject.setC2(0);
//            testObject.setC3(0);
//            testObject.setC4(0);
//            testObject.setC5(0);
//            testObject.setC6(0);
//            testObject.setC7(0);
//            testObject.setC8(0);
//            testObject.setC9(0);
//            testObject.setS1(tmp[random.nextInt(3)]);
//            kieSession.insert(testObject);
//        }


//        int ruleFiredCount = 0;

        long pre = System.currentTimeMillis();
        kieSession.fireAllRules();
        System.out.println("drools cost " + (System.currentTimeMillis() - pre) + "ms");
        System.out.println("触发了" + list.size() + "条规则");
//        System.out.println("订单提交之后积分：" + order.getScore());
        kieSession.dispose();
        System.gc();
    }
}
