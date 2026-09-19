package com.chua.oshi.support.cli.command;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.Cpu;
import com.chua.oshi.support.Mem;
import com.chua.oshi.support.Network;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.Sys;
import com.chua.oshi.support.SysFile;
import com.chua.oshi.support.cli.display.Formatter;

import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.Sensors;

import java.util.List;

/**
 * oshc 状态 — 全局仪表盘：系统概况、CPU、内存、磁盘、网络一览。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class StatusCommand extends AbstractCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Dashboard: system overview, CPU, memory, disk, network";
    }

    @Override
    public void execute(CommandLine options) {
        HardwareAbstractionLayer hw = Oshi.getHardware();

 // ── 系统 信息 ──
        Sys sys = Oshi.newSys();
        oshi.hardware.ComputerSystem cs = hw.getComputerSystem();
        System.out.println();
        System.out.println(
                Formatter.panel("oshc status",
                        "OS", sys.getOsName() == null ? "N/A" : sys.getOsName(),
                        "Arch", sys.getOsArch() == null ? "N/A" : sys.getOsArch(),
                        "Host", cs.getManufacturer() == null ? "N/A" : cs.getManufacturer() + " " + cs.getModel(),
                        "Serial", cs.getSerialNumber() == null ? "N/A" : cs.getSerialNumber(),
                        "IP", sys.getComputerIp() == null ? "N/A" : sys.getComputerIp()
                )
        );

        // ── CPU ──
        System.out.println();
        Cpu cpu = Oshi.newCpu(1000);
        double used = Math.max(0, cpu.getUsed());
        System.out.println("CPU: " + cpu.getCpuNum() + " cores — " + String.format("%.1f%%", used));
        System.out.println("  " + Formatter.bar(used, 30));
        System.out.println("  " + pad("user", cpu.getUser()) + pad("sys", cpu.getSys())
                + pad("wait", cpu.getWait()) + pad("idle", cpu.getFree()));

 // ── 内存 ──
        System.out.println();
        Mem mem = Oshi.newMem();
        System.out.println("Memory: " + Formatter.formatBytes(mem.getUsed()) + " / "
                + Formatter.formatBytes(mem.getTotal()) + " — " + String.format("%.1f%%", mem.getUsage()));
        System.out.println("  " + Formatter.bar(mem.getUsage(), 30));
        System.out.println("  " + Formatter.formatBytes(mem.getFree()) + " free");

 // ── 虚拟 内存 ──
        oshi.hardware.GlobalMemory globalMem = hw.getMemory();
        oshi.hardware.VirtualMemory vMem = globalMem.getVirtualMemory();
        if (vMem != null) {
            long swapTotal = vMem.getSwapTotal();
            if (swapTotal > 0) {
                long swapUsed = vMem.getSwapUsed();
                double swapPct = swapTotal > 0 ? (double) swapUsed / swapTotal * 100 : 0;
                System.out.println("Swap: " + Formatter.formatBytes(swapUsed) + " / "
                        + Formatter.formatBytes(swapTotal) + " — " + String.format("%.1f%%", swapPct));
            }
        }

 // ── Physical 内存 modules ──
        List<oshi.hardware.PhysicalMemory> pmms = globalMem.getPhysicalMemory();
        if (!pmms.isEmpty()) {
            System.out.println("  Physical: " + pmms.size() + " module(s)");
            for (oshi.hardware.PhysicalMemory pm : pmms) {
                System.out.println("    " + Formatter.formatBytes(pm.getCapacity()) + " "
                        + (pm.getBankLabel() == null ? "" : pm.getBankLabel()));
            }
        }

        // ── Disk (file system) ──
        System.out.println();
        List<SysFile> sysFiles = Oshi.newSysFile();
        System.out.println("Disk Partitions (" + sysFiles.size() + "):");
        for (SysFile sf : sysFiles) {
            double pct = sf.getUsage();
            System.out.printf("  %-14s  %-6s  %8s / %-8s  %5.1f%%  %s%n",
                    sf.getDirName(), sf.getTypeName(),
                    Formatter.formatBytes(sf.getUsed()), Formatter.formatBytes(sf.getTotal()),
                    pct, Formatter.bar(pct, 15));
        }

        // ── Physical disks ──
        List<oshi.hardware.HWDiskStore> disks = hw.getDiskStores();
        if (!disks.isEmpty()) {
            System.out.println("Physical Disks (" + disks.size() + "):");
            for (oshi.hardware.HWDiskStore d : disks) {
                System.out.printf("  %-20s  %-6s  %8s  %s%n",
                        d.getModel() == null ? "N/A" : d.getModel(),
                        d.getPartitions().isEmpty() ? "" : d.getPartitions().size() + "p",
                        Formatter.formatBytes(d.getSize()),
                        d.getSerial() == null ? "" : d.getSerial().trim());
            }
        }

        // ── Network ──
        System.out.println();
        List<Network> nets = Oshi.newNetwork();
        System.out.println("Network Interfaces (" + nets.size() + "):");
        for (Network net : nets) {
            String[] ipv4 = net.getIpv4();
            String ip = (ipv4 != null && ipv4.length > 0) ? ipv4[0] : "N/A";
            System.out.printf("  %-16s  %-20s  tx:%8s  rx:%8s%n",
                    net.getName(), ip,
                    Formatter.formatBytes(net.getTransmitBytes()),
                    Formatter.formatBytes(net.getReceiveBytes()));
        }

        // ── Sensors ──
        Sensors sensors = hw.getSensors();
        System.out.println();
        System.out.println("Sensors:");
        double cpuTemp = sensors.getCpuTemperature();
        if (cpuTemp > 0) {
            System.out.println("  CPU Temperature: " + String.format("%.1f °C", cpuTemp));
        }
        int[] fans = sensors.getFanSpeeds();
        if (fans.length > 0) {
            int maxFan = 0;
            for (int f : fans) {
                maxFan = Math.max(maxFan, f);
            }
            System.out.println("  Fan Speed: " + maxFan + " RPM");
        }
        double voltage = sensors.getCpuVoltage();
        if (voltage > 0) {
            System.out.println("  CPU Voltage: " + String.format("%.2f V", voltage));
        }

        // ── Power supply ──
        List<oshi.hardware.PowerSource> ps = hw.getPowerSources();
        if (ps != null && !ps.isEmpty()) {
            System.out.println();
            System.out.println("Power Sources (" + ps.size() + "):");
            for (oshi.hardware.PowerSource p : ps) {
                System.out.printf("  %-20s  %.1f%%  %s%n",
                        p.getName(), p.getRemainingCapacityPercent(),
                        p.isCharging() ? "charging" : "discharging");
            }
        }

        System.out.println();
    }

    /**
     * pad。
     * @param label 标签
     * @param val val
     * @return pad的结果
     */
    private static String pad(String label, double val) {
        return String.format("%-5s %6.1f%%  ", label, val);
    }
}
