package com.ruoyi.dqservice.connecter;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.DroolsRule;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.*;
import org.kie.api.builder.model.KieBaseModel;
import org.kie.api.builder.model.KieModuleModel;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class DroolsManager {
    // 此类本身就是单例的
    private final KieServices kieServices = KieServices.get();

    // kie文件系统，需要缓存，如果每次添加规则都是重新new一个的话，则可能出现问题。即之前加到文件系统中的规则没有了
    private final KieFileSystem kieFileSystem = kieServices.newKieFileSystem();

    // 可以理解为构建 kmodule.xml
    private final KieModuleModel kieModuleModel = kieServices.newKieModuleModel();

    // 需要全局唯一一个，如果每次加个规则都新创建一个，那么就需要销毁之前创建的kieContainer，如果此时有正在使用的KieSession，则可能有问题
    private KieContainer kieContainer;

    /**
     * 判断该kbase是否存在
     */
    public boolean existsKieBase(String kieBaseName) {
        if (null == kieContainer) {
            return false;
        }
        Collection<String> kieBaseNames = kieContainer.getKieBaseNames();
        if (kieBaseNames.contains(kieBaseName)) {
            return true;
        }
        log.info("需要创建KieBase:{}", kieBaseName);
        return false;
    }

    public void deleteDroolsRule(String kieBaseName, String packageName, String ruleName) {
        if (existsKieBase(kieBaseName)) {
            KieBase kieBase = kieContainer.getKieBase(kieBaseName);
            kieBase.removeRule(packageName, ruleName);
            log.info("删除kieBase:[{}]包:[{}]下的规则:[{}]", kieBaseName, packageName, ruleName);
        }
    }

    /**
     * 添加或更新 drools 规则
     */
    public void addOrUpdateRule(DroolsRule droolsRule) {
        // 获取kbase的名称
        String kieBaseName = droolsRule.getKieBaseName();
        // 判断该kbase是否存在
        boolean existsKieBase = existsKieBase(kieBaseName);
        // 该对象对应kmodule.xml中的kbase标签
        KieBaseModel kieBaseModel = null;
        if (!existsKieBase) {
            // 创建一个kbase
            kieBaseModel = kieModuleModel.newKieBaseModel(kieBaseName);
            // 不是默认的kieBase
            kieBaseModel.setDefault(false);
            // 设置该KieBase需要加载的包路径
            kieBaseModel.addPackage(droolsRule.getKiePackageName());
            // 设置kieSession
            kieBaseModel.newKieSessionModel(kieBaseName + "-session")
                    // 不是默认session
                    .setDefault(false);
        } else {
            // 获取到已经存在的kbase对象
            kieBaseModel = kieModuleModel.getKieBaseModels().get(kieBaseName);
            // 获取到packages
            List<String> packages = kieBaseModel.getPackages();
            if (!packages.contains(droolsRule.getKiePackageName())) {
                kieBaseModel.addPackage(droolsRule.getKiePackageName());
                log.info("kieBase:{}添加一个新的包:{}", kieBaseName, droolsRule.getKiePackageName());
            } else {
                kieBaseModel = null;
            }
        }
        String file = "src/main/resources/" + droolsRule.getKiePackageName() + "/" + droolsRule.getRuleId() + ".drl";
        log.info("加载虚拟规则文件:{}", file);
        kieFileSystem.write(file, droolsRule.getRuleContent());

        if (kieBaseModel != null) {
            String kmoduleXml = kieModuleModel.toXML();
            log.info("加载kmodule.xml:[\n{}]", kmoduleXml);
            kieFileSystem.writeKModuleXML(kmoduleXml);
        }

        KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
        // 通过KieBuilder构建KieModule下所有的KieBase
        kieBuilder.buildAll();
        // 获取构建过程中的结果
        Results results = kieBuilder.getResults();
        // 获取错误信息
        List<Message> messages = results.getMessages(Message.Level.ERROR);
        if (null != messages && !messages.isEmpty()) {
            for (Message message : messages) {
                log.error(message.getText());
            }
            throw new RuntimeException("加载规则出现异常");
        }
        // KieContainer只有第一次时才需要创建，之后就是使用这个
        if (null == kieContainer) {
            kieContainer = kieServices.newKieContainer(kieServices.getRepository().getDefaultReleaseId());
        }
    }

    /**
     * 触发规则，此处简单模拟，会向规则中插入一个Integer类型的值
     */
    public String fireRule(String kieBaseName, Integer param) {
        // 创建kieSession
        KieSession kieSession = kieContainer.newKieSession(kieBaseName + "-session");
        StringBuilder resultInfo = new StringBuilder();
        kieSession.setGlobal("resultInfo", resultInfo);
        kieSession.insert(param);
        kieSession.fireAllRules();
        kieSession.dispose();
        return resultInfo.toString();
    }

    /**
     * 仅编译校验 DRL，不执行。供规则保存前的语法检查使用。
     *
     * @param drl 完整 DRL 文本
     */
    public void verifyDrl(String drl)
    {
        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(drl, ResourceType.DRL);
        Results results = kieHelper.verify();
        List<Message> errors = results.getMessages(Message.Level.ERROR);
        if (errors != null && !errors.isEmpty())
        {
            StringBuilder detail = new StringBuilder();
            for (Message message : errors)
            {
                if (detail.length() > 0)
                {
                    detail.append("; ");
                }
                detail.append(message.getText());
            }
            log.error("规则编译失败: {}", detail);
            throw new ServiceException("规则编译失败：" + detail);
        }
    }

    /**
     * 编译并执行一组 DRL，返回规则命中的违规明细。
     *
     * 与 fireRule 的区别：
     * 1. 支持任意数量的 Map fact（每条数据库记录一个 Map），而非单个 Integer；
     * 2. 每次调用用独立的 KieHelper 编译，不写入全局 KieFileSystem，
     *    因此单条规则语法错误不会影响其它规则，规则修改也能立即生效；
     * 3. 通过 global 集合 violations 回收 then 块产出的违规详情。
     *
     * @param drl   待执行的 DRL 文本
     * @param facts 数据记录，字段名 -> 字段值
     * @return 违规明细列表，每项包含 rule / field / rowId
     */
    public List<Map<String, Object>> fireRules(String drl, Collection<Map<String, Object>> facts) {
        return fireRules(drl, facts, new QualityExecutionContext(java.time.Instant.now(), facts));
    }

    public List<Map<String, Object>> fireRules(String drl, Collection<Map<String, Object>> facts,
            QualityExecutionContext context) {
        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(drl, ResourceType.DRL);

        Results results = kieHelper.verify();
        List<Message> errors = results.getMessages(Message.Level.ERROR);
        if (errors != null && !errors.isEmpty()) {
            StringBuilder detail = new StringBuilder();
            for (Message message : errors) {
                detail.append(message.getText()).append("; ");
            }
            log.error("规则编译失败: {}", detail);
            throw new ServiceException("规则编译失败: " + detail);
        }

        KieSession kieSession = null;
        try {
            kieSession = kieHelper.build().newKieSession();
            List<Map<String, Object>> violations = new ArrayList<>();
            try
            {
                kieSession.setGlobal("violations", violations);
            }
            catch (RuntimeException e)
            {
                // 手写 DRL 没有声明 global 时给出可读提示，而不是抛裸异常
                throw new ServiceException("规则未声明 global java.util.List violations，无法收集违规结果");
            }
            boolean usesContext = kieSession.getKieBase().getKiePackages().stream()
                    .flatMap(p -> p.getGlobalVariables().stream()).anyMatch(g -> "qualityContext".equals(g.getName()));
            if (usesContext) kieSession.setGlobal("qualityContext", context);
            for (Map<String, Object> fact : facts) {
                kieSession.insert(fact);
            }
            kieSession.fireAllRules();
            return violations;
        } finally {
            if (kieSession != null) {
                kieSession.dispose();
            }
        }
    }

}
