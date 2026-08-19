package com.chua.common.support.lang.cmd;

import com.chua.common.support.spi.ServiceProvider;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 命令注册中心 — 管理所有可用的 {@link Cmd} 命令。
 *
 * <p>支持以下特性：</p>
 * <ul>
 *   <li><strong>SPI 自动发现</strong> — 自动扫描所有 {@code @Spi} 标注的 {@link Cmd} 实现</li>
 *   <li><strong>手动注册</strong> — 通过 {@link #register(Cmd)} 动态添加命令</li>
 *   <li><strong>按名查找和执行</strong> — 根据命令名称查找并执行</li>
 *   <li><strong>服务器友好输出</strong> — {@link #listCommands()} 返回格式化列表，
 *       方便通过 SSH、HTTP、WebSocket 等远程返回</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // SPI 自动发现所有命令
 * Commands commands = Commands.create();
 *
 * // 手动注册
 * commands.register(new EchoCmd());
 *
 * // 查找命令
 * Cmd ls = commands.get("ls");
 *
 * // 执行命令
 * CmdResult result = commands.execute("echo", "-u", "hello world");
 * System.out.println(result.getStdout()); // "HELLO WORLD"
 *
 * // 列出所有可用命令
 * for (Cmd cmd : commands.getAll()) {
 *     System.out.println(cmd.name() + " - " + cmd.description());
 * }
 * }</pre>
 *
 * <h3>服务器集成</h3>
 * <p>{@link Commands} 可以嵌入到任何服务端协议中：</p>
 * <pre>{@code
 * // HTTP 请求处理器
 * public void handleRequest(String commandLine) {
 *     String[] parts = commandLine.split(" ");
 *     String cmdName = parts[0];
 *     String[] cmdArgs = Arrays.copyOfRange(parts, 1, parts.length);
 *
 *     CmdResult result = commands.execute(cmdName, cmdArgs);
 *     // 将 result.getStdout() 写入 HTTP 响应
 *     // 将 result.getExitCode() 作为 HTTP 状态码
 * }
 * }</pre>
 *
 * @since 4.0.0.42
 * @see Cmd
 * @see CmdResult
 * @see CommandLine
 */
public final class Commands {

    /** 内部命令容器 */
    private final Map<String, Cmd> commandMap = new ConcurrentHashMap<>();

    /** 创建 Commands 实例 */
    private Commands() {
    }

    // ==================== 工厂方法 ====================

    /**
     * 创建一个新的 {@link Commands} 实例，并通过 SPI 自动发现所有 {@link Cmd} 实现。
     *
     * <p>会自动扫描 classpath 中所有标注了 {@code @Spi} 且实现了 {@link Cmd} 接口的类，
     * 并以 {@link Cmd#name()} 为键注册。</p>
     *
     * @return Commands 实例（包含所有 SPI 发现的命令）
     */
    @Nonnull
    public static Commands create() {
        Commands commands = new Commands();
        commands.discoverFromSpi();
        return commands;
    }

    /**
     * 创建一个空的 {@link Commands} 实例（不进行 SPI 发现）。
     *
     * <p>适用于完全手动注册命令的场景。</p>
     *
     * @return 空的 Commands 实例
     */
    @Nonnull
    public static Commands empty() {
        return new Commands();
    }

    // ==================== 注册 ====================

    /**
     * 注册一个命令。
     *
     * @param cmd 命令实例
     * @return this
     * @throws IllegalArgumentException 如果存在同名的命令
     */
    @Nonnull
    public Commands register(@Nonnull Cmd cmd) {
        String name = cmd.name();
        if (commandMap.containsKey(name)) {
            throw new IllegalArgumentException("命令已存在: " + name);
        }
        commandMap.put(name, cmd);
        return this;
    }

    /**
     * 注册一个命令（如果存在同名，覆盖已有）。
     *
     * @param cmd 命令实例
     * @return this
     */
    @Nonnull
    public Commands registerOrReplace(@Nonnull Cmd cmd) {
        commandMap.put(cmd.name(), cmd);
        return this;
    }

    /**
     * 批量注册命令。
     *
     * @param cmds 命令数组
     * @return this
     */
    @Nonnull
    public Commands registerAll(@Nonnull Cmd... cmds) {
        for (Cmd cmd : cmds) {
            register(cmd);
        }
        return this;
    }

    /**
     * 取消注册指定名称的命令。
     *
     * @param name 命令名称
     * @return this
     */
    @Nonnull
    public Commands unregister(@Nonnull String name) {
        commandMap.remove(name);
        return this;
    }

    // ==================== 查找 ====================

    /**
     * 根据名称获取命令。
     *
     * @param name 命令名称
     * @return {@link Cmd} 实例，未找到返回 null
     */
    @Nullable
    public Cmd get(@Nonnull String name) {
        return commandMap.get(name);
    }

    /**
     * 判断指定名称的命令是否存在。
     *
     * @param name 命令名称
     * @return 如果存在返回 true
     */
    public boolean has(@Nonnull String name) {
        return commandMap.containsKey(name);
    }

    /**
     * 获取所有已注册的命令。
     *
     * @return 不可修改的命令集合
     */
    @Nonnull
    public Collection<Cmd> getAll() {
        return Collections.unmodifiableCollection(commandMap.values());
    }

    /**
     * 获取所有已注册的命令名称列表。
     *
     * @return 命令名称列表
     */
    @Nonnull
    public List<String> getNames() {
        return new ArrayList<>(commandMap.keySet());
    }

    /**
     * 获取已注册的命令数量。
     *
     * @return 命令数量
     */
    public int size() {
        return commandMap.size();
    }

    // ==================== 执行 ====================

    /**
     * 根据名称和参数执行命令。
     *
     * @param name     命令名称
     * @param rawArgs  原始命令行参数（不包含命令名称本身）
     * @return 命令执行结果
     * @throws IllegalArgumentException 如果命令不存在或参数解析失败
     */
    @Nonnull
    public CmdResult execute(@Nonnull String name, @Nonnull String... rawArgs) {
        Cmd cmd = commandMap.get(name);
        if (cmd == null) {
            return CmdResult.builder()
                    .exitCode(127)
                    .stderr("命令不存在: " + name
                            + "。可用命令: " + String.join(", ", commandMap.keySet()))
                    .command(name + " " + String.join(" ", rawArgs))
                    .build();
        }
        try {
            return cmd.execute(rawArgs);
        } catch (IllegalArgumentException e) {
            return CmdResult.builder()
                    .exitCode(2)
                    .stderr(e.getMessage())
                    .command(name + " " + String.join(" ", rawArgs))
                    .throwable(e)
                    .build();
        } catch (Exception e) {
            return CmdResult.builder()
                    .exitCode(1)
                    .stderr("执行异常: " + e.getMessage())
                    .command(name + " " + String.join(" ", rawArgs))
                    .throwable(e)
                    .build();
        }
    }

    /**
     * 从完整命令行字符串解析并执行命令。
     *
     * <p>将一行字符串按空格拆分为命令名称和参数，然后执行。
     * 支持引号包裹的参数（如 {@code echo "hello world"}）。</p>
     *
     * @param commandLine 完整的命令行字符串（如 {@code "echo --uppercase hello world"}）
     * @return 命令执行结果
     */
    @Nonnull
    public CmdResult executeLine(@Nonnull String commandLine) {
        String[] parts = splitCommandLine(commandLine);
        if (parts.length == 0) {
            return CmdResult.builder()
                    .exitCode(0)
                    .command("")
                    .build();
        }
        String name = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return execute(name, args);
    }

    // ==================== 列表/帮助 ====================

    /**
     * 获取所有命令的格式化描述列表。
     *
     * <p>返回格式每行如："  echo   输出文本"，
     * 适合直接输出到终端或远程客户端。</p>
     *
     * @return 格式化命令列表字符串
     */
    @Nonnull
    public String listCommands() {
        if (commandMap.isEmpty()) {
            return "（无可用命令）\n";
        }

        // 计算命令名称最大宽度
        int maxNameWidth = 0;
        for (Cmd cmd : commandMap.values()) {
            if (cmd.name().length() > maxNameWidth) {
                maxNameWidth = cmd.name().length();
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("可用命令:\n");
        for (Cmd cmd : commandMap.values()) {
            sb.append("  ").append(cmd.name());
            // 对齐
            int padding = maxNameWidth - cmd.name().length() + 2;
            for (int i = 0; i < padding; i++) {
                sb.append(' ');
            }
            sb.append(cmd.description()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 获取命令的帮助信息（相当于执行 {@code cmd --help}）。
     *
     * @param name 命令名称
     * @return 帮助文本，如果命令不存在返回错误信息
     */
    @Nonnull
    public String help(@Nonnull String name) {
        Cmd cmd = commandMap.get(name);
        if (cmd == null) {
            return "命令不存在: " + name + "\n" + listCommands();
        }
        // 获取 CLI 帮助文本
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        cmd.cli().printHelp(new java.io.PrintStream(baos));
        return baos.toString();
    }

    // ==================== 内部方法 ====================

    /**
     * 通过 SPI 自动发现所有 {@link Cmd} 实现并注册。
     */
    private void discoverFromSpi() {
        Map<String, Cmd> spiCommands = ServiceProvider.of(Cmd.class).list();
        for (Map.Entry<String, Cmd> entry : spiCommands.entrySet()) {
            String spiName = entry.getKey();
            Cmd cmd = entry.getValue();
            // SPI 名称优先，如果 Cmd 有自定义 name() 则用 name()
            String cmdName = cmd.name();
            if (!commandMap.containsKey(cmdName)) {
                commandMap.put(cmdName, cmd);
            }
        }
    }

    /**
     * 按空格拆分命令行字符串，支持双引号包裹的参数。
     *
     * @param line 命令行字符串
     * @return 拆分后的参数数组
     */
    @Nonnull
    private static String[] splitCommandLine(@Nonnull String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.toArray(new String[0]);
    }
}
