package com.chua.oshi.support;

import lombok.extern.slf4j.Slf4j;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.CentralProcessor.TickType;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Oshi 系统信息工具类
 *
 * @author CH
 */
@Slf4j
public final class Oshi {

    private static final SystemInfo SYSTEM_INFO = new SystemInfo();
    private static final HardwareAbstractionLayer HARDWARE = SYSTEM_INFO.getHardware();
    private static final OperatingSystem OPERATING_SYSTEM = SYSTEM_INFO.getOperatingSystem();

    private Oshi() {
    }

    /**
     * 获取 CPU 信息。
     */
    public static Cpu newCpu(long tickMillis) {
        Cpu cpu = new Cpu();
        try {
            CentralProcessor processor = HARDWARE.getProcessor();
            cpu.setCpuNum(processor.getLogicalProcessorCount());

            long[] prevTicks = processor.getSystemCpuLoadTicks();
            sleep(tickMillis);
            long[] currTicks = processor.getSystemCpuLoadTicks();

            long userDiff = currTicks[TickType.USER.getIndex()] - prevTicks[TickType.USER.getIndex()];
            long niceDiff = currTicks[TickType.NICE.getIndex()] - prevTicks[TickType.NICE.getIndex()];
            long sysDiff = currTicks[TickType.SYSTEM.getIndex()] - prevTicks[TickType.SYSTEM.getIndex()];
            long idleDiff = currTicks[TickType.IDLE.getIndex()] - prevTicks[TickType.IDLE.getIndex()];
            long ioWaitDiff = currTicks[TickType.IOWAIT.getIndex()] - prevTicks[TickType.IOWAIT.getIndex()];
            long irqDiff = currTicks[TickType.IRQ.getIndex()] - prevTicks[TickType.IRQ.getIndex()];
            long softIrqDiff = currTicks[TickType.SOFTIRQ.getIndex()] - prevTicks[TickType.SOFTIRQ.getIndex()];
            long stealDiff = currTicks[TickType.STEAL.getIndex()] - prevTicks[TickType.STEAL.getIndex()];

            long total = userDiff + niceDiff + sysDiff + idleDiff + ioWaitDiff + irqDiff + softIrqDiff + stealDiff;

            if (total > 0) {
                cpu.setUser(userDiff * 100.0 / total);
                cpu.setSys(sysDiff * 100.0 / total);
                cpu.setWait(ioWaitDiff * 100.0 / total);
                cpu.setFree(idleDiff * 100.0 / total);
                cpu.setUsed(100.0 - cpu.getFree());
            }
        } catch (Exception e) {
            log.warn("采集CPU信息失败", e);
        }
        return cpu;
    }

    /**
     * 获取内存信息。
     */
    public static Mem newMem() {
        Mem mem = new Mem();
        try {
            GlobalMemory memory = HARDWARE.getMemory();
            mem.setTotal(memory.getTotal());
            mem.setUsed(memory.getTotal() - memory.getAvailable());
            mem.setFree(memory.getAvailable());
            if (memory.getTotal() > 0) {
                mem.setUsage((double) (memory.getTotal() - memory.getAvailable()) / memory.getTotal() * 100);
            }
        } catch (Exception e) {
            log.warn("采集内存信息失败", e);
        }
        return mem;
    }

    /**
     * 获取系统信息。
     */
    public static Sys newSys() {
        Sys sys = new Sys();
        try {
            sys.setOsName(OPERATING_SYSTEM.toString());
            sys.setOsArch(System.getProperty("os.arch"));
            sys.setComputerIp(getLocalIp());
        } catch (Exception e) {
            log.warn("采集系统信息失败", e);
        }
        return sys;
    }

    /**
     * 获取文件系统（分区/挂载点）信息列表。
     */
    public static List<SysFile> newSysFile() {
        List<SysFile> list = new ArrayList<>();
        try {
            List<OSFileStore> fileStores = OPERATING_SYSTEM.getFileSystem().getFileStores();
            for (OSFileStore store : fileStores) {
                SysFile sysFile = new SysFile();
                sysFile.setDirName(store.getMount());
                sysFile.setTypeName(store.getType());
                sysFile.setTotal(store.getTotalSpace());
                long used = store.getTotalSpace() - store.getFreeSpace();
                sysFile.setUsed(used);
                sysFile.setFree(store.getFreeSpace());
                if (store.getTotalSpace() > 0) {
                    sysFile.setUsage(used * 100.0 / store.getTotalSpace());
                }
                list.add(sysFile);
            }
        } catch (Exception e) {
            log.warn("采集文件系统信息失败", e);
        }
        return list;
    }

    /**
     * 获取网络接口信息列表。
     */
    public static List<Network> newNetwork() {
        List<Network> list = new ArrayList<>();
        try {
            List<NetworkIF> networkIfs = HARDWARE.getNetworkIFs();
            for (NetworkIF net : networkIfs) {
                Network network = new Network();
                network.setName(net.getName());
                network.setDisplayName(net.getDisplayName());
                network.setMac(net.getMacaddr());
                String[] ipv4 = net.getIPv4addr();
                network.setIpv4(net.getIPv4addr());
                network.setReceiveBytes(net.getBytesRecv());
                network.setTransmitBytes(net.getBytesSent());
                network.setSpeed(net.getSpeed());
                list.add(network);
            }
        } catch (Exception e) {
            log.warn("采集网络信息失败", e);
        }
        return list;
    }

    private static String getLocalIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static SystemInfo getSystemInfo() {
        return SYSTEM_INFO;
    }

    public static HardwareAbstractionLayer getHardware() {
        return HARDWARE;
    }

    public static OperatingSystem getOperatingSystem() {
        return OPERATING_SYSTEM;
    }
}