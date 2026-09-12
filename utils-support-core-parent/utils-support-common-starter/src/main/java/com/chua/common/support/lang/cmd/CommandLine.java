package com.chua.common.support.lang.cmd;

import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.PrintStream;
import java.util.*;
import java.util.function.Consumer;

/**
* 命令行参数解析器，支持 POSIX 风格和 GNU 风格参数解析与组装。
*
* <p>不需要任何第三方依赖（如 commons-cli），纯 JDK 实现。</p>
*
* <h3>支持的特性</h3>
* <ul>
*   <li>{@code --long-name value} 长选项</li>
*   <li>{@code -s value} 短选项</li>
*   <li>{@code --long-name=value} 等号语法</li>
*   <li>{@code --flag} 和 {@code -f} 布尔标志</li>
*   <li>{@code --no-flag} 布尔标志否定</li>
*   <li>位置参数（非选项参数）</li>
*   <li>自动类型转换（String、Integer、Long、Double、Boolean）</li>
*   <li>必需选项校验</li>
*   <li>自动生成帮助信息</li>
*   <li>终止标记 {@code --}（之后的参数视为位置参数）</li>
*   <li>默认内置 {@code --help} / {@code -h} 选项</li>
*   <li><strong>组装（Compose）</strong> — 反向将选项值生成为命令行参数数组</li>
* </ul>
*
* <h3>解析示例</h3>
* <pre>{@code
* CommandLine cli = CommandLine.builder()
*         .programName("myapp")
*         .description("一个示例应用")
*         .option(CliOption.builder()
*                 .longName("port").shortName("p")
*                 .description("监听端口").type(Integer.class)
*                 .defaultValue(8080).build())
*         .option(CliOption.builder()
*                 .longName("verbose").shortName("v")
*                 .description("启用详细输出").flag(true).build())
*         .option(CliOption.builder()
*                 .longName("config").shortName("c")
*                 .description("配置文件路径").required().build())
*         .build();
*
* CommandLine.Result result = cli.parse(args);
*
* if (result.has("help")) {
*     cli.printHelp();
*     return;
* }
* int port = result.getInt("port");
* String config = result.getString("config");
* </pre>
*
* <h3>组装示例</h3>
* <pre>{@code
* // 方式一：Lambda 风格
* String[] args = cli.compose(c -> {
*     c.option("port", 9090);
*     c.option("v", true);
*     c.option("config", "/etc/app.yml");
*     c.arg("input.txt");
* });
*
* // 方式二：Builder 风格
* CommandLine.Composer composer = cli.composer();
* composer.set("port", 9090);
* composer.set("verbose", true);
* composer.set("config", "/etc/app.yml");
* composer.arg("input.txt");
* String[] args2 = composer.build();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class CommandLine {

    /** 默认帮助选项的长名称 */
    private static final String HELP_LONG = "help";

    /** 默认帮助选项的短名称 */
    private static final String HELP_SHORT = "h";

    /** 默认帮助选项的描述 */
    private static final String HELP_DESC = "显示帮助信息";

    /** 选项列表 */
    private final List<CliOption> options;
    /** 程序名称 */
    private final String programName;
    /** 程序描述 */
    private final String programDescription;
    /** 是否启用帮助选项 */
    private final boolean helpOptionEnabled;

    /**
    * 创建 CommandLine 实例
    * @param builder builder
     */
    private CommandLine(Builder builder) {
        this.options = Collections.unmodifiableList(new ArrayList<>(builder.options));
        this.programName = builder.programName;
        this.programDescription = builder.programDescription;
        this.helpOptionEnabled = builder.helpOptionEnabled;
    }

    @Nonnull
    /** ProgramName */
    public String programName() {
        return programName;
    }

    @Nonnull
    /** ProgramDescription */
    public String programDescription() {
        return programDescription;
    }

    /**
    * 创建新的 {@link Builder} 实例。
    *
    * @return Builder
     */
    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /**
    * 获取已定义的所有选项。
    *
    * @return 不可修改的选项列表
     */
    @Nonnull
    public List<CliOption> options() {
        return options;
    }

    /**
    * 判断是否启用了内置的帮助选项。
    *
    * @return 如果启用了返回 true
     */
    public boolean isHelpOptionEnabled() {
        return helpOptionEnabled;
    }

    // ==================== 解析 ====================

    /**
    * 解析命令行参数。
    *
    * @param args 命令行参数数组（通常来自 {@code main(String[] args)}）
    * @return 解析结果
    * @throws IllegalArgumentException 如果遇到未知选项或缺少必需选项的值
     */
    @Nonnull
    public Result parse(@Nullable String[] args) {
        Map<String, Object> values = new LinkedHashMap<>();
        List<String> positionalArgs = new ArrayList<>();
        Set<String> seenOptions = new HashSet<>();

        // 初始化所有选项的默认值
        for (CliOption opt : options) {
            if (opt.defaultValue() != null) {
                values.put(opt.longName(), opt.defaultValue());
            }
        }

        if (args == null || args.length == 0) {
            return new Result(options, values, positionalArgs, seenOptions);
        }

        int i = 0;
        boolean endOfOptions = false;

        while (i < args.length) {
            String arg = args[i];

            // 终止标记 --
            if (!endOfOptions && "--".equals(arg)) {
                endOfOptions = true;
                i++;
                continue;
            }

            // 终止标记之后的所有参数视为位置参数
            if (endOfOptions || !arg.startsWith("-")) {
                positionalArgs.add(arg);
                i++;
                continue;
            }

            // 长选项 --name=value 或 --name value
            if (arg.startsWith("--")) {
                String longName = arg.substring(2);
                String inlineValue = null;

                // 检查等号语法 --name=value
                int eqIdx = longName.indexOf('=');
                if (eqIdx >= 0) {
                    inlineValue = longName.substring(eqIdx + 1);
                    longName = longName.substring(0, eqIdx);
                }

                // 处理 --no-xxx 否定形式
                boolean negation = false;
                if (!isOptionDefined(longName) && longName.startsWith("no-")) {
                    String negatedName = longName.substring(3);
                    if (isOptionDefined(negatedName)) {
                        longName = negatedName;
                        negation = true;
                    }
                }

                CliOption opt = findOption(longName);
                if (opt == null) {
                    throw new IllegalArgumentException("未知选项: --" + longName);
                }

                if (opt.flag()) {
                    values.put(opt.longName(), !negation);
                } else {
                    String value;
                    if (inlineValue != null) {
                        value = inlineValue;
                    } else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        value = args[++i];
                    } else {
                        throw new IllegalArgumentException("选项 --" + longName + " 需要一个值");
                    }
                    values.put(opt.longName(), convertValue(opt, value));
                }
                seenOptions.add(opt.longName());
                i++;
                continue;
            }

            // 短选项 -s value 或 -svalue 或 -abc（组合标志）
            if (arg.startsWith("-") && arg.length() >= 2) {
                String shortOpts = arg.substring(1);

                // 查找匹配的短选项（优先匹配完整字符串）
                CliOption shortOpt = findOptionByShortName(shortOpts);
                if (shortOpt != null) {
                    // 找到了完整的短选项
                    if (shortOpt.flag()) {
                        values.put(shortOpt.longName(), true);
                    } else {
                        // 选项需要值：-s value 或 -svalue
                        String value;
                        if (shortOpts.length() > 1) {
                            // -svalue 形式（短选项名只有1个字符时）
                            value = shortOpts.substring(1);
                        } else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                            value = args[++i];
                        } else {
                            throw new IllegalArgumentException("选项 -" + shortOpts + " 需要一个值");
                        }
                        values.put(shortOpt.longName(), convertValue(shortOpt, value));
                    }
                    seenOptions.add(shortOpt.longName());
                } else {
                    // 尝试作为组合标志处理（如 -abc = -a -b -c）
                    boolean parsed = false;
                    for (int j = 0; j < shortOpts.length(); j++) {
                        String single = String.valueOf(shortOpts.charAt(j));
                        CliOption flagOpt = findOptionByShortName(single);
                        if (flagOpt != null && flagOpt.flag()) {
                            values.put(flagOpt.longName(), true);
                            seenOptions.add(flagOpt.longName());
                            parsed = true;
                        } else if (flagOpt != null && !flagOpt.flag()) {
                            // 非标志短选项：取剩余部分作为值
                            String remaining = shortOpts.substring(j + 1);
                            if (remaining.isEmpty()) {
                                if (i + 1 < args.length) {
                                    values.put(flagOpt.longName(), convertValue(flagOpt, args[++i]));
                                } else {
                                    throw new IllegalArgumentException("选项 -" + single + " 需要一个值");
                                }
                            } else {
                                values.put(flagOpt.longName(), convertValue(flagOpt, remaining));
                            }
                            seenOptions.add(flagOpt.longName());
                            parsed = true;
                            break;
                        }
                    }
                    if (!parsed) {
                        throw new IllegalArgumentException("未知选项: -" + shortOpts);
                    }
                }
                i++;
                continue;
            }

            // 不应该到达这里
            positionalArgs.add(arg);
            i++;
        }

        // 校验必需选项
        List<String> missing = new ArrayList<>();
        for (CliOption opt : options) {
            if (opt.required() && !seenOptions.contains(opt.longName())) {
                missing.add("--" + opt.longName());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("缺少必需选项: " + String.join(", ", missing));
        }

        return new Result(options, values, positionalArgs, seenOptions);
    }

    // ==================== 组装 ====================

    /**
    * 创建一个组装器，通过 Lambda 快捷设置选项值并生成命令行参数。
    *
    * <pre>{@code
    * String[] args = cli.compose(c -> {
    *     c.option("port", 8080);
    *     c.option("verbose", true);
    *     c.arg("file.txt");
    * });
    * }</pre>
    *
    * @param consumer 组装器消费回调
    * @return 生成命令行参数字符串数组
    * @throws IllegalArgumentException 如果设置了未定义的选项或值类型不匹配
     */
    @Nonnull
    public String[] compose(@Nonnull Consumer<Composer> consumer) {
        Composer composer = new Composer(this);
        consumer.accept(composer);
        return composer.build();
    }

    /**
    * 创建一个组装器，用于构建命令行参数字符串数组。
    *
    * <pre>{@code
    * CommandLine.Composer composer = cli.composer();
    * composer.set("port", 8080);
    * composer.set("verbose", true);
    * composer.arg("file.txt");
    * String[] args = composer.build();
    * }</pre>
    *
    * @return Composer 实例
     */
    @Nonnull
    public Composer composer() {
        return new Composer(this);
    }

    // ==================== 帮助信息 ====================

    /**
    * 将帮助信息打印到指定输出流。
    *
    * @param out 输出流（如 {@link System#out}）
     */
    public void printHelp(@Nonnull PrintStream out) {
        out.println("用法: " + (programName != null ? programName : "<program>") + " [选项]");
        if (StringUtils.isNotBlank(programDescription)) {
            out.println();
            out.println(programDescription);
        }
        out.println();
        out.println("选项:");

        if (options.isEmpty()) {
            out.println("  （无可用选项）");
            return;
        }

        // 计算格式对齐宽度
        int maxNameWidth = 0;
        Map<CliOption, String> nameParts = new LinkedHashMap<>();
        for (CliOption opt : options) {
            String namePart = formatOptionName(opt);
            nameParts.put(opt, namePart);
            int displayWidth = StringUtils.getDisplayWidth(namePart);
            if (displayWidth > maxNameWidth) {
                maxNameWidth = displayWidth;
            }
        }

        // 缩进宽度 = 2 个 padding + 2 个最小空格
        int helpIndent = maxNameWidth + 4;
        int consoleWidth = getConsoleWidth();

        for (CliOption opt : options) {
            String namePart = nameParts.get(opt);
            String desc = opt.description();

            // 追加额外信息（类型、必需、默认值）
            StringBuilder extra = new StringBuilder();
            if (opt.required()) {
                extra.append(" [必需]");
            }
            if (opt.defaultValue() != null) {
                extra.append(" (默认: ").append(opt.defaultValue()).append(")");
            }

            String fullDesc = desc + extra;

            // 打印选项名
            out.print("  " + namePart);

            // 对齐到帮助文本起始位置
            int currentWidth = StringUtils.getDisplayWidth(namePart) + 2;
            int padding = helpIndent - currentWidth;
            if (padding > 0) {
                out.print(StringUtils.repeat(' ', padding));
            } else {
                out.print("  ");
            }
            // 自动换行输出帮助文本
            if (consoleWidth > 0) {
                List<String> lines = wordWrap(fullDesc, consoleWidth - helpIndent);
                for (int j = 0; j < lines.size(); j++) {
                    if (j > 0) {
                        out.println();
                        out.print(StringUtils.repeat(' ', helpIndent));
                    }
                    out.print(lines.get(j));
                }
            } else {
                out.print(fullDesc);
            }
            out.println();
        }
    }

    /**
    * 将帮助信息打印到标准输出。
     */
    public void printHelp() {
        printHelp(System.out);
    }

    // ========== 内部方法 ==========

    /**
    * 格式化选项名称（如 "-p, --port"）。
     */
    private static String formatOptionName(CliOption opt) {
        StringBuilder sb = new StringBuilder();
        if (opt.shortName() != null) {
            sb.append('-').append(opt.shortName());
            if (!opt.flag()) {
                sb.append(' ').append(typeHint(opt));
            }
            sb.append(", ");
        } else {
            sb.append("    ");
        }
        sb.append("--").append(opt.longName());
        if (!opt.flag()) {
            sb.append(' ').append(typeHint(opt));
        }
        return sb.toString();
    }

    /**
    * 返回类型的友好提示字符串。
     */
    private static String typeHint(CliOption opt) {
        switch (opt.type()) {
            case INTEGER: return "<int>";
            case LONG:    return "<long>";
            case DOUBLE:  return "<double>";
            case PATH:    return "<path>";
            case ENUM:
                if (opt.enumConstants() != null && opt.enumConstants().length > 0) {
                    return "<" + String.join("|", opt.enumConstants()) + ">";
                }
                return "<enum>";
            case BOOLEAN: return "";
            default:      return "<text>";
        }
    }

    /**
    * 检查指定长名称是否在已定义的选项中。
     */
    private boolean isOptionDefined(String longName) {
        return findOption(longName) != null;
    }

    /**
    * 按长名称查找选项。
     */
    @Nullable
    private CliOption findOption(String longName) {
        for (CliOption opt : options) {
            if (opt.longName().equals(longName)) {
                return opt;
            }
        }
        return null;
    }

    /**
    * 按短名称查找选项。
     */
    @Nullable
    private CliOption findOptionByShortName(String shortName) {
        for (CliOption opt : options) {
            if (shortName.equals(opt.shortName())) {
                return opt;
            }
        }
        return null;
    }

    /**
    * 通过长名称或短名称查找选项。
     */
    @Nullable
    private CliOption resolveOption(String name) {
        CliOption opt = findOption(name);
        if (opt != null) {
            return opt;
        }
        return findOptionByShortName(name);
    }

    /**
    * 将选项值转为字符串（组装时使用）。
     */
    private static String valueToString(CliOption opt, Object value) {
        if (value == null) {
            return "";
        }
        switch (opt.type()) {
            case BOOLEAN:
                return Boolean.TRUE.equals(value) ? "true" : "false";
            case ENUM:
                return value instanceof Enum ? ((Enum<?>) value).name() : value.toString();
            case PATH:
                return value.toString();
            default:
                return value.toString();
        }
    }

    /**
    * 将字符串值转换为选项对应的类型。
     */
@SuppressWarnings("unchecked")
    private static Object convertValue(CliOption opt, String value) {
        if (value == null) {
            return null;
        }
        try {
            switch (opt.type()) {
                case INTEGER:
                    return Integer.parseInt(value);
                case LONG:
                    return Long.parseLong(value);
                case DOUBLE:
                    return Double.parseDouble(value);
                case BOOLEAN:
                    return "true".equalsIgnoreCase(value)
                            || "yes".equalsIgnoreCase(value)
                            || "1".equals(value);
                case PATH:
                    try {
                        return java.nio.file.Paths.get(value);
                    } catch (java.nio.file.InvalidPathException e) {
                        throw new IllegalArgumentException(
                                "选项 --" + opt.longName() + " 需要有效的路径，但得到: " + value, e);
                    }
                case ENUM: {
                    Class<? extends Enum<?>> enumType = opt.enumType();
                    if (enumType == null) {
                        return value;
                    }
                    // 尝试精确匹配
                    Enum<?>[] constants = enumType.getEnumConstants();
                    if (constants == null) {
                        return value;
                    }
                    for (Enum<?> constant : constants) {
                        if (constant.name().equals(value)) {
                            return constant;
                        }
                    }
                    // 尝试忽略大小写匹配
                    if (opt.enumIgnoreCase()) {
                        for (Enum<?> constant : constants) {
                            if (constant.name().equalsIgnoreCase(value)) {
                                return constant;
                            }
                        }
                    }
                    throw new IllegalArgumentException("选项 --" + opt.longName()
                            + " 需要有效的枚举值 [" + String.join(", ", opt.enumConstants()) + "]"
                            + "，但得到: " + value);
                }
                default:
                    return value;
            }
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("选项 --")) {
                throw e;
            }
            throw new IllegalArgumentException(
                    "选项 --" + opt.longName() + " 需要 " + opt.type().name().toLowerCase()
                            + " 类型的值，但得到: " + value, e);
        }
    }

    /**
    * 获取控制台宽度（用于帮助文本自动换行）。
    *
    * <p>默认返回 80 列，适用于大多数终端环境。</p>
     */
    private static int getConsoleWidth() {
        return 80;
    }

    /**
    * 将文本按指定宽度换行。
    *
    * @param text  文本
    * @param width 每行最大宽度
    * @return 换行后的行列表
     */
    private static List<String> wordWrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        if (StringUtils.isEmpty(text)) {
            lines.add("");
            return lines;
        }

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > width && currentLine.length() > 0) {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(' ');
                }
                currentLine.append(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }

        return lines;
    }

    // ==================== Builder ====================

    /**
    * {@link CommandLine} 构建器。
    *
    * <p>默认会自动添加 {@code --help} / {@code -h} 选项，可通过 {@link #disableHelpOption()} 禁用。</p>
     */
    public static final class Builder {
        /** 选项列表 */
        private final List<CliOption> options = new ArrayList<>();
        /** 程序名称 */
        private String programName;
        /** 程序描述 */
        private String programDescription;
        /** 是否启用帮助选项 */
        private boolean helpOptionEnabled = true;

        /** 创建 Builder 实例 */
        private Builder() {
        }

        /**
        * 添加一个选项定义。
        *
        * @param option 选项定义
        * @return this
        * @throws IllegalArgumentException 如果存在重复的选项名称
         */
        @Nonnull
        public Builder option(@Nonnull CliOption option) {
            // 检查重复
            for (CliOption existing : options) {
                if (existing.longName().equals(option.longName())) {
                    throw new IllegalArgumentException("重复的选项名称: --" + option.longName());
                }
                if (option.shortName() != null && option.shortName().equals(existing.shortName())) {
                    throw new IllegalArgumentException("重复的短选项名称: -" + option.shortName());
                }
            }
            options.add(option);
            return this;
        }

        /**
        * 设置程序名称（用于帮助信息）。
        *
        * @param programName 程序名称
        * @return this
         */
        @Nonnull
        public Builder programName(@Nullable String programName) {
            this.programName = programName;
            return this;
        }

        /**
        * 设置程序描述（用于帮助信息）。
        *
        * @param description 程序描述
        * @return this
         */
        @Nonnull
        public Builder description(@Nullable String description) {
            this.programDescription = description;
            return this;
        }

        /**
        * 禁用内置的 {@code --help} / {@code -h} 选项。
        *
        * <p>默认情况下，所有 {@link CommandLine} 实例都自动包含帮助选项。
        * 如果用户自定义了名为 "help" 的选项，也会自动覆盖默认的帮助选项。</p>
        *
        * @return this
         */
        @Nonnull
        public Builder disableHelpOption() {
            this.helpOptionEnabled = false;
            return this;
        }

        /**
        * 构建 {@link CommandLine} 实例。
        *
        * <p>如果启用了帮助选项且没有名为 "help" 的选项，会自动添加一个默认的
        * {@code --help} / {@code -h} 布尔标志选项。</p>
        *
        * @return CommandLine 实例
        * @throws IllegalStateException 如果选项定义中存在冲突
         */
        @Nonnull
        public CommandLine build() {
            // 自动添加 --help / -h 选项
            if (helpOptionEnabled && !hasOption("help")) {
                CliOption helpOption = CliOption.builder()
                        .longName(HELP_LONG)
                        .shortName(HELP_SHORT)
                        .description(HELP_DESC)
                        .flag(true)
                        .build();

                // 检查短名称 h 是否被占用
                if (findOptionByShortName(HELP_SHORT) == null) {
                    options.add(helpOption);
                } else {
                    // 短名称被占用，仅添加长名称
                    helpOption = CliOption.builder()
                            .longName(HELP_LONG)
                            .description(HELP_DESC)
                            .flag(true)
                            .build();
                    options.add(helpOption);
                }
            }

            return new CommandLine(this);
        }

        /**
        * 检查是否已存在指定长名称的选项。
         */
        private boolean hasOption(String longName) {
            for (CliOption opt : options) {
                if (opt.longName().equals(longName)) {
                    return true;
                }
            }
            return false;
        }

        /**
        * 按短名称查找已添加的选项。
         */
        @Nullable
        private CliOption findOptionByShortName(String shortName) {
            for (CliOption opt : options) {
                if (shortName.equals(opt.shortName())) {
                    return opt;
                }
            }
            return null;
        }
    }

    // ==================== Result ====================

    /**
    * 命令行参数解析结果。
    *
    * <p>提供类型安全的方法获取选项值，以及获取位置参数列表。</p>
     */
    public static final class Result {

        /** 选项列表 */
        private final List<CliOption> options;
        /** 值映射 */
        private final Map<String, Object> values;
        /** 位置参数列表 */
        private final List<String> positionalArgs;
        /** 已解析选项集合 */
        private final Set<String> seenOptions;

        /**
        * 创建 Result 实例
        * @param options options
        * @param values values
        * @param positionalArgs positionalArgs
        * @param seenOptions seenOptions
         */
        private Result(List<CliOption> options, Map<String, Object> values,
                       List<String> positionalArgs, Set<String> seenOptions) {
            this.options = options;
            this.values = values;
            this.positionalArgs = Collections.unmodifiableList(positionalArgs);
            this.seenOptions = seenOptions;
        }

        // ========== 存在性检查 ==========

        /**
        * 检查是否指定了某个选项（通过长名称或短名称）。
        *
        * @param name 长选项名称或短选项名称
        * @return 如果指定了该选项返回 true
         */
        public boolean has(@Nonnull String name) {
            CliOption opt = resolveOption(name);
            if (opt == null) {
                return false;
            }
            if (opt.flag()) {
                Object val = values.get(opt.longName());
                return Boolean.TRUE.equals(val);
            }
            return seenOptions.contains(opt.longName());
        }

        /**
        * 检查某个选项是否在命令行中被显式指定（而不是使用默认值）。
        *
        * @param name 长选项名称或短选项名称
        * @return 如果用户在命令行中指定了该选项返回 true
         */
        public boolean isExplicitlySet(@Nonnull String name) {
            CliOption opt = resolveOption(name);
            return opt != null && seenOptions.contains(opt.longName());
        }

        // ========== 取值方法 ==========

        /**
        * 获取选项的原始值。
        *
        * @param name 长选项名称或短选项名称
        * @param <T>  值类型
        * @return 选项值，未指定时返回 null
         */
        @Nullable
        public <T> T get(@Nonnull String name) {
            CliOption opt = resolveOption(name);
            if (opt == null) {
                return null;
            }
            return (T) values.get(opt.longName());
        }

        /**
        * 获取选项的字符串值。
        *
        * @param name 长选项名称或短选项名称
        * @return 字符串值，未指定时返回 null
         */
        @Nullable
        public String getString(@Nonnull String name) {
            return get(name);
        }

        /**
        * 获取选项的整数值。
        *
        * @param name 长选项名称或短选项名称
        * @return 整数值，未指定或无法转换时返回 0
         */
        public int getInt(@Nonnull String name) {
            Number value = get(name);
            return value != null ? value.intValue() : 0;
        }

        /**
        * 获取选项的长整数值。
        *
        * @param name 长选项名称或短选项名称
        * @return 长整数值，未指定或无法转换时返回 0L
         */
        public long getLong(@Nonnull String name) {
            Number value = get(name);
            return value != null ? value.longValue() : 0L;
        }

        /**
        * 获取选项的双精度浮点数值。
        *
        * @param name 长选项名称或短选项名称
        * @return 双精度浮点数值，未指定或无法转换时返回 0.0
         */
        public double getDouble(@Nonnull String name) {
            Number value = get(name);
            return value != null ? value.doubleValue() : 0.0;
        }

        /**
        * 获取选项的布尔值。
        *
        * @param name 长选项名称或短选项名称
        * @return 布尔值，未指定时返回 false
         */
        public boolean getBoolean(@Nonnull String name) {
            Boolean value = get(name);
            return value != null && value;
        }

        /**
        * 获取选项值，如果未指定则返回默认值。
        *
        * @param name         长选项名称或短选项名称
        * @param defaultValue 默认值
        * @param <T>          值类型
        * @return 选项值或默认值
         */
        @Nullable
        public <T> T getOrDefault(@Nonnull String name, @Nullable T defaultValue) {
            CliOption opt = resolveOption(name);
            if (opt == null || !seenOptions.contains(opt.longName())) {
                return defaultValue;
            }
            T value = get(name);
            return value != null ? value : defaultValue;
        }

        // ========== 位置参数 ==========

        /**
        * 获取位置参数列表（非选项参数）。
        *
        * @return 不可修改的位置参数列表
         */
        @Nonnull
        public List<String> positionalArgs() {
            return positionalArgs;
        }

        /**
        * 获取第一个位置参数。
        *
        * @return 第一个位置参数，没有时返回 null
         */
        @Nullable
        public String firstPositional() {
            return positionalArgs.isEmpty() ? null : positionalArgs.get(0);
        }

        /**
        * 获取指定索引的位置参数。
        *
        * @param index 索引
        * @return 位置参数，不存在时返回 null
         */
        @Nullable
        public String positionalAt(int index) {
            return index >= 0 && index < positionalArgs.size() ? positionalArgs.get(index) : null;
        }

        /**
        * 获取所有选项的概要信息（用于调试）。
        *
        * @return 选项概要字符串
         */
        @Nonnull
        public String dump() {
            StringBuilder sb = new StringBuilder();
            sb.append("CommandLine.Result {\n");
            for (CliOption opt : options) {
                sb.append("  --").append(opt.longName()).append(" = ");
                Object value = values.get(opt.longName());
                if (seenOptions.contains(opt.longName())) {
                    sb.append(value);
                } else if (value != null) {
                    sb.append(value).append(" (default)");
                } else {
                    sb.append("<not set>");
                }
                sb.append('\n');
            }
            if (!positionalArgs.isEmpty()) {
                sb.append("  positional: ").append(positionalArgs).append('\n');
            }
            sb.append('}');
            return sb.toString();
        }

        // ========== 内部方法 ==========

        /**
        * 通过长名称或短名称解析对应的选项定义。
         */
        @Nullable
        private CliOption resolveOption(String name) {
            // 先按长名称查找
            for (CliOption opt : options) {
                if (opt.longName().equals(name)) {
                    return opt;
                }
            }
            // 再按短名称查找
            for (CliOption opt : options) {
                if (name.equals(opt.shortName())) {
                    return opt;
                }
            }
            return null;
        }
    }

    // ==================== Composer ====================

    /**
    * 命令行参数组装器 — 将选项值反向生成为 {@code String[]} 命令行参数。
    *
    * <p>用于程序化的参数构建场景，如生成要传递给外部进程的命令行。</p>
    *
    * <h3>用法</h3>
    * <pre>{@code
    * // Builder 风格
    * String[] args = cli.composer()
    *         .set("port", 8080)
    *         .set("verbose", true)
    *         .set("config", "/etc/app.yml")
    *         .arg("input.txt")
    *         .build();
    *
    * // Lambda 风格（更简洁）
    * String[] args = cli.compose(c -> {
    *     c.option("port", 8080);
    *     c.option("v", true);
    *     c.arg("input.txt");
    * });
    * }</pre>
     */
    public static final class Composer {

        /** 命令行实例 */
        private final CommandLine commandLine;
        /** 已解析选项值映射 */
        private final Map<String, Object> optionValues = new LinkedHashMap<>();
        /** 位置参数列表 */
        private final List<String> positionalArgs = new ArrayList<>();
        /** 是否使用长选项名 */
        private boolean useLongNames = true;
        /** 是否使用等号格式 */
        private boolean useEqualsFormat = false;

        /**
        * 创建 Composer 实例
        * @param commandLine commandLine
         */
        private Composer(CommandLine commandLine) {
            this.commandLine = commandLine;
        }

        /**
        * 设置选项值（通过长名称或短名称）。
        *
        * @param name  长选项名称或短选项名称
        * @param value 选项值
        * @return this
        * @throws IllegalArgumentException 如果名称为对应的选项定义，或值类型不匹配
         */
        @Nonnull
        public Composer set(@Nonnull String name, @Nullable Object value) {
            CliOption opt = commandLine.resolveOption(name);
            if (opt == null) {
                throw new IllegalArgumentException("未定义的选项: " + name
                        + "。可用的选项: " + commandLine.optionNames());
            }
            // 类型检查（仅对非 null 值做基本校验）
            if (value != null && !opt.flag()) {
                switch (opt.type()) {
                    case INTEGER:
                        if (!(value instanceof Integer)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Integer 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    case LONG:
                        if (!(value instanceof Long)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Long 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    case DOUBLE:
                        if (!(value instanceof Double)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Double 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    case BOOLEAN:
                        if (!(value instanceof Boolean)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Boolean 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    case ENUM:
                        if (!(value instanceof Enum)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Enum 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    case PATH:
                        if (!(value instanceof java.nio.file.Path)) {
                            throw new IllegalArgumentException("选项 --" + opt.longName()
                                    + " 需要 Path 类型，但得到: " + value.getClass().getSimpleName());
                        }
                        break;
                    default:
                        // STRING 类型接受任意值
                        break;
                }
            }
            optionValues.put(opt.longName(), value);
            return this;
        }

        /**
        * 设置选项值（仅字符串形式）。
        * <p>这是 Lambda 风格 {@link #compose(Composer)} 中的命名方法。</p>
        *
        * @param name  长选项名称或短选项名称
        * @param value 选项值
        * @return this
         */
        @Nonnull
        public Composer option(@Nonnull String name, @Nullable Object value) {
            return set(name, value);
        }

        /**
        * 添加一个位置参数。
        *
        * @param arg 位置参数值
        * @return this
         */
        @Nonnull
        public Composer arg(@Nullable String arg) {
            if (arg != null) {
                positionalArgs.add(arg);
            }
            return this;
        }

        /**
        * 添加多个位置参数。
        *
        * @param args 位置参数值数组
        * @return this
         */
        @Nonnull
        public Composer args(@Nonnull String... args) {
            for (String arg : args) {
                arg(arg);
            }
            return this;
        }

        /**
        * 设置是否使用长选项名称（默认 true）。
        * <ul>
        *   <li>true=使用 {@code --port}（长名称）</li>
        *   <li>false=使用 {@code -p}（短名称，优先使用；若无短名称则回退到长名称）</li>
        * </ul>
        *
        * @param useLongNames 是否使用长名称
        * @return this
         */
        @Nonnull
        public Composer useLongNames(boolean useLongNames) {
            this.useLongNames = useLongNames;
            return this;
        }

        /**
        * 设置是否使用 {@code --name=value} 等号格式（默认 false）。
        * <ul>
        *   <li>true=输出 {@code --port=8080}</li>
        *   <li>false=输出 {@code --port 8080}（空格分隔）</li>
        * </ul>
        *
        * @param useEquals 是否使用等号格式
        * @return this
         */
        @Nonnull
        public Composer useEqualsFormat(boolean useEquals) {
            this.useEqualsFormat = useEquals;
            return this;
        }

        /**
        * 构建命令行参数字符串数组。
        *
        * @return 命令行参数数组（适用于 {@link ProcessBuilder} 等）
         */
        @Nonnull
        public String[] build() {
            List<String> result = new ArrayList<>();

            for (CliOption opt : commandLine.options) {
                Object value = optionValues.get(opt.longName());
                // 只处理显式设置了值的选项
                if (!optionValues.containsKey(opt.longName())) {
                    continue;
                }

                if (opt.flag()) {
                    // 布尔标志
                    boolean flagValue = Boolean.TRUE.equals(value);
                    if (flagValue) {
                        if (useLongNames || opt.shortName() == null) {
                            result.add("--" + opt.longName());
                        } else {
                            result.add("-" + opt.shortName());
                        }
                    } else if (useLongNames) {
                        // --no-xxx 形式否定
                        result.add("--no-" + opt.longName());
                    }
                } else {
                    // 带值的选项
                    String strValue = valueToString(opt, value);
                    String optName;
                    if (useLongNames || opt.shortName() == null) {
                        optName = "--" + opt.longName();
                    } else {
                        optName = "-" + opt.shortName();
                    }

                    if (useEqualsFormat) {
                        result.add(optName + "=" + strValue);
                    } else {
                        result.add(optName);
                        result.add(strValue);
                    }
                }
            }

            // 位置参数
            if (!positionalArgs.isEmpty()) {
                result.add("--");
                result.addAll(positionalArgs);
            }

            return result.toArray(new String[0]);
        }

        /**
        * 构建命令行字符串。
        *
        * @return 命令行字符串（每个参数用空格连接）
         */
        @Nonnull
        public String buildString() {
            return String.join(" ", build());
        }
    }

    // ========== 辅助方法 ==========

    /**
    * 获取所有选项的长名称列表（用于错误提示）。
     */
    private String optionNames() {
        StringBuilder sb = new StringBuilder();
        for (CliOption opt : options) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("--").append(opt.longName());
        }
        return sb.toString();
    }
}
