package com.chua.oshi.support.cli.command;

import com.chua.common.support.utils.CommandLine;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.SysFile;
import com.chua.oshi.support.cli.display.Formatter;

import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.hardware.HardwareAbstractionLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * oshc disk — 磁盘明细：物理磁盘 + 分区/挂载点。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DiskCommand extends AbstractCommand {

    @Override
    public String name() {
        return "disk";
    }

    @Override
    public String description() {
        return "Disk details: physical disks, partitions, usage";
    }

    @Override
    public void execute(CommandLine options) {
        HardwareAbstractionLayer hw = Oshi.getHardware();

        // ── Physical disks ──
        List<HWDiskStore> disks = hw.getDiskStores();
        System.out.println();
        System.out.println("Physical Disks (" + disks.size() + "):");
        List<String[]> rows = new ArrayList<>();
        for (HWDiskStore d : disks) {
            rows.add(new String[]{
                    d.getModel() == null ? "N/A" : d.getModel(),
                    d.getSerial() == null ? "" : d.getSerial().trim(),
                    Formatter.formatBytes(d.getSize()),
                    d.getPartitions().size() + "",
                    (d.getTransferTime() > 0 ? "active" : "idle")
            });
        }
        if (rows.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.println(Formatter.table("", new String[]{"Model", "Serial", "Size", "Partitions", "State"}, rows));
        }

        // ── Partitions ──
        System.out.println();
        System.out.println("Partitions:");
        List<String[]> prow = new ArrayList<>();
        for (HWDiskStore d : disks) {
            for (HWPartition p : d.getPartitions()) {
                prow.add(new String[]{
                        p.getIdentification() == null ? "" : p.getIdentification(),
                        d.getModel() == null ? "" : d.getModel(),
                        p.getMountPoint() == null ? "" : p.getMountPoint(),
                        Formatter.formatBytes(p.getSize()),
                        p.getType() == null ? "" : p.getType()
                });
            }
        }
        if (prow.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.println(Formatter.table("", new String[]{"ID", "Disk", "Mount", "Size", "Type"}, prow));
        }

        // ── File system usage ──
        System.out.println();
        List<SysFile> sysFiles = Oshi.newSysFile();
        System.out.println("File System Usage (" + sysFiles.size() + "):");
        List<String[]> frow = new ArrayList<>();
        long totalUsed = 0;
        long totalAll = 0;
        for (SysFile sf : sysFiles) {
            totalUsed += sf.getUsed();
            totalAll += sf.getTotal();
            frow.add(new String[]{
                    sf.getDirName(),
                    sf.getTypeName(),
                    Formatter.formatBytes(sf.getTotal()),
                    Formatter.formatBytes(sf.getUsed()),
                    Formatter.formatBytes(sf.getFree()),
                    String.format("%5.1f%%", sf.getUsage()),
                    Formatter.bar(sf.getUsage(), 15)
            });
        }
        if (frow.isEmpty()) {
            System.out.println("  (none)");
        } else {
            System.out.println(Formatter.table("", new String[]{"Mount", "Type", "Total", "Used", "Free", "Usage", "Bar"}, frow));
        }
        double pct = totalAll > 0 ? (double) totalUsed / totalAll * 100 : 0;
        System.out.println(Formatter.panel("",
                "Aggregate", Formatter.formatBytes(totalUsed) + " / " + Formatter.formatBytes(totalAll)
                        + " (" + String.format("%.1f%%", pct) + ")"));
    }
}