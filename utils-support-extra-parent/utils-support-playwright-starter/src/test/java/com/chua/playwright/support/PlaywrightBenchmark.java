package com.chua.playwright.support;

import com.chua.common.support.utils.NativeUtils;
import java.io.*;
import java.lang.management.*;
import java.util.*;

/**
 * 轻量级性能压测。压测项：单步延迟 / 内存 / batch 加速。
 * 并发测试在独立入口避免超时。
 */
public class PlaywrightBenchmark {

    static final int ITER = 5;
    static final String TEST_URL = "https://www.baidu.com";
    static final List<BenchResult> results = new ArrayList<>();

    static class BenchResult {
        String name, mode, val;
        BenchResult(String name, String mode, String val) {
            this.name = name; this.mode = mode; this.val = val;
        }
    }

    static String mode() { return Playwright.isNative() ? "NATIVE" : "JAVA"; }

    public static void main(String[] args) throws Exception {
        System.out.println("===== Playwright Native Benchmark =====");
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("JVM: " + Runtime.version());
        System.out.println("Cores: " + Runtime.getRuntime().availableProcessors());

        Playwright.create();
        String m = mode();
        System.out.println("Mode: " + m + " v" + Playwright.version());
        System.out.println();

        // ========== warmup ==========
        Browser wb = Playwright.create().chromium().launch();
        Page wp = wb.newPage();
        wp.gotoPage(TEST_URL);
        wp.title(); wp.screenshot();
        wp.close(); wb.close();
        System.out.println("Warmup done\n");

        // ========== 1. single-step ==========
        System.out.println("--- 1. Single-step Latency (avg of " + ITER + ") ---");
        Browser b1 = Playwright.create().chromium().launch();
        long[] s = new long[ITER];

        // launch
        long t0 = System.nanoTime();
        for (int i = 0; i < ITER; i++) {
            Browser bb = Playwright.create().chromium().launch();
            s[i] = (System.nanoTime() - t0) / 1_000_000;
            bb.close();
            t0 = System.nanoTime();
        }
        add("launch browser", s);

        // newPage
        for (int i = 0; i < ITER; i++) {
            t0 = System.nanoTime();
            Page p = b1.newPage();
            s[i] = (System.nanoTime() - t0) / 1_000_000;
            p.close();
        }
        add("newPage", s);

        // goto
        for (int i = 0; i < ITER; i++) {
            Page p = b1.newPage();
            t0 = System.nanoTime();
            p.gotoPage(TEST_URL);
            s[i] = (System.nanoTime() - t0) / 1_000_000;
            p.close();
        }
        add("goto", s);

        // screenshot
        Page sp = b1.newPage();
        sp.gotoPage(TEST_URL);
        for (int i = 0; i < ITER; i++) {
            t0 = System.nanoTime();
            sp.screenshot();
            s[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        add("screenshot", s);

        // evaluate
        for (int i = 0; i < ITER; i++) {
            t0 = System.nanoTime();
            sp.evaluate("document.title");
            s[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        add("evaluate", s);

        // title
        for (int i = 0; i < ITER; i++) {
            t0 = System.nanoTime();
            sp.title();
            s[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        add("title", s);

        // querySelector
        for (int i = 0; i < ITER; i++) {
            t0 = System.nanoTime();
            sp.querySelector("#head");
            s[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        add("querySelector", s);

        sp.close();
        b1.close();

        // ========== 2. batch vs single ==========
        System.out.println("\n--- 2. Batch vs Single ---");
        Browser b3 = Playwright.create().chromium().launch();

        // single (launch+newPage+goto+screenshot+close)
        long ts = System.nanoTime();
        Browser bb = Playwright.create().chromium().launch();
        Page pp = bb.newPage();
        pp.gotoPage(TEST_URL);
        pp.screenshot();
        pp.close();
        bb.close();
        long singleMs = (System.nanoTime() - ts) / 1_000_000;

        // batch (3 个独立命令: goto + screenshot + evaluate, 用同一个已知 page handle)
        Browser bb2 = Playwright.create().chromium().launch();
        Page pB = bb2.newPage();
        pB.gotoPage(TEST_URL); // 先导航一次，让 page 处于可用状态
        long ph = pB.handle();
        long tb = System.nanoTime();
        Batch batch = new Batch();
        batch.gotoPage((int)ph, TEST_URL);
        batch.screenshot((int)ph);
        batch.evaluate((int)ph, "document.title");
        batch.execute();
        long batchMs = (System.nanoTime() - tb) / 1_000_000;
        pB.close();
        bb2.close();

        // 对照单步（同一 page 顺序执行相同 3 步）
        long ts2 = System.nanoTime();
        Browser b3ref = Playwright.create().chromium().launch();
        Page pRef = b3ref.newPage();
        pRef.gotoPage(TEST_URL);
        pRef.screenshot();
        pRef.evaluate("document.title");
        pRef.close();
        b3ref.close();
        long single3Ms = (System.nanoTime() - ts2) / 1_000_000;

        System.out.println("  single: " + singleMs + " ms");
        System.out.println("  batch: " + batchMs + " ms");
        System.out.println("  speedup: " + (singleMs / Math.max(batchMs, 1)) + "x");
        results.add(new BenchResult("single (launch+goto+screenshot)", m, singleMs + " ms"));
        results.add(new BenchResult("batch (3 calls in 1 JNI)", m, batchMs + " ms"));
        results.add(new BenchResult("single (3 calls)", m, single3Ms + " ms"));
        b3.close();

        // ========== 3. memory ==========
        System.out.println("\n--- 3. Memory ---");
        MemoryMXBean mmb = ManagementFactory.getMemoryMXBean();
        long heapBefore = mmb.getHeapMemoryUsage().getUsed();
        Browser b4 = Playwright.create().chromium().launch();
        for (int i = 0; i < 20; i++) {
            Page p = b4.newPage();
            p.gotoPage("data:text/html,<h1>test</h1>");
            p.close();
        }
        b4.close();
        System.gc();
        Thread.sleep(200);
        long heapAfter = mmb.getHeapMemoryUsage().getUsed();
        long deltaKb = (heapAfter - heapBefore) / 1024;
        System.out.println("  heap delta (20 page cycles): " + deltaKb + " KB");
        results.add(new BenchResult("heap delta (20 cycles)", m, deltaKb + " KB"));
        results.add(new BenchResult("JVM total heap", m, Runtime.getRuntime().totalMemory() / 1024 + " KB"));

        // ========== 4. concurrent ==========
        System.out.println("\n--- 4. Concurrent (3 tabs) ---");
        Browser b5 = Playwright.create().chromium().launch();
        List<Page> pages = new ArrayList<>();
        for (int i = 0; i < 3; i++) pages.add(b5.newPage());
        long tstart = System.nanoTime();
        Tester[] testers = new Tester[3];
        for (int i = 0; i < 3; i++) {
            testers[i] = new Tester(pages.get(i));
            testers[i].start();
        }
        for (Tester t : testers) t.join();
        long totalMs = (System.nanoTime() - tstart) / 1_000_000;
        long avgPerOp = totalMs / (3L * 3L);
        System.out.println("  3 tabs x 3 ops: " + totalMs + " ms total, " + avgPerOp + " ms/op");
        results.add(new BenchResult("concurrent 3tab x3op", m, avgPerOp + " ms/op"));
        for (Page p : pages) p.close();
        b5.close();

        // ========== print ==========
        printSummary();
        writeReport();
    }

    static class Tester extends Thread {
        final Page page;
        Tester(Page p) { this.page = p; }
        public void run() {
            for (int i = 0; i < 3; i++) {
                try {
                    page.gotoPage(TEST_URL);
                    page.title();
                    page.screenshot();
                } catch (Exception ignored) {}
            }
        }
    }

    static void add(String name, long[] samples) {
        long sum = 0, min = Long.MAX_VALUE, max = 0;
        for (long s : samples) { sum += s; if (s < min) min = s; if (s > max) max = s; }
        long avg = sum / samples.length;
        String m = mode();
        System.out.printf("  %-25s avg=%5d ms  min=%5d  max=%5d%n", name, avg, min, max);
        results.add(new BenchResult(name, m, avg + " ms"));
    }

    static void printSummary() {
        System.out.println("\n==================== Summary ====================");
        System.out.printf("%-30s %-8s %s%n", "Test", "Mode", "Value");
        System.out.println("------------------------------------------------");
        for (BenchResult r : results) {
            System.out.printf("%-30s %-8s %s%n", r.name, r.mode, r.val);
        }
    }

    static void writeReport() throws Exception {
        String path = "target/playwright-benchmark.txt";
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("Playwright Native Performance Report");
            pw.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            pw.println("JVM: " + Runtime.version());
            pw.println("Cores: " + Runtime.getRuntime().availableProcessors());
            pw.println("Mode: " + mode() + " v" + Playwright.version());
            pw.println();
            pw.printf("%-30s %-8s %s%n", "Test", "Mode", "Value");
            pw.println("------------------------------------------------");
            for (BenchResult r : results) {
                pw.printf("%-30s %-8s %s%n", r.name, r.mode, r.val);
            }
            pw.println();
            pw.println("Notes:");
            pw.println("  1. goto: dominated by Chromium network + page load + CDP wait");
            pw.println("  2. screenshot: CDP capture + PNG encode (Rust side base64)");
            pw.println("  3. evaluate: JS injection + CDP result return");
            pw.println("  4. batch: single JNI call for >1 commands, amortizes overhead");
            pw.println("  5. memory: Rust side zero GC, handle table is HashMap<u64, Arc<Tab>>");
        }
        System.out.println("Report -> " + new File(path).getAbsolutePath());
    }
}