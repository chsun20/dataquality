package com.ruoyi.dqservice;

import org.junit.jupiter.api.Test;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证「动态表结构 -> Map fact -> 违规收集」这条技术路线是否可行。
 * 不启动 Spring 上下文，只用 KieHelper 编译并执行 DRL。
 *
 * 被测语法有三种写法，目的是一次性确定哪种能在 Drools 7.5 下正常工作：
 *   A. Map(this["col"] == null)   —— MVEL 索引访问
 *   B. Map(col == null)          —— Drools 对 Map 的属性访问糖
 *   C. eval 写法
 */
public class DrlSyntaxTest {

    private static final String HEADER =
            "package dq.test\n" +
            "import java.util.Map\n" +
            "global java.util.List violations\n";

    private Results verify(String drl) {
        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(drl, ResourceType.DRL);
        return kieHelper.verify();
    }

    private void assertCompiles(String drl, String label) {
        Results results = verify(drl);
        boolean hasError = results.hasMessages(Message.Level.ERROR);
        if (hasError) {
            System.out.println("== " + label + " 编译失败 ==");
            for (Message m : results.getMessages(Message.Level.ERROR)) {
                System.out.println("   " + m.getText());
            }
        }
        assertFalse(hasError, label + " should compile");
    }

    private Map<String, Object> row(int rowId, Object age, Object name) {
        Map<String, Object> m = new HashMap<>();
        m.put("__rowId", rowId);
        m.put("age", age);
        m.put("customer_name", name);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<Object> run(String drl, List<Map<String, Object>> rows) {
        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(drl, ResourceType.DRL);
        Results results = kieHelper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            for (Message m : results.getMessages(Message.Level.ERROR)) {
                System.out.println("   " + m.getText());
            }
            throw new IllegalStateException("DRL 编译失败");
        }
        KieBase kieBase = kieHelper.build();
        KieSession session = kieBase.newKieSession();
        List<Object> violations = new ArrayList<>();
        session.setGlobal("violations", violations);
        for (Map<String, Object> r : rows) {
            session.insert(r);
        }
        session.fireAllRules();
        session.dispose();
        return violations;
    }

    /** A. MVEL 索引访问 */
    @Test
    public void syntaxA_indexAccess() {
        String drl = HEADER +
                "rule \"r1\"\n" +
                "when\n" +
                "    $row: Map(this[\"age\"] == null)\n" +
                "then\n" +
                "    violations.add($row.get(\"__rowId\"));\n" +
                "end\n";
        assertCompiles(drl, "A");
        List<Object> v = run(drl, jdkList(
                row(1, 28, "张三"),
                row(2, null, "李四"),
                row(3, null, null)));
        System.out.println("A 命中数 = " + v.size() + " -> " + v);
        assertEquals(2, v.size());
    }

    /** B. Drools 对 Map 的属性访问糖 */
    @Test
    public void syntaxB_propertyAccess() {
        String drl = HEADER +
                "rule \"r2\"\n" +
                "when\n" +
                "    $row: Map(age == null)\n" +
                "then\n" +
                "    violations.add($row.get(\"__rowId\"));\n" +
                "end\n";
        assertCompiles(drl, "B");
        List<Object> v = run(drl, jdkList(
                row(1, 28, "张三"),
                row(2, null, "李四"),
                row(3, null, null)));
        System.out.println("B 命中数 = " + v.size() + " -> " + v);
        assertEquals(2, v.size());
    }

    /** C. 一条规则里多个字段条件 + 同一条数据被多条规则命中 */
    @Test
    public void syntaxC_multiFieldMultiRule() {
        String drl = HEADER +
                "rule \"r_age_null\"\n" +
                "when\n" +
                "    $row: Map(this[\"age\"] == null)\n" +
                "then\n" +
                "    violations.add(\"age:\" + $row.get(\"__rowId\"));\n" +
                "end\n" +
                "rule \"r_name_null\"\n" +
                "when\n" +
                "    $row: Map(this[\"customer_name\"] == null)\n" +
                "then\n" +
                "    violations.add(\"name:\" + $row.get(\"__rowId\"));\n" +
                "end\n";
        assertCompiles(drl, "C");
        List<Object> v = run(drl, jdkList(
                row(1, 28, "张三"),
                row(2, null, "李四"),
                row(3, null, null)));
        System.out.println("C 命中数 = " + v.size() + " -> " + v);
        assertEquals(3, v.size());
    }

    /** D. 用 Map 构造违规详情，模拟真实报告需要的字段 */
    @Test
    public void syntaxD_violationDetail() {
        String drl = HEADER +
                "import java.util.HashMap\n" +
                "rule \"r1\"\n" +
                "when\n" +
                "    $row: Map(this[\"age\"] == null)\n" +
                "then\n" +
                "    Map v = new HashMap();\n" +
                "    v.put(\"rule\", drools.getRule().getName());\n" +
                "    v.put(\"field\", \"age\");\n" +
                "    v.put(\"rowId\", $row.get(\"__rowId\"));\n" +
                "    violations.add(v);\n" +
                "end\n";
        assertCompiles(drl, "D");
        List<Object> v = run(drl, jdkList(row(1, 28, "张三"), row(2, null, "李四")));
        System.out.println("D 违规详情 = " + v);
        assertEquals(1, v.size());
        assertTrue(v.get(0) instanceof Map);
    }

    @SafeVarargs
    private final List<Map<String, Object>> jdkList(Map<String, Object>... rows) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            list.add(r);
        }
        return list;
    }
}
