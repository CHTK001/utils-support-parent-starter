package com.chua.oshi.support.cli.command;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.cli.display.Formatter;

import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;

import java.util.ArrayList;
import java.util.List;

/**
* oshc cpu — CPU 明细：每核使用率、频率、上下文切换、进程 top。
*
* @author CH
* @since 4.0.0.42
 */
public final class CpuCommand extends AbstractCommand {

    @Override
    public String name() {
        return "cpu";
    }

    @Override
    public String description() {
        return "CPU details: per-core usage, frequency, top processes";
    }

    @Override
    public void execute(CommandLine options) {
        HardwareAbstractionLayer hw = Oshi.getHardware();
        CentralProcessor processor = hw.getProcessor();

        System.out.println();
        System.out.println("CPU: " + processor.getProcessorIdentifier().getName().trim());
        System.out.println(
                Formatter.panel("oshc cpu",
                        "Cores", processor.getPhysicalProcessorCount() + " physical / " + processor.getLogicalProcessorCount() + " logical",
                        "Frequency", formatHz(currentFreq(processor)) + " (max " + formatHz(processor.getMaxFreq()) + ")",
                        "Vendor", processor.getProcessorIdentifier().getVendor(),
                        "Family", processor.getProcessorIdentifier().getFamily(),
                        "Model", processor.getProcessorIdentifier().getModel(),
                        "Stepping", processor.getProcessorIdentifier().getStepping()
                )
        );

 // ── Per-核心 usage ──
        long[][] prev = processor.getProcessorCpuLoadTicks();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        double[] perCore = processor.getProcessorCpuLoadBetweenTicks(prev);

        System.out.println();
        System.out.println("Per-Core Usage:");
        int n = perCore.length;
        int cols = 4;
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < n; i += cols) {
            String[] row = new String[cols * 2];
            for (int j = 0; j < cols; j++) {
                int idx = i + j;
                if (idx < n) {
                    double pct = perCore[idx] * 100;
                    row[j * 2] = "core" + idx;
                    row[j * 2 + 1] = String.format("%5.1f%% %s", pct, Formatter.bar(pct, 10));
                } else {
                    row[j * 2] = "";
                    row[j * 2 + 1] = "";
                }
            }
            rows.add(row);
        }
        String[] headers = new String[cols * 2];
        for (int j = 0; j < cols; j++) {
            headers[j * 2] = "Core";
            headers[j * 2 + 1] = "Usage";
        }
        System.out.println(Formatter.table("", headers, rows));

 // ── 加载 & 上下文 switches ──
        System.out.println(
                Formatter.panel("",
                        "Load (1/5/15m)", String.format("%.2f / %.2f / %.2f",
                                processor.getSystemLoadAverage(3)[0],
                                processor.getSystemLoadAverage(3)[1],
                                processor.getSystemLoadAverage(3)[2]),
                        "Context switches", String.format("%,d", processor.getContextSwitches()),
                        "Interrupts", String.format("%,d", processor.getInterrupts())
                )
        );

        // ── Top processes by CPU ──
        List<OSProcess> procs = Oshi.getOperatingSystem().getProcesses();
        procs.sort((a, b) -> Double.compare(b.getProcessCpuLoadCumulative(), a.getProcessCpuLoadCumulative()));
        if (procs.size() > 10) {
            procs = procs.subList(0, 10);
        }
        if (procs != null && !procs.isEmpty()) {
            System.out.println();
            System.out.println("Top 10 Processes by CPU:");
            List<String[]> procs2 = new ArrayList<>();
            for (OSProcess p : procs) {
                procs2.add(new String[]{
                        String.valueOf(p.getProcessID()),
                        truncate(p.getName(), 24),
                        String.format("%5.1f%%", p.getProcessCpuLoadCumulative() * 100),
                        Formatter.formatBytes(p.getResidentSetSize())
                });
            }
            System.out.println(Formatter.table("", new String[]{"PID", "Name", "CPU", "RSS"}, procs2));
        }
    }

    /**
    * 当前freq。
    * @param processor 处理器
    * @return 当前freq的结果
     */
    private static long currentFreq(CentralProcessor processor) {
        long[] freqs = processor.getCurrentFreq();
        if (freqs == null || freqs.length == 0) {
            return 0;
        }
        long max = 0;
        for (long f : freqs) {
            max = Math.max(max, f);
        }
        return max;
    }

    /**
    * 格式化hz。
    * @param hz hz
    * @return 格式化hz的结果
     */
    private static String formatHz(long hz) {
        if (hz >= 1_000_000_000L) {
            return String.format("%.2f GHz", hz / 1_000_000_000.0);
        }
        if (hz >= 1_000_000L) {
            return String.format("%.1f MHz", hz / 1_000_000.0);
        }
        return hz + " Hz";
    }

    /**
    * truncate。
    * @param s s
    * @param max 最大
    * @return truncate的结果
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}