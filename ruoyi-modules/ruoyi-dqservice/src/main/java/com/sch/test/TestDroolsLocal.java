package com.sch.test;

import com.ruoyi.dqservice.util.AprioriMyself;
import com.ruoyi.dqservice.util.RuleParser;
import com.sch.order_rules.fact.Bank;
import org.junit.Test;
import org.kie.api.KieBase;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

public class TestDroolsLocal {
    private static final List<String> VARIANTS = Arrays.asList(
            "original", "l3-weak", "l3-strong", "l4-weak", "l4-strong");

    @Test
    public void testWorkbench() throws Exception {
        org.junit.Assume.assumeNotNull(System.getProperty("bank.csv"));
        main(new String[]{System.getProperty("bank.csv"), RuleParser.bankOutputDirectory().toString(),
                System.getProperty("bank.support", "0.3"), System.getProperty("bank.maxItems", "3")});
    }

    /** 参数：CSV、输出根目录、最小支持度、最大项集长度、可选预热次数、可选测量次数。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            throw new IllegalArgumentException("用法：TestDroolsLocal <CSV> <输出目录> <支持度> <最大项集长度> [预热次数] [测量次数]");
        }
        Path csv = Paths.get(args[0]).toAbsolutePath();
        double support = Double.parseDouble(args[2]);
        int maxItems = Integer.parseInt(args[3]);
        int warmups = args.length > 4 ? Integer.parseInt(args[4]) : 3;
        int repeats = args.length > 5 ? Integer.parseInt(args[5]) : 5;
        if (warmups < 0 || repeats < 1) throw new IllegalArgumentException("预热次数须非负，测量次数须为正");
        Path root = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(root);
        Path output = Files.createTempDirectory(root, "run-");
        System.out.println("本次实验目录：" + output);
        List<String> config = new ArrayList<>(Arrays.asList(
                "status=RUNNING", "csv=" + csv, "csv_sha256=" + sha256(Files.readAllBytes(csv)),
                "support=" + support, "max_items=" + maxItems, "warmups=" + warmups, "repeats=" + repeats,
                "shuffle_seed=20261030", "java=" + System.getProperty("java.version"),
                "drools=7.5.0.Final", "mode=List<Bank> + from", "baseline=canonical-item-order",
                "scope=exploratory-single-jvm;not-historical-reproduction"));
        Path manifest = output.resolve("manifest.txt");
        Files.write(manifest, config, StandardCharsets.UTF_8);
        try {
            long start = System.nanoTime();
            List<Bank> banks = RuleParser.saveBankData(csv.toString(), output);
            config.add("read_and_serialize_ns=" + (System.nanoTime() - start));
            start = System.nanoTime();
            int rules = AprioriMyself.mine(csv.toString(), output, support, maxItems);
            config.add("mining_and_export_ns=" + (System.nanoTime() - start));
            if (rules == 0) throw new IllegalStateException("没有频繁项集，请调整支持度");
            config.add("rows=" + banks.size());
            config.add("rules=" + rules);
            Path itemsets = output.resolve("bank-itemsets.ser");
            Set<String> expected = RuleParser.expectedBankMatches(itemsets, csv.toString());
            List<String> expectedLines = new ArrayList<>(expected);
            Collections.sort(expectedLines);
            Files.write(output.resolve("expected-matches.txt"), expectedLines, StandardCharsets.UTF_8);
            config.add("expected_matches=" + expected.size());
            config.add("expected_sha256=" + sha256(String.join("\n", expectedLines).getBytes(StandardCharsets.UTF_8)));
            Map<Bank, Integer> rowIds = new IdentityHashMap<>();
            for (int i = 0; i < banks.size(); i++) rowIds.put(banks.get(i), i);
            Map<String, KieBase> bases = new LinkedHashMap<>();
            List<String> builds = new ArrayList<>(Collections.singletonList("variant,compile_ns,drl_sha256"));
            List<String> checks = new ArrayList<>(Collections.singletonList("variant,expected,actual,fired,equal"));
            for (String variant : VARIANTS) {
                String drl = RuleParser.generateBankDrl(itemsets, output, variant, false);
                start = System.nanoTime();
                bases.put(variant, compile(drl));
                builds.add(variant + "," + (System.nanoTime() - start) + ","
                        + sha256(drl.getBytes(StandardCharsets.UTF_8)));
            }
            Files.write(output.resolve("compilation.csv"), builds, StandardCharsets.UTF_8);
            // 诊断版独立执行，计时版保持原实验的空 then，避免明细收集掩盖排序成本。
            for (String variant : VARIANTS) {
                String drl = RuleParser.generateBankDrl(itemsets, output, variant, true);
                KieSession session = compile(drl).newKieSession();
                Set<String> actual = new HashSet<>();
                int fired;
                try {
                    session.setGlobal("matches", actual);
                    session.setGlobal("rowIds", rowIds);
                    session.insert(banks);
                    fired = session.fireAllRules();
                } finally {
                    session.dispose();
                }
                boolean equal = actual.equals(expected) && fired == expected.size();
                checks.add(variant + "," + expected.size() + "," + actual.size() + "," + fired + "," + equal);
                Files.write(output.resolve("correctness.csv"), checks, StandardCharsets.UTF_8);
                if (!equal) throw new IllegalStateException("命中身份或触发次数不一致：" + variant);
            }
            Map<String, List<Long>> measured = new LinkedHashMap<>();
            for (String variant : VARIANTS) measured.put(variant, new ArrayList<>());
            Random random = new Random(20261030L);
            try (BufferedWriter writer = Files.newBufferedWriter(output.resolve("timings.csv"), StandardCharsets.UTF_8)) {
                writer.write("variant,phase,round,rows,rules,fired,session_ns,insert_ns,fire_ns,match_ns,dispose_ns\n");
                for (int round = -warmups; round < repeats; round++) {
                    List<String> order = new ArrayList<>(VARIANTS);
                    Collections.shuffle(order, random);
                    for (String variant : order) {
                        long[] timing = execute(bases.get(variant), banks, expected.size());
                        writer.write(variant + "," + (round < 0 ? "warmup" : "measure") + "," + round
                                + "," + banks.size() + "," + rules + "," + expected.size() + "," + timing[0]
                                + "," + timing[1] + "," + timing[2] + "," + (timing[1] + timing[2]) + "," + timing[3] + "\n");
                        writer.flush();
                        if (round >= 0) measured.get(variant).add(timing[2]);
                    }
                }
            }
            List<String> summary = new ArrayList<>(Collections.singletonList("variant,median_fire_ms,min_fire_ms,max_fire_ms"));
            for (String variant : VARIANTS) {
                List<Long> values = measured.get(variant);
                Collections.sort(values);
                double median = (values.get((repeats - 1) / 2) / 2.0 + values.get(repeats / 2) / 2.0) / 1e6;
                String line = String.format(Locale.ROOT, "%s,%.3f,%.3f,%.3f", variant, median,
                        values.get(0) / 1e6, values.get(values.size() - 1) / 1e6);
                summary.add(line);
                System.out.println(line);
            }
            Files.write(output.resolve("summary.csv"), summary, StandardCharsets.UTF_8);
            config.set(0, "status=SUCCESS");
        } catch (Exception | Error e) {
            config.set(0, "status=FAILED");
            config.add("error=" + e);
            throw e;
        } finally {
            Files.write(manifest, config, StandardCharsets.UTF_8);
        }
    }

    private static KieBase compile(String drl) {
        return new KieHelper().addContent(drl, ResourceType.DRL).build();
    }

    private static long[] execute(KieBase base, List<Bank> banks, int expected) {
        long start = System.nanoTime();
        KieSession session = base.newKieSession();
        long[] times = new long[4];
        times[0] = System.nanoTime() - start;
        try {
            start = System.nanoTime();
            session.insert(banks);
            times[1] = System.nanoTime() - start;
            start = System.nanoTime();
            int fired = session.fireAllRules();
            times[2] = System.nanoTime() - start;
            if (fired != expected) throw new IllegalStateException("计时版触发次数不一致：" + fired);
        } finally {
            start = System.nanoTime();
            session.dispose();
            times[3] = System.nanoTime() - start;
        }
        return times;
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return hex.toString();
    }
}
