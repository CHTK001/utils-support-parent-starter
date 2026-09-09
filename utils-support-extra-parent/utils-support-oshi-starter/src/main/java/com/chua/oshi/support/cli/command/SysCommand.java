package com.chua.oshi.support.cli.command;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.Sys;
import com.chua.oshi.support.cli.display.Formatter;

import oshi.SystemInfo;
import oshi.hardware.*;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSService;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * oshc sys — 系统信息：OS、主机、CPU、磁盘、网络、进程统计一览。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Parameters(commandDescription = "System info: OS, hostname, CPU, processes")
public final class SysCommand extends AbstractCommand {

    @Parameter(names = {"--services"}, description = "List running OS services")
    private boolean services;

    @Override
    public String name() {
        return "sys";
    }

    @Override
    public String description() {
        return "System info: OS, hostname, CPU, processes";
    }

    @Override
    public void execute() {
        if (help) {
            printHelp();
            return;
        }

        HardwareAbstractionLayer hw = Oshi.getHardware();
        OperatingSystem os = Oshi.getOperatingSystem();
        ComputerSystem cs = hw.getComputerSystem();
        Sys sys = Oshi.newSys();

        System.out.println();
        System.out.println(
                Formatter.panel("oshc sys",
                        "OS", sys.getOsName() == null ? "N/A" : sys.getOsName(),
                        "Arch", sys.getOsArch() == null ? "N/A" : sys.getOsArch(),
                        "IP", sys.getComputerIp() == null ? "N/A" : sys.getComputerIp(),
                        "Manufacturer", cs.getManufacturer() == null ? "N/A" : cs.getManufacturer(),
                        "Model", cs.getModel() == null ? "N/A" : cs.getModel(),
                        "Serial", cs.getSerialNumber() == null ? "N/A" : cs.getSerialNumber(),
                        "UUID", cs.getUUID() == null ? "N/A" : cs.getUUID()
                )
        );

        // ── Firmware & BIOS ──
        Firmware fw = cs.getFirmware();
        if (fw != null) {
            System.out.println(
                    Formatter.panel("Firmware",
                            "Manufacturer", fw.getManufacturer() == null ? "N/A" : fw.getManufacturer(),
                            "Name", fw.getName() == null ? "N/A" : fw.getName(),
                            "Description", fw.getDescription() == null ? "N/A" : fw.getDescription(),
                            "Version", fw.getVersion() == null ? "N/A" : fw.getVersion(),
                            "Release Date", fw.getReleaseDate() == null ? "N/A" : fw.getReleaseDate()
                    )
            );
        }

        // ── Baseboard ──
        oshi.hardware.Baseboard bb = cs.getBaseboard();
        if (bb != null) {
            System.out.println(
                    Formatter.panel("Baseboard",
                            "Manufacturer", bb.getManufacturer() == null ? "N/A" : bb.getManufacturer(),
                            "Model", bb.getModel() == null ? "N/A" : bb.getModel(),
                            "Serial", bb.getSerialNumber() == null ? "N/A" : bb.getSerialNumber()
                    )
            );
        }

        // ── CPU summary ──
        System.out.println();
        CentralProcessor proc = hw.getProcessor();
        System.out.println("CPU: " + (proc.getProcessorIdentifier().getName() == null ? "N/A"
                : proc.getProcessorIdentifier().getName().trim()));
        System.out.println(
                Formatter.panel("",
                        "Physical cores", String.valueOf(proc.getPhysicalProcessorCount()),
                        "Logical cores", String.valueOf(proc.getLogicalProcessorCount()),
                        "Max freq", formatHz(proc.getMaxFreq())
                )
        );

        // ── Memory summary ──
        GlobalMemory mem = hw.getMemory();
        System.out.println();
        System.out.println("Memory: " + Formatter.formatBytes(mem.getTotal()) + " total, "
                + Formatter.formatBytes(mem.getAvailable()) + " available");

        // ── Process stats ──
        System.out.println();
        int totalProcs = os.getProcessCount();
        int running = 0;
        int sleeping = 0;
        int stopped = 0;
        int zombie = 0;
        int other = 0;
        for (oshi.software.os.OSProcess p : os.getProcesses(0, 0)) {
            switch (p.getState()) {
                case RUNNING -> running++;
                case SLEEPING -> sleeping++;
                case STOPPED -> stopped++;
                case ZOMBIE -> zombie++;
                default -> other++;
            }
        }
        System.out.println("Processes: " + totalProcs + " total");
        System.out.println(
                Formatter.panel("",
                        "Running", String.valueOf(running),
                        "Sleeping", String.valueOf(sleeping),
                        "Stopped", String.valueOf(stopped),
                        "Zombie", String.valueOf(zombie),
                        "Other", String.valueOf(other)
                )
        );

        // ── System uptime ──
        System.out.println("Uptime: " + formatDuration(os.getSystemUptime()));
        System.out.println("Boot Time: " + os.getSystemBootTime());

        // ── Display adapters ──
        List<oshi.hardware.Display> displays = hw.getDisplays();
        if (displays != null && !displays.isEmpty()) {
            System.out.println();
            System.out.println("Displays (" + displays.size() + "):");
            for (oshi.hardware.Display d : displays) {
                byte[] edid = d.getEdid();
                int w = 0;
                int h = 0;
                if (edid != null && edid.length >= 21) {
                    w = ((edid[18] & 0xff) | ((edid[19] & 0xff) << 8));
                    h = ((edid[20] & 0xff) | ((edid[21] & 0xff) << 8));
                }
                System.out.println("  " + (w > 0 ? w + "x" + h : "unknown resolution"));
            }
        }

        // ── Sound cards ──
        List<SoundCard> sounds = hw.getSoundCards();
        if (sounds != null && !sounds.isEmpty()) {
            System.out.println();
            System.out.println("Sound Cards (" + sounds.size() + "):");
            for (SoundCard s : sounds) {
                System.out.println("  " + s.getName());
            }
        }

        // ── Battery ──
        oshi.hardware.PowerSource[] ps = hw.getPowerSources();
        if (ps != null && ps.length > 0) {
            System.out.println();
            System.out.println("Power Sources (" + ps.length + "):");
            for (oshi.hardware.PowerSource p : ps) {
                System.out.printf("  %-25s  remaining=%.1f%%  %s%n",
                        p.getName(), p.getRemainingCapacityPercent(),
                        p.isCharging() ? "⚡ charging" : "");
            }
        }

        // ── Services ──
        if (services) {
            List<OSService> svcs = os.getServices();
            if (svcs != null && !svcs.isEmpty()) {
                System.out.println();
                System.out.println("Running Services (" + svcs.size() + "):");
                List<String[]> srow = new ArrayList<>();
                for (OSService svc : svcs) {
                    srow.add(new String[]{
                            String.valueOf(svc.getProcessId()),
                            svc.getName(),
                            svc.getState().name()
                    });
                }
                System.out.println(Formatter.table("", new String[]{"PID", "Name", "State"}, srow));
            }
        }

        System.out.println();
    }

    private static String formatHz(long hz) {
        if (hz >= 1_000_000_000L) {
            return String.format("%.2f GHz", hz / 1_000_000_000.0);
        }
        if (hz >= 1_000_000L) {
            return String.format("%.1f MHz", hz / 1_000_000.0);
        }
        return hz + " Hz";
    }

    private static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "N/A";
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }
}