package com.chua.common.support.lang.cmd;

import org.jspecify.annotations.NullMarked;

/**
 * 命令接口 — 代表一个可注册、可发现、可执行的命令。
 *
 * <p>类似 Unix Shell 中的 {@code ls}、{@code ll}、{@code echo} 等命令，
 * 每个 {@link Cmd} 实例包含：</p>
 * <ul>
 *   <li>命令名称 — 用于在终端或服务器端标识和查找命令</li>
 *   <li>CLI 定义 — 通过 {@link CommandLine} 声明支持的选项和参数</li>
 *   <li>执行逻辑 — 解析参数后执行具体操作并返回结果</li>
 * </ul>
 *
 * <h3>实现示例</h3>
 * <pre>{@code
 * // 用 @Spi("echo") 注册即可被 Commands 自动发现
 * &#64;Spi("echo")
 * public class EchoCmd implements Cmd {
 *     &#64;Override
 *     public String name() { return "echo"; }
 *
 *     &#64;Override
 *     public String description() { return "输出文本"; }
 *
 *     &#64;Override
 *     public CommandLine cli() {
 *         return CommandLine.builder()
 *                 .programName("echo")
 *                 .description("将文本输出到标准输出")
 *                 .option(CliOption.builder()
 *                         .longName("uppercase").shortName("u")
 *                         .description("转换为大写").flag(true).build())
 *                 .disableHelpOption()
 *                 .build();
 *     }
 *
 *     &#64;Override
 *     public CmdResult execute(CommandLine.Result args) {
 *         String text = String.join(" ", args.positionalArgs());
 *         if (args.has("uppercase")) {
 *             text = text.toUpperCase();
 *         }
 *         return CmdResult.builder()
 *                 .exitCode(0).stdout(text).command("echo " + text)
 *                 .build();
 *     }
 * }
 * }</pre>
 *
 * <h3>服务器远程执行</h3>
 * <p>实现 {@link Cmd} 接口的命令可以注册到 {@link Commands} 注册中心，
 * 然后通过服务器（如 HTTP、SSH、WebSocket）暴露给远程客户端执行。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see CommandLine
 * @see Commands
 * @see CmdResult
 */
@NullMarked
public interface Cmd {

    /**
     * 获取命令名称。
     *
     * <p>用于在注册中心和服务器端标识命令，应当简短且唯一。
     * 如 {@code "ls"}、{@code "echo"}</p>
     *
     * @return 命令名称（非空）
     */
    String name();

    /**
     * 获取命令的描述信息。
     *
     * @return 命令描述，用于帮助信息和命令列表
     */
    String description();

    /**
     * 获取命令的 CLI 定义。
     *
     * <p>定义了该命令支持的所有选项和参数，用于参数解析和帮助信息生成。</p>
     *
     * @return {@link CommandLine} 实例
     */
    CommandLine cli();

    /**
     * 执行命令。
     *
     * @param args 已解析的命令行参数（来自 {@link CommandLine#parse(String[])}）
     * @return 命令执行结果
     */
    CmdResult execute(CommandLine.Result args);

    /**
     * 直接通过原始参数执行命令。
     *
     * <p>默认实现自动调用 {@link #cli()} 解析参数后执行。
     * 子类可重写此方法以跳过参数解析或添加预处理逻辑。</p>
     *
     * @param rawArgs 原始命令行参数数组
     * @return 命令执行结果
     * @throws IllegalArgumentException 如果参数解析失败
     */
    default CmdResult execute(String[] rawArgs) {
        CommandLine.Result parsed = cli().parse(rawArgs);
        // 自动处理 --help
        if (parsed.has("help")) {
            cli().printHelp(System.out);
            return CmdResult.builder()
                    .exitCode(0)
                    .command(name() + " --help")
                    .build();
        }
        return execute(parsed);
    }
}
