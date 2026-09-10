package com.chua.remote.agent.vnc;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * VNC 服务自启器（内嵌 TigerVNC 二进制）。
 *
 * <p>本机缺失 VNC 服务时，从 {@code utils-support-resource-vnc} jar 解压内嵌的
 * TigerVNC 二进制到临时目录并启动：</p>
 * <ul>
 *   <li><b>Linux</b>：{@code Xvnc :N}（绑定 {@code $DISPLAY} 或独立 display）</li>
 *   <li><b>Windows</b>：{@code winvnc4.exe -install|-run}（内嵌服务端）</li>
 *   <li><b>Mac</b>：无内嵌二进制，兜底开启系统屏幕共享（launchctl）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class VncServerStarter {

    /** VNC 服务默认端口（RFB） */
    private static final int VNC_PORT = 5900;

    private VncServerStarter() {
    }

    /**
     * 自启 VNC 服务（内嵌二进制）。
     *
     * @param platform 平台类型
     * @return true=启动成功；false=启动失败
     */
    public static boolean startVncServer(VncServiceProbe.Platform platform) {
        switch (platform) {
            case LINUX:
                return startLinuxVnc();
            case MAC:
                return startMacVnc();
            case WINDOWS:
                return startWindowsVnc();
            default:
                log.warn("不支持的平台，无法自启 VNC 服务: {}", platform);
                return false;
        }
    }

    /**
     * Linux：从 resource-vnc 解压 Xvnc 并绑定当前 DISPLAY。
     */
    private static boolean startLinuxVnc() {
        try {
            Path xvnc = extractEmbedded("linux/bin/Xvnc");
            if (xvnc == null) {
                log.warn("内嵌 Xvnc 不存在（resource-vnc 未提供 Linux 二进制）");
                return false;
            }
            xvnc.toFile().setExecutable(true);
            String display = System.getenv("DISPLAY");
            if (display != null && !display.isBlank() && !"null".equalsIgnoreCase(display.trim())) {
                // 有 X 会话：x0vncserver 绑定现有 DISPLAY（相当于转发/共享当前桌面）
                Path x0vnc = extractEmbedded("linux/bin/x0vncserver");
                if (x0vnc != null && runBg(x0vnc.toString(), "-display", display,
                        "-passwordfile", "unused", "-nopw")) {
                    log.info("VNC 服务自启成功（x0vncserver 绑定 display={}）", display);
                    return true;
                }
            }
            // 无 DISPLAY：Xvnc 独立虚拟 display :1
            if (runBg(xvnc.toString(), ":1", "-geometry", "1920x1080", "-depth", "24")) {
                log.info("VNC 服务自启成功（Xvnc :1）");
                return true;
            }
            log.warn("VNC 服务自启失败（Linux）：Xvnc/x0vncserver 启动失败");
        } catch (Exception e) {
            log.error("VNC 服务自启异常（Linux）", e);
        }
        return false;
    }

    /**
     * Mac：无内嵌二进制，开启系统屏幕共享。
     */
    private static boolean startMacVnc() {
        if (runOk("sudo", "launchctl", "load", "-w", "/System/Library/LaunchDaemons/com.apple.screensharing.plist")) {
            log.info("VNC 服务自启成功（Mac 屏幕共享）");
            return true;
        }
        log.warn("VNC 服务自启失败（Mac）：屏幕共享不可用");
        return false;
    }

    /**
     * Windows：从 resource-vnc 解压 winvnc4.exe 并启动服务。
     */
    private static boolean startWindowsVnc() {
        try {
            Path winvnc = extractEmbedded("windows/winvnc4.exe");
            if (winvnc == null) {
                log.warn("内嵌 winvnc4.exe 不存在（resource-vnc 未提供 Windows 二进制）");
                return false;
            }
            winvnc.toFile().setExecutable(true);
            // 直接以 -run 后台启动（GUI 服务进程，前台运行会卡住调用线程）
            if (runBg(winvnc.toString(), "-run")) {
                log.info("VNC 服务自启成功（winvnc4.exe -run 后台运行）");
                return true;
            }
            log.warn("VNC 服务自启失败（Windows）：winvnc4.exe 启动失败");
        } catch (Exception e) {
            log.error("VNC 服务自启异常（Windows）", e);
        }
        return false;
    }

    /**
     * 从 classpath 解压内嵌二进制到临时目录。
     *
     * @param resource 资源相对路径（如 linux/bin/Xvnc）
     * @return 解压后的文件路径（资源缺失返回 null）
     */
    private static Path extractEmbedded(String resource) {
        String fullPath = "vnc/" + resource;
        InputStream in = VncServerStarter.class.getResourceAsStream("/" + fullPath);
        if (in == null) {
            fullPath = resource;
            in = VncServerStarter.class.getResourceAsStream("/" + fullPath);
        }
        if (in == null) {
            log.debug("内嵌资源不存在: {}", resource);
            return null;
        }
        try {
            String name = resource.replace('/', '-').replace('\\', '-');
            Path target = Files.createTempDirectory("remote-vnc").resolve(name);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("内嵌 VNC 二进制已解压: {} -> {}", resource, target);
            return target;
        } catch (IOException e) {
            log.error("解压内嵌 VNC 二进制失败: {}", resource, e);
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 执行命令并判定退出码为 0（阻塞等待）。
     */
    private static boolean runOk(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            log.debug("命令执行失败: {} - {}", String.join(" ", cmd), e.getMessage());
            return false;
        }
    }

    /**
     * 后台启动服务进程（不阻塞，命令不退出）。
     */
    private static boolean runBg(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.start();
            return true;
        } catch (Exception e) {
            log.debug("后台启动失败: {} - {}", String.join(" ", cmd), e.getMessage());
            return false;
        }
    }
}