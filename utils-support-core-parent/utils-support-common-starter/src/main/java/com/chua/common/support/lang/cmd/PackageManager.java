package com.chua.common.support.lang.cmd;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 包管理器工具类，用于检测系统包管理器并执行包安装操作。
 *
 * <p>支持主流包管理器的自动检测与安装操作，提供同步/异步安装以及实时输出回调。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class PackageManager {

 /**
 * 包管理器类型枚举
 */
 public enum Type {
 WINGET("winget", "winget install --id %s --silent --accept-package-agreements", true),
 CHOCO("choco", "choco install -y %s", true),
 BREW("brew", "brew install %s", true),
 APT("apt", "DEBIAN_FRONTEND=noninteractive apt install -y %s", true),
 YUM("yum", "yum install -y %s", true),
 DNF("dnf", "dnf install -y %s", true),
 APK("apk", "apk add %s", true);

 /** 命令字符串 */
 /** 命令 */
 private final String command;
 /** 安装模板 */
 /** Install模板 */
 private final String installTemplate;
 /** 是否可用 */
 /** 可用 */
 private final boolean available;

 Type(String command, String installTemplate, boolean available) {
 this.command = command;
 this.installTemplate = installTemplate;
 this.available = available;
 }

 public String getCommand() { return command; }
 public String getInstallTemplate() { return installTemplate; }
 public boolean isAvailable() { return available; }
 }

 /**
 * 检测当前系统上可用的包管理器
 *
 * @return 可用的包管理器列表
 */
 public static List<Type> detect() {
 return List.of(Type.values()).stream()
 .filter(t -> isAvailable(t.getCommand()))
 .toList();
 }

 /**
 * 检测指定的包管理器命令是否可用
 *
 * @param command 包管理器命令名
 * @return 可用返回 true
 */
 private static boolean isAvailable(String command) {
 try {
 String checkCmd = System.getProperty("os.name").toLowerCase().contains("win")
 ? "where " + command : "which " + command;
 CmdResult result = CmdExecutors.execute(checkCmd, 5, TimeUnit.SECONDS);
 return result.isSuccess();
 } catch (Exception e) {
 return false;
 }
 }

 /**
 * 使用检测到的第一个可用包管理器同步安装包
 *
 * @param packageId 包 ID（如 "Pandoc"、"python3"）
 * @return 安装结果
 */
 public static CmdResult install(String packageId) {
 Type pm = detect().stream().findFirst().orElse(null);
 if (pm == null) {
 return CmdResult.builder()
 .exitCode(CmdResult.EXIT_CODE_ERROR)
 .command(packageId)
 .throwable(new UnsupportedOperationException("未检测到可用的包管理器"))
 .build();
 }
 String cmd = String.format(pm.getInstallTemplate(), packageId);
 log.info("使用 {} 安装 {}: {}", pm.getCommand(), packageId, cmd);
 return CmdExecutors.execute(cmd, 300, TimeUnit.SECONDS);
 }

 /**
 * 使用指定包管理器同步安装包，支持实时输出
 *
 * @param packageId 包 ID
 * @param callback 实时输出回调
 */
 public static CmdResult install(String packageId, LineCallback callback) {
 Type pm = detect().stream().findFirst().orElse(null);
 if (pm == null) {
 callback.onLine("未检测到可用的包管理器");
 CmdResult result = CmdResult.builder()
 .exitCode(CmdResult.EXIT_CODE_ERROR)
 .command(packageId)
 .throwable(new UnsupportedOperationException("未检测到可用的包管理器"))
 .build();
 callback.onComplete(result.getExitCode());
 return result;
 }
 String cmd = String.format(pm.getInstallTemplate(), packageId);
 callback.onLine("[" + pm.getCommand() + "] 开始安装 " + packageId + "...");
 return CmdExecutors.executeWithOutput(cmd, 300, TimeUnit.SECONDS, callback);
 }

 /**
 * 异步安装包
 *
 * @param packageId 包 ID
 * @param callback 结果回调
 */
 public static void installAsync(String packageId, CmdCallback callback) {
 Type pm = detect().stream().findFirst().orElse(null);
 if (pm == null) {
 callback.onError(packageId, new UnsupportedOperationException("未检测到可用的包管理器"));
 return;
 }
 String cmd = String.format(pm.getInstallTemplate(), packageId);
 CmdExecutors.executeAsync(cmd, 300, TimeUnit.SECONDS, callback);
 }

 /**
 * 使用指定包管理器类型安装包（同步）
 *
 * @param type 包管理器类型
 * @param packageId 包 ID
 * @return 安装结果
 */
 public static CmdResult installWith(Type type, String packageId) {
 String cmd = String.format(type.getInstallTemplate(), packageId);
 return CmdExecutors.execute(cmd, 300, TimeUnit.SECONDS);
 }
}
