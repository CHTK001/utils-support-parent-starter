package com.chua.oshi.support.cli.command;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.Mem;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.cli.display.Formatter;

import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PhysicalMemory;
import oshi.hardware.VirtualMemory;

import java.util.ArrayList;
import java.util.List;

/**
* oshc mem — 内存明细：物理内存条、虚拟内存/交换分区。
*
* @author CH
* @since 4.0.0.42
 */
public final class MemCommand extends AbstractCommand {

    @Override
    public String name() {
        return "mem";
    }

    @Override
    public String description() {
        return "Memory details: physical modules, virtual memory, swap";
    }

    @Override
    public void execute(CommandLine options) {
        HardwareAbstractionLayer hw = Oshi.getHardware();
        GlobalMemory globalMemory = hw.getMemory();
        Mem mem = Oshi.newMem();

        System.out.println();
        double usage = mem.getUsage();
        System.out.println("Physical Memory: " + Formatter.formatBytes(mem.getTotal()));
        System.out.println("  " + Formatter.bar(usage, 30) + "  " + String.format("%.1f%%", usage));
        System.out.println(
                Formatter.panel("oshc mem",
                        "Total", Formatter.formatBytes(mem.getTotal()),
                        "Used", Formatter.formatBytes(mem.getUsed()),
                        "Free", Formatter.formatBytes(mem.getFree()),
                        "Usage", String.format("%.1f%%", usage),
                        "Available", Formatter.formatBytes(globalMemory.getAvailable())
                )
        );

 // ── Physical 内存 modules ──
        List<PhysicalMemory> modules = globalMemory.getPhysicalMemory();
        if (modules != null && !modules.isEmpty()) {
            System.out.println();
            System.out.println("Physical Memory Modules (" + modules.size() + "):");
            List<String[]> rows = new ArrayList<>();
            for (PhysicalMemory pm : modules) {
                rows.add(new String[]{
                        pm.getBankLabel() == null ? "N/A" : pm.getBankLabel(),
                        pm.getPartNumber() == null ? "N/A" : pm.getPartNumber(),
                        pm.getMemoryType() == null ? "N/A" : pm.getMemoryType(),
                        Formatter.formatBytes(pm.getCapacity()),
                        pm.getClockSpeed() > 0 ? pm.getClockSpeed() + " MHz" : "N/A",
                        pm.getManufacturer() == null ? "N/A" : pm.getManufacturer()
                });
            }
            System.out.println(Formatter.table("", new String[]{"Bank", "Part Number", "Type", "Capacity", "Speed", "Manufacturer"}, rows));
        }

 // ── 虚拟 内存 / 掉期 ──
        VirtualMemory virtualMemory = globalMemory.getVirtualMemory();
        System.out.println();
        long swapTotal = virtualMemory.getSwapTotal();
        long swapUsed = virtualMemory.getSwapUsed();
        long swapFree = swapTotal - swapUsed;
        double swapPct = swapTotal > 0 ? (double) swapUsed / swapTotal * 100 : 0;
        System.out.println("Swap:");
        System.out.println("  " + Formatter.bar(swapPct, 30) + "  " + String.format("%.1f%%", swapPct));
        System.out.println(
                Formatter.panel("",
                        "Swap total", Formatter.formatBytes(swapTotal),
                        "Swap used", Formatter.formatBytes(swapUsed),
                        "Swap free", Formatter.formatBytes(swapFree),
                        "Swap pages in", String.valueOf(virtualMemory.getSwapPagesIn()),
                        "Swap pages out", String.valueOf(virtualMemory.getSwapPagesOut())
                )
        );

        // ── Top processes by RSS ──
        List<oshi.software.os.OSProcess> procs = Oshi.getOperatingSystem().getProcesses();
        procs.sort((a, b) -> Long.compare(b.getResidentSetSize(), a.getResidentSetSize()));
        if (procs.size() > 10) {
            procs = procs.subList(0, 10);
        }
        if (!procs.isEmpty()) {
            System.out.println();
            System.out.println("Top 10 Processes by RSS:");
            List<String[]> rows = new ArrayList<>();
            for (oshi.software.os.OSProcess p : procs) {
                rows.add(new String[]{
                        String.valueOf(p.getProcessID()),
                        p.getName() == null ? "" : p.getName(),
                        Formatter.formatBytes(p.getResidentSetSize()),
                        Formatter.formatBytes(p.getVirtualSize())
                });
            }
            System.out.println(Formatter.table("", new String[]{"PID", "Name", "RSS", "VSZ"}, rows));
        }
    }
}