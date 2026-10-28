package com.ruoyi.dqservice.util;

import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.sch.order_rules.fact.Bank;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleParser {

    /** 约束标识：空值检测 */
    private static final String CONSTRAINT_NULL_COUNT = "nullCount";

    /** 行号占位字段，执行时由 RuleExecuteService 写入每条记录 */
    public static final String ROW_ID_KEY = "__rowId";

    /** 匹配 DRL 中的空值条件：this["col"] == null */
    private static final Pattern NULL_CONDITION_PATTERN =
            Pattern.compile("this\\[\"([^\"]+)\"\\]\\s*==\\s*null");

    /**
     * 根据列约束生成「空值检测」DRL。
     *
     * 设计说明：
     * 1. 数据行以 Map 作为 fact 插入 KieSession，因此无需 declare 动态类型，
     *    任意表结构都能直接检测，字段通过 this["col"] 索引访问。
     * 2. 每个检测字段单独生成一条规则，命中即代表该记录该字段违规，
     *    便于报告按字段维度统计。
     * 3. then 块把违规详情写入 global 集合 violations，由执行方回收。
     *
     * 与原实现的区别：原实现生成 `col != null`（匹配合规数据）且 then 为空，
     * 调用方拿不到任何检出结果；此处改为匹配违规并在 then 中产出明细。
     */
    public static void nullCountParser(Rule rule) {
        ColInfo[] cols = rule.getCols();
        if (cols == null || cols.length == 0) {
            // 没有列信息（例如规则列表页直接编辑 DRL），保留调用方已有的 ruleContext
            return;
        }

        StringBuilder columns = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            String colName = cols[i].getName();
            if (colName == null || colName.isEmpty()) {
                continue;
            }
            if (columns.length() != 0) {
                columns.append(",");
            }
            columns.append(colName);
        }
        rule.setColumnName(columns.toString());

        List<String> nullCols = nullColumnsOf(cols);
        if (nullCols.isEmpty()) {
            return;
        }
        String ruleName = rule.getRuleName() == null || rule.getRuleName().isEmpty()
                ? "rule" : rule.getRuleName();
        rule.setRuleContext(buildNullCountDrl(ruleName, nullCols));
    }

    private static String buildNullCountDrl(String ruleName, List<String> nullCols) {
        String name = escape(ruleName);
        StringBuilder drl = new StringBuilder();
        // package 固定：执行时每条规则都用独立的 KieHelper 编译，不存在同名冲突
        drl.append("package dq.dynamic\n")
           .append("import java.util.Map\n")
           .append("import java.util.HashMap\n")
           .append("global java.util.List violations\n\n");

        for (String col : nullCols) {
            String field = escape(col);
            drl.append("rule \"").append(name).append("_").append(field).append("_null\"\n")
               .append("    when\n")
               .append("        $row: Map(this[\"").append(field).append("\"] == null)\n")
               .append("    then\n")
               .append("        Map v = new HashMap();\n")
               .append("        v.put(\"rule\", \"").append(name).append("\");\n")
               .append("        v.put(\"field\", \"").append(field).append("\");\n")
               .append("        v.put(\"rowId\", $row.get(\"").append(ROW_ID_KEY).append("\"));\n")
               .append("        violations.add(v);\n")
               .append("end\n\n");
        }
        return drl.toString();
    }

    /** DRL 字符串字面量转义，避免规则名/字段名中的引号破坏语法 */
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 提取列约束中的空值检测字段，保持列顺序并去重。
     */
    public static List<String> nullColumnsOf(ColInfo[] cols) {
        List<String> nullCols = new ArrayList<>();
        if (cols == null) {
            return nullCols;
        }
        for (ColInfo col : cols) {
            String colName = col.getName();
            if (colName == null || colName.isEmpty() || nullCols.contains(colName)) {
                continue;
            }
            String[] constraints = col.getRules();
            if (constraints == null) {
                continue;
            }
            for (String constraint : constraints) {
                if (CONSTRAINT_NULL_COUNT.equals(constraint)) {
                    nullCols.add(colName);
                    break;
                }
            }
        }
        return nullCols;
    }

    /**
     * 从已有 DRL 中解析当前实际生效的空值检测字段。
     * 编辑规则时用于判断用户是否改动了检测范围。
     */
    public static List<String> parseNullColumns(String drl) {
        List<String> columns = new ArrayList<>();
        if (drl == null || drl.isEmpty()) {
            return columns;
        }
        Matcher matcher = NULL_CONDITION_PATTERN.matcher(drl);
        while (matcher.find()) {
            String column = matcher.group(1);
            if (!columns.contains(column)) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * 用 DRL 中实际生效的检测字段回填 rule.cols，供前端编辑弹窗回显。
     */
    public static void fillDetectColumns(Rule rule) {
        List<String> columns = parseNullColumns(rule.getRuleContext());
        ColInfo[] cols = new ColInfo[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            ColInfo col = new ColInfo();
            col.setName(columns.get(i));
            col.setRules(new String[] { CONSTRAINT_NULL_COUNT });
            cols[i] = col;
        }
        rule.setCols(cols);
    }
    public static void aprioriParser(String url) {
        try {
            ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(Paths.get(url)));
            List<Mymap> map = (List<Mymap>) ois.readObject();
            StringBuilder rule = new StringBuilder();
            rule.append("package com.sch.order_rules\nimport java.util.List\n");
            for(int i = 0; i < map.size(); i++) {
                List<String> tmp = map.get(i).li;
                StringBuilder ruleContext = new StringBuilder();
                ruleContext.append("rule \"rule").append(i).append("\"\nwhen $li: List()\n$bank:(");
                for(int j = 0; j < tmp.size(); j++) {
                    if(j != 0) ruleContext.append(" AND ");
                    ruleContext.append(tmp.get(j));
                }
                ruleContext.append(") from $li\nthen\nend\n");
                rule.append(ruleContext);
            }
            writeFile("bank.drl", rule.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static void printFile(String url) {
        try {
            ObjectInputStream ois = new ObjectInputStream(Files.newInputStream(Paths.get(url)));
            System.out.println(ois.readObject().toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static <T> void writeFile(String url, T t) {
        try {
            ObjectOutputStream oos = new ObjectOutputStream(Files.newOutputStream(Paths.get(url)));
            oos.writeObject(t);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static List<Bank> saveBankData(String url) {
        List<Bank> list = new ArrayList<>();
        try {
            File file = new File(url);
            if (file.isFile() && file.exists()) {
                InputStreamReader read = new InputStreamReader(Files.newInputStream(file.toPath()));
                BufferedReader bufferedReader = new BufferedReader(read);
                String lineTXT = bufferedReader.readLine();
                while ((lineTXT = bufferedReader.readLine()) != null) {
                    Bank bank = new Bank();
                    String[] line = lineTXT.split(";");
                    if(line.length == 17) {
                        bank.setAge(Integer.parseInt(line[0]));
                        bank.setJob(line[1]);
                        bank.setMarital(line[2]);
                        bank.setEducation(line[3]);
                        bank.setIsDefault(line[4]);
                        bank.setBalance(Integer.parseInt(line[5]));
                        bank.setHousing(line[6]);
                        bank.setLoan(line[7]);
                        bank.setContact(line[8]);
                        bank.setDay(Integer.parseInt(line[9]));
                        bank.setMonth(line[10]);
                        bank.setDuration(Integer.parseInt(line[11]));
                        bank.setCampaign(Integer.parseInt(line[12]));
                        bank.setPdays(Integer.parseInt(line[13]));
                        bank.setPrevious(Integer.parseInt(line[14]));
                        bank.setPoutcome(line[15]);
                        bank.setY(line[16]);
                        list.add(bank);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        writeFile("bankData", list);
        return list;
    }
    public static void main(String[] args) {
        printFile("bank.drl");
    }
}
