package com.chua.common.support.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行参数解析工具。
 *
 * <p>支持三种参数格式：</p>
 * <ul>
 * <li>{@code --key value} — 长选项带值</li>
 * <li>{@code --flag} — 长选项作为布尔标志</li>
 * <li>{@code --key=value} — 长选项带等号</li>
 * </ul>
 *
 * <p>自动适配长短命令（如 {@code --type} 与 {@code -t}）。
 * 通过 {@link #register(String, String, String, String)} 注册命令元数据后，
 * {@link #help()} 可自动输出帮助信息。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * CommandLine cli = CommandLine.parse(args)
 * .register("type", "t", "实现类型", "JdkTcpServer")
 * .register("port", "p", "监听端口", "8888")
 * .register("test", "自检模式")
 * .register("help", "显示帮助");
 * if (cli.isHelp()) {
 * cli.help();
 * return;
 * }
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
*/
public class CommandLine {

 /**
 * 原始参数数组
 */
 private final String[] args;

 /**
 * 解析后的键值映射
 */
 private final Map<String, String> options = new LinkedHashMap<>();

 /**
 * 短名到基础名的映射
 */
 private final Map<String, String> shortToBase = new LinkedHashMap<>();

 /**
 * 长名（带 -- 前缀）到基础名的映射
 */
 private final Map<String, String> longToBase = new LinkedHashMap<>();

 /**
 * 位置参数列表
 */
 private final List<String> positional = new ArrayList<>();

 /**
 * 命令注册列表
 */
 private final List<CommandSpec> specs = new ArrayList<>();

 /**
 * 程序名称（用于 help 头部）
 */
 private String programName = "java";

 /**
 * 创建 命令线 实例
 * @param args 参数
 */
 private CommandLine(String[] args) {
 this.args = args == null ? new String[0] : args;
 parse();
 }

 /**
 * 解析命令行参数。
 *
 * @param args 参数数组
 * @return CommandLine 实例
 */
 public static CommandLine parse(String[] args) {
 return new CommandLine(args);
 }

 /**
 * 注册命令元数据（链式调用）。
 *
 * @param name 长命令名（不含 --）
 * @param shortName 短命令名（不含 -），传 空 表示无短名
 * @param description 命令描述
 * @return this
 */
 public CommandLine register(String name, String shortName, String description) {
 specs.add(new CommandSpec(name, shortName, description, null));
 return this;
 }

 /**
 * 注册带默认值的命令。
 *
 * @param name 长命令名
 * @param shortName 短命令名
 * @param description 描述
 * @param defaultValue 默认值（字符串形式，仅用于 help 显示）
 * @return this
 */
 public CommandLine register(String name, String shortName, String description, String defaultValue) {
 specs.add(new CommandSpec(name, shortName, description, defaultValue));
 return this;
 }

 /**
 * 注册布尔标志命令。
 *
 * @param name 长命令名
 * @param description 描述
 * @return this
 */
 public CommandLine register(String name, String description) {
 specs.add(new CommandSpec(name, null, description, null));
 return this;
 }

 /**
 * 设置程序名称（用于 help 头部）。
 *
 * @param name 程序名
 * @return this
 */
 public CommandLine program(String name) {
 this.programName = name;
 return this;
 }

 /**
 * 打印帮助信息（根据已注册的命令自动组装）。
 */
 public void help() {
 StringBuilder sb = new StringBuilder();
 sb.append("用法: ").append(programName).append(" [选项]\n\n");
 sb.append("选项:\n");
 for (CommandSpec spec : specs) {
 sb.append(" ");
 if (spec.shortName != null) {
 sb.append("--").append(spec.name)
 .append(", -").append(spec.shortName);
 } else {
 sb.append("--").append(spec.name);
 }
 sb.append(" ").append(spec.description);
 if (spec.defaultValue != null) {
 sb.append("（默认: ").append(spec.defaultValue).append("）");
 }
 sb.append("\n");
 }
 System.out.println(sb);
 }

 /**
 * 判断是否请求帮助（--help 或 -h）。
 *
 * @return true 请求帮助
 */
 public boolean isHelp() {
 return has("help");
 }

 /**
 * 获取选项值。
 *
 * @param name 选项基础名
 * @return 值，未设置返回 空
 */
 public String get(String name) {
 return resolve(name);
 }

 /**
 * 获取选项值，带默认值。
 *
 * @param name 选项基础名
 * @param defaultValue 默认值
 * @return 值
 */
 public String get(String name, String defaultValue) {
 String value = resolve(name);
 return value != null ? value : defaultValue;
 }

 /**
 * 获取整数选项值。
 *
 * @param name 选项基础名
 * @param defaultValue 默认值
 * @return 整数值
 */
 public int getInt(String name, int defaultValue) {
 String value = resolve(name);
 if (value == null) {
 return defaultValue;
 }
 try {
 return Integer.parseInt(value);
 } catch (NumberFormatException e) {
 return defaultValue;
 }
 }

 /**
 * 判断布尔标志是否设置。
 *
 * @param name 选项基础名
 * @return true 已设置
 */
 public boolean has(String name) {
 return resolve(name) != null;
 }

 /**
 * 获取所有选项。
 *
 * @return 选项映射
 */
 public Map<String, String> getOptions() {
 return options;
 }

 /**
 * 获取位置参数列表。
 *
 * @return 位置参数
 */
 public List<String> getPositional() {
 return positional;
 }

 /** 解析 */
 private void parse() {
 int index = 0;
 while (index < args.length) {
 String token = args[index];
 if (token.startsWith("--")) {
 registerLong(token);
 index = consumeValue(index, token);
 } else if (token.startsWith("-") && token.length() >= 2) {
 registerShort(token);
 index = consumeValue(index, token);
 } else {
 positional.add(token);
 index++;
 }
 }
 }

/**
 * consume值
 *
 * @param index 索引
 * @param token 令牌
 * @return consume值的结果
*/
private int consumeValue(int index, String token) {
  String value = null;
  String key = token;
  int eq = token.indexOf('=');
  if (eq > 0) {
  value = token.substring(eq + 1);
  key = token.substring(0, eq);
  } else if (index + 1 < args.length && !args[index + 1].startsWith("-")) {
  value = args[++index];
  }
  options.put(key, value);
  return ++index;
  }

 /**
 * 注册Long
 *
 * @param token 令牌
 */
 private void registerLong(String token) {
 String base = token.substring(2);
 int eq = base.indexOf('=');
 if (eq > 0) {
 base = base.substring(0, eq);
 }
 longToBase.put(base, base);
 }

 /**
 * 注册Short
 *
 * @param token 令牌
 */
 private void registerShort(String token) {
 String base = token.substring(1);
 int eq = base.indexOf('=');
 if (eq > 0) {
 base = base.substring(0, eq);
 }
 shortToBase.put(base, base);
 }

 /**
 * 解析
 *
 * @param name 名称
 * @return resolve的结果
 */
 private String resolve(String name) {
 if (name == null) {
 return null;
 }
 if (name.startsWith("--") || name.startsWith("-")) {
 return options.get(name);
 }
 String value = options.get("--" + name);
 if (value != null) {
 return value;
 }
 for (Map.Entry<String, String> entry : shortToBase.entrySet()) {
 if (entry.getValue().equals(name)) {
 value = options.get(entry.getKey());
 if (value != null) {
 return value;
 }
 }
 }
 return null;
 }

 /**
 * 命令规格内部类。
 * @author CH
 * @since 4.0.0
 */
 private static class CommandSpec {
 final String name; // 名称
 final String shortName; // short名称
 final String description; // description
 final String defaultValue; // 默认值

 CommandSpec(String name, String shortName, String description, String defaultValue) {
 this.name = name;
 this.shortName = shortName;
 this.description = description;
 this.defaultValue = defaultValue;
 }
 }
}
