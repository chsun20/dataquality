package com.ruoyi.dqservice.util;

import com.ruoyi.dqservice.domain.Rule;
import com.ruoyi.dqservice.dto.ColInfo;
import com.sch.order_rules.fact.Bank;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
    private static final String[] BANK_FIELDS = {
            "age", "job", "marital", "education", "isDefault", "balance", "housing", "loan",
            "contact", "day", "month", "duration", "campaign", "pdays", "previous", "poutcome", "y"
    };

    public static Path bankOutputDirectory() {
        return Paths.get(System.getProperty("bank.outputDir", "target/bank-experiment")).toAbsolutePath();
    }

    public static void aprioriParser(String url) {
        generateBankDrl(Paths.get(url), bankOutputDirectory(), "original", false);
    }

    /** 读取本地实验生成的项集文件，规则 ID 独立于排序位置。 */
    @SuppressWarnings("unchecked")
    private static List<Mymap> readItemsets(Path input) {
        try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(input))) {
            return (List<Mymap>) stream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("读取频繁项集失败：" + input, e);
        }
    }

    /** L3 只改变规则声明顺序，L4 只改变同一 Bank 模式中的条件顺序。 */
    public static String generateBankDrl(Path input, Path output, String variant, boolean diagnostic) {
        if (!Arrays.asList("original", "l3-weak", "l3-strong", "l4-weak", "l4-strong").contains(variant)) {
            throw new IllegalArgumentException("未知排序组：" + variant);
        }
        List<Mymap> items = readItemsets(input);
        Map<String, Integer> singletonCounts = new HashMap<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            order.add(i);
            Mymap item = items.get(i);
            if (item.li.size() == 1) singletonCounts.put(item.li.get(0), item.count);
        }
        boolean strong = variant.endsWith("strong");
        if (variant.startsWith("l3")) {
            Comparator<Integer> cmp = Comparator.comparingInt(i -> items.get(i).count);
            order.sort(strong ? cmp : cmp.reversed());
        }
        StringBuilder drl = new StringBuilder("package bank.experiment\nimport java.util.List\n"
                + "import com.sch.order_rules.fact.Bank\n");
        if (diagnostic) drl.append("global java.util.Set matches\nglobal java.util.Map rowIds\n");
        for (int index : order) {
            List<String> predicates = new ArrayList<>(items.get(index).li);
            if (variant.startsWith("l4")) {
                Comparator<String> cmp = Comparator.comparingInt(p -> {
                    Integer count = singletonCounts.get(p);
                    if (count == null) throw new IllegalStateException("缺少单项支持度：" + p);
                    return count;
                });
                predicates.sort(strong ? cmp : cmp.reversed());
            }
            drl.append("rule \"rule").append(index).append("\"\nwhen\n    $li: List()\n    $bank: Bank(")
                    .append(String.join(" && ", predicates)).append(") from $li\nthen\n");
            if (diagnostic) drl.append("    matches.add(\"rule").append(index)
                    .append(":\" + rowIds.get($bank));\n");
            drl.append("end\n\n");
        }
        String text = drl.toString();
        writeFile(output.resolve(variant + (diagnostic ? "-check" : "") + ".drl").toString(), text);
        return text;
    }

    /** 从独立的逐行集合判断取得期望命中身份，同时校验挖掘阶段的支持度。 */
    public static Set<String> expectedBankMatches(Path input, String csv) {
        List<Mymap> items = readItemsets(input);
        List<List<String>> rows = bankTransactions(csv);
        Set<String> matches = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            int count = 0;
            for (int row = 0; row < rows.size(); row++) {
                if (rows.get(row).containsAll(items.get(i).li)) {
                    matches.add("rule" + i + ":" + row);
                    count++;
                }
            }
            if (count != items.get(i).count) throw new IllegalStateException("支持度不一致：rule" + i);
        }
        return matches;
    }

    public static void printFile(String url) {
        try {
            if (url.endsWith(".drl")) {
                System.out.println(new String(Files.readAllBytes(Paths.get(url)), StandardCharsets.UTF_8));
            } else {
                try (ObjectInputStream stream = new ObjectInputStream(Files.newInputStream(Paths.get(url)))) {
                    System.out.println(stream.readObject());
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException("读取文件失败：" + url, e);
        }
    }

    /** 文本按 UTF-8 输出，其余实验对象使用 Java 序列化。 */
    public static <T> void writeFile(String url, T value) {
        Path path = Paths.get(url).toAbsolutePath();
        try {
            Files.createDirectories(path.getParent());
            if (value instanceof CharSequence) {
                Files.write(path, value.toString().getBytes(StandardCharsets.UTF_8));
            } else {
                try (ObjectOutputStream stream = new ObjectOutputStream(Files.newOutputStream(path))) {
                    stream.writeObject(value);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("写入文件失败：" + path, e);
        }
    }

    /** 专用于 bank 的单行、分号分隔 CSV；严格核验表头和列数。 */
    private static List<String[]> readBankCsv(String url) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(url), StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (header == null) throw new IllegalArgumentException("CSV 缺少表头");
            String[] columns = bankCsvCells(header.replace("\uFEFF", ""));
            String[] expected = BANK_FIELDS.clone();
            expected[4] = "default";
            if (!Arrays.equals(columns, expected)) throw new IllegalArgumentException("bank CSV 表头不匹配");
            String text;
            int line = 1;
            while ((text = reader.readLine()) != null) {
                line++;
                if (text.trim().isEmpty()) continue;
                String[] cells = bankCsvCells(text);
                if (cells.length != BANK_FIELDS.length) throw new IllegalArgumentException("CSV 列数错误，行 " + line);
                for (int i = 0; i < cells.length; i++) {
                    if (bankNumeric(i)) {
                        try {
                            cells[i] = Integer.toString(Integer.parseInt(cells[i]));
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("CSV 整数错误，行 " + line + "，字段 " + BANK_FIELDS[i], e);
                        }
                    }
                }
                rows.add(cells);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取 CSV 失败：" + url, e);
        }
        return rows;
    }

    private static String[] bankCsvCells(String line) {
        String[] cells = line.split(";(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i].trim();
            if (!cell.matches("\"(?:[^\"]|\"\")*\"|[^\";]*")) {
                throw new IllegalArgumentException("CSV 引号格式错误");
            }
            cells[i] = cell.startsWith("\"") ? cell.substring(1, cell.length() - 1).replace("\"\"", "\"") : cell;
        }
        return cells;
    }

    private static boolean bankNumeric(int index) {
        return index == 0 || index == 5 || index == 9 || (index >= 11 && index <= 14);
    }

    public static List<List<String>> bankTransactions(String url) {
        List<List<String>> transactions = new ArrayList<>();
        for (String[] row : readBankCsv(url)) {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < row.length; i++) {
                String literal = bankNumeric(i) ? row[i] : "\"" + escape(row[i]) + "\"";
                items.add(BANK_FIELDS[i] + "==" + literal);
            }
            transactions.add(items);
        }
        return transactions;
    }

    public static List<Bank> saveBankData(String url) {
        return saveBankData(url, bankOutputDirectory());
    }

    public static List<Bank> saveBankData(String url, Path output) {
        List<Bank> banks = new ArrayList<>();
        for (String[] row : readBankCsv(url)) {
            Bank bank = new Bank();
            bank.setAge(Integer.parseInt(row[0]));
            bank.setJob(row[1]);
            bank.setMarital(row[2]);
            bank.setEducation(row[3]);
            bank.setIsDefault(row[4]);
            bank.setBalance(Integer.parseInt(row[5]));
            bank.setHousing(row[6]);
            bank.setLoan(row[7]);
            bank.setContact(row[8]);
            bank.setDay(Integer.parseInt(row[9]));
            bank.setMonth(row[10]);
            bank.setDuration(Integer.parseInt(row[11]));
            bank.setCampaign(Integer.parseInt(row[12]));
            bank.setPdays(Integer.parseInt(row[13]));
            bank.setPrevious(Integer.parseInt(row[14]));
            bank.setPoutcome(row[15]);
            bank.setY(row[16]);
            banks.add(bank);
        }
        writeFile(output.resolve("bank-data.ser").toString(), banks);
        return banks;
    }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("用法：RuleParser <项集文件> <输出目录>");
        generateBankDrl(Paths.get(args[0]), Paths.get(args[1]), "original", false);
    }
}
