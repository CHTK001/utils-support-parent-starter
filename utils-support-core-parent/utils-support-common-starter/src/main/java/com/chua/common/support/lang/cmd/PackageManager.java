package com.chua.common.support.lang.cmd;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

 /**
  * 命令字符串
  */
 private final String command;
 /**
  * 安装模板
  */
 private final String installTemplate;
 /**
  * 是否可用
  */
 private final boolean available;

 Type(String command, String installTemplate, boolean available) {
 this.command = command;
 this.installTemplate = installTemplate;
 this.available = available;
 }

 /**
  * 获取Command
  */
 public String getCommand() { return command; }
 /**
  * 获取InstallTemplate
  */
 public String getInstallTemplate() { return installTemplate; }
 /**
  * 是否Available
  */
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
 boolean win = System.getProperty("os.name").toLowerCase().contains("win");
 CmdResult result = CmdExecutors.execute(win
 ? new String[]{"where", command} : new String[]{"which", command}, 5, TimeUnit.SECONDS);
 return result.isSuccess();
 } catch (Exception e) {
 return false;
 }
 }

 /**
  * 把安装模板拆成进程参数数组，{@code %s} 位置整体替换为包 ID。
  *
  * <p>数组形式不经 shell 解析，包 ID 里的 {@code &}、{@code |} 等字符不会被当成命令执行；
  * 模板开头 {@code KEY=VALUE} 形式的前缀（如 apt 的 DEBIAN_FRONTEND）不属于命令，
  * 由 {@link #installEnv(Type)} 单独作为环境变量下传。</p>
  *
  * @param type 包管理器类型
  * @param packageId 包 ID
  * @return 进程参数数组
  */
 static String[] installArgs(Type type, String packageId) {
 String[] tokens = type.getInstallTemplate().trim().split("\\s+");
 List<String> args = new ArrayList<>();
 for (int i = 0; i < tokens.length; i++) {
 if (i == 0 && isEnvPrefix(tokens[i])) {
 continue;
 }
 args.add("%s".equals(tokens[i]) ? packageId : tokens[i]);
 }
 return args.toArray(String[]::new);
 }

 /**
  * 安装模板开头的环境变量。
  *
  * @param type 包管理器类型
  * @return 环境变量；模板无 {@code KEY=VALUE} 前缀时返回 null
  */
 static Map<String, String> installEnv(Type type) {
 String first = type.getInstallTemplate().trim().split("\\s+")[0];
 if (!isEnvPrefix(first)) {
 return null;
 }
 int split = first.indexOf('=');
 return Map.of(first.substring(0, split), first.substring(split + 1));
 }

 /**
  * 判断是否为 {@code KEY=VALUE} 形式的环境变量前缀。
  *
  * @param token 模板首 token
  * @return 判断结果
  */
 private static boolean isEnvPrefix(String token) {
 int split = token.indexOf('=');
 if (split <= 0) {
 return false;
 }
 for (int i = 0; i < split; i++) {
 char c = token.charAt(i);
 if (!Character.isLetter(c) && c != '_' && !Character.isDigit(c)) {
 return false;
 }
 }
 return true;
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
 String[] args = installArgs(pm, packageId);
 log.info("使用 {} 安装 {}: {}", pm.getCommand(), packageId, String.join(" ", args));
 return CmdExecutors.execute(args, 300, TimeUnit.SECONDS, null, installEnv(pm), null);
 }

 /**
  * 使用指定包管理器同步安装包，支持实时输出
  *
  * @param packageId 包 ID
  * @param callback 实时输出回调
  * @return Cmd结果 对象
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
 String[] args = installArgs(pm, packageId);
 callback.onLine("[" + pm.getCommand() + "] 开始安装 " + packageId + "...");
 return CmdExecutors.executeWithOutput(args, 300, TimeUnit.SECONDS, callback, null, installEnv(pm), null);
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
 String[] args = installArgs(pm, packageId);
 CmdExecutors.executeAsync(args, 300, TimeUnit.SECONDS, callback, null, installEnv(pm), null);
 }

 /**
  * 使用指定包管理器类型安装包（同步）
  *
  * @param type 包管理器类型
  * @param packageId 包 ID
  * @return 安装结果
  */
 public static CmdResult installWith(Type type, String packageId) {
 String[] args = installArgs(type, packageId);
 return CmdExecutors.execute(args, 300, TimeUnit.SECONDS, null, installEnv(type), null);
 }
}
