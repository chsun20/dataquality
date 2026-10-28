package com.ruoyi.dqservice;

import com.myspace.test.entity.Dog;
import com.sch.oder_rules.fact.*;
import org.drools.core.io.impl.UrlResource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.KieRepository;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.io.InputStream;

public class DroolsApplicationWorkBenchTests {
    @Test
    public void testWorkbench() throws IOException {
        String url = "http://localhost:8080/drools-wb/maven2/com/sch/order-rules/1.0.0/order-rules-1.0.0.jar";
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
        Order order = new Order();
        order.setAmout(250);
        kieSession.insert(order);
        int ruleFiredCount = kieSession.fireAllRules();
        System.out.println("触发了" + ruleFiredCount + "条规则");
        System.out.println("订单提交之后积分：" + order.getScore());
        kieSession.dispose();
    }
}
