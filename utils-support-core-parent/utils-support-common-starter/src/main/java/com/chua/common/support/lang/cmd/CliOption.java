package com.chua.common.support.lang.cmd;

import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * 命令行选项定义，描述一个命令行参数的名称、类型、描述等元信息。
 *
 * <p>支持以下特性：</p>
 * <ul>
 *   <li>长选项（{@code --name}）和短选项（{@code -n}）</li>
 *   <li>值类型：字符串、整数、长整数、双精度浮点数、布尔标志、枚举、文件路径</li>
 *   <li>必需/可选标记</li>
 *   <li>默认值</li>
 *   <li>选项描述（用于自动生成帮助信息）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CliOption option = CliOption.builder()
 *         .longName("port")
 *         .shortName("p")
 *         .description("监听端口")
 *         .type(Integer.class)
 *         .defaultValue(8080)
 *         .build();
 *
 * CliOption flag = CliOption.builder()
 *         .longName("verbose")
 *         .shortName("v")
 *         .description("启用详细输出")
 *         .flag(true)
 *         .build();
 *
 * // 枚举类型
 * public enum Level { DEBUG, INFO, WARN, ERROR }
 * CliOption logLevel = CliOption.builder()
 *         .longName("log-level")
 *         .shortName("l")
 *         .description("日志级别")
 *         .type(Level.class)
 *         .defaultValue(Level.INFO)
 *         .enumIgnoreCase(true)
 *         .build();
 *
 * // Path 类型
 * CliOption config = CliOption.builder()
 *         .longName("config")
 *         .description("配置文件")
 *         .type(java.nio.file.Path.class)
 *         .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CliOption {

    /** 选项的值类型枚举 */
    public enum OptionType {
        STRING,
        INTEGER,
        LONG,
        DOUBLE,
        BOOLEAN,
        ENUM,
        PATH
    }

    /** 长选项名称 */
    private final String longName;
    /** 短选项名称 */
    private final String shortName;
    /** 描述信息 */
    private final String description;
    /** 选项类型 */
    private final OptionType type;
    /** 是否必填 */
    private final boolean required;
    /** 是否为标志参数 */
    private final boolean flag;
    /** 默认值 */
    private final Object defaultValue;
    /** 枚举类型 */
    private final Class<? extends Enum<?>> enumType;
    /** 枚举常量值数组 */
    private final String[] enumConstants;
    /** 是否忽略枚举大小写 */
    private final boolean enumIgnoreCase;

    private CliOption(Builder builder) {
        this.longName = builder.longName;
        this.shortName = builder.shortName;
        this.description = builder.description;
        this.type = builder.type;
        this.required = builder.required;
        this.flag = builder.flag;
        this.defaultValue = builder.defaultValue;
        this.enumType = builder.enumType;
        this.enumConstants = builder.enumConstants;
        this.enumIgnoreCase = builder.enumIgnoreCase;
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
     * 构建枚举常量的名称数组。
     *
     * @param enumClass 枚举类
     * @return 枚举常量名称数组
     */
    @Nonnull
@SuppressWarnings({"rawtypes", "unchecked"})
    private static String[] buildEnumConstants(Class<? extends Enum> enumClass) {
        Enum[] constants = enumClass.getEnumConstants();
        if (constants == null) {
            return new String[0];
        }
        String[] names = new String[constants.length];
        for (int i = 0; i < constants.length; i++) {
            names[i] = constants[i].name();
        }
        return names;
    }

    /**
     * 快速创建一个字符串类型的选项。
     *
     * @param longName  长选项名称
     * @param shortName 短选项名称
     * @param description 选项描述
     * @return CliOption 实例
     */
    @Nonnull
    public static CliOption of(@Nonnull String longName, @Nullable String shortName, @Nonnull String description) {
        return builder()
                .longName(longName)
                .shortName(shortName)
                .description(description)
                .type(OptionType.STRING)
                .build();
    }

    /**
     * 快速创建一个字符串类型的选项。
     *
     * @param longName  长选项名称
     * @param description 选项描述
     * @return CliOption 实例
     */
    @Nonnull
    public static CliOption of(@Nonnull String longName, @Nonnull String description) {
        return of(longName, null, description);
    }

    // ========== getters ==========

    /**
     * 获取长选项名称（如 "port"）。
     *
     * @return 长选项名称
     */
    @Nonnull
    public String longName() {
        return longName;
    }

    /**
     * 获取短选项名称（如 "p"），可能为 null。
     *
     * @return 短选项名称，可能为 null
     */
    @Nullable
    public String shortName() {
        return shortName;
    }

    /**
     * 获取选项描述。
     *
     * @return 选项描述
     */
    @Nonnull
    public String description() {
        return description;
    }

    /**
     * 获取选项值的类型。
     *
     * @return 选项类型
     */
    @Nonnull
    public OptionType type() {
        return type;
    }

    /**
     * 判断此选项是否为必需的。
     *
     * @return 如果为必需返回 true
     */
    public boolean required() {
        return required;
    }

    /**
     * 判断此选项是否为布尔标志（无值）。
     *
     * @return 如果是标志返回 true
     */
    public boolean flag() {
        return flag;
    }

    /**
     * 获取默认值。
     *
     * @return 默认值，可能为 null
     */
    @Nullable
    public Object defaultValue() {
        return defaultValue;
    }

    /**
     * 获取枚举类型（仅当 {@link #type()} 为 {@link OptionType#ENUM} 时有意义）。
     *
     * @return 枚举 Class，非 ENUM 类型返回 null
     */
    @Nullable
    public Class<? extends Enum<?>> enumType() {
        return enumType;
    }

    /**
     * 获取枚举常量名称列表（仅当 {@link #type()} 为 {@link OptionType#ENUM} 时有意义）。
     *
     * @return 枚举常量名称数组
     */
    @Nonnull
    public String[] enumConstants() {
        return enumConstants;
    }

    /**
     * 获取枚举是否忽略大小写。
     *
     * @return 如果忽略大小写返回 true
     */
    public boolean enumIgnoreCase() {
        return enumIgnoreCase;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CliOption)) return false;
        CliOption cliOption = (CliOption) o;
        return longName.equals(cliOption.longName)
                && Objects.equals(shortName, cliOption.shortName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(longName, shortName);
    }

    @Override
    public String toString() {
        return "CliOption{" +
                "longName='" + longName + '\'' +
                ", shortName='" + shortName + '\'' +
                ", type=" + type +
                ", required=" + required +
                ", flag=" + flag +
                ", defaultValue=" + defaultValue +
                '}';
    }

    // ========== Builder ==========

    /**
     * {@link CliOption} 构建器。
     */
    public static final class Builder {
        /** 长选项名称 */
        private String longName;
        /** 短选项名称 */
        private String shortName;
        /** 描述信息 */
        private String description;
        /** 选项类型 */
        private OptionType type = OptionType.STRING;
        /** 是否必填 */
        private boolean required;
        /** 是否为标志参数 */
        private boolean flag;
        /** 默认值 */
        private Object defaultValue;

        /** 枚举类型 */
        private Class<? extends Enum<?>> enumType;
        /** 枚举常量值数组 */
        private String[] enumConstants = new String[0];
        /** 是否忽略枚举大小写 */
        private boolean enumIgnoreCase;

        private Builder() {
        }

        /**
         * 设置长选项名称（如 "port"）。
         *
         * @param longName 长选项名称
         * @return this
         */
        @Nonnull
        public Builder longName(@Nonnull String longName) {
            this.longName = longName;
            return this;
        }

        /**
         * 设置短选项名称（如 "p"）。
         *
         * @param shortName 短选项名称
         * @return this
         */
        @Nonnull
        public Builder shortName(@Nullable String shortName) {
            this.shortName = shortName;
            return this;
        }

        /**
         * 设置选项描述。
         *
         * @param description 选项描述
         * @return this
         */
        @Nonnull
        public Builder description(@Nonnull String description) {
            this.description = description;
            return this;
        }

        /**
         * 设置选项值的类型。
         *
         * @param type 选项类型
         * @return this
         */
        @Nonnull
        public Builder type(@Nonnull OptionType type) {
            this.type = type;
            return this;
        }

        /**
         * 设置选项值的类型（由 Java 类型自动推导）。
         *
         * @param typeClass 值类型 Class
         * @return this
         * @throws IllegalArgumentException 不支持的参数类型
         */
        @Nonnull
        public Builder type(@Nonnull Class<?> typeClass) {
            if (typeClass == String.class) {
                this.type = OptionType.STRING;
            } else if (typeClass == Integer.class || typeClass == int.class) {
                this.type = OptionType.INTEGER;
            } else if (typeClass == Long.class || typeClass == long.class) {
                this.type = OptionType.LONG;
            } else if (typeClass == Double.class || typeClass == double.class) {
                this.type = OptionType.DOUBLE;
            } else if (typeClass == Boolean.class || typeClass == boolean.class) {
                this.type = OptionType.BOOLEAN;
                this.flag = true;
            } else if (typeClass == java.nio.file.Path.class) {
                this.type = OptionType.PATH;
            } else if (typeClass.isEnum()) {
                Class<? extends Enum<?>> enumCls = (Class<? extends Enum<?>>) typeClass;
                this.type = OptionType.ENUM;
                this.enumType = enumCls;
                this.enumConstants = buildEnumConstants(enumCls);
            } else {
                throw new IllegalArgumentException("不支持的选项类型: " + typeClass.getName()
                        + "。支持的类型: String, Integer, Long, Double, Boolean, Path, 及 Enum 子类");
            }
            return this;
        }

        /**
         * 设置此选项为必需。
         *
         * @return this
         */
        @Nonnull
        public Builder required() {
            this.required = true;
            return this;
        }

        /**
         * 设置此选项为可选。
         *
         * @return this
         */
        @Nonnull
        public Builder optional() {
            this.required = false;
            return this;
        }

        /**
         * 设置是否为布尔标志。
         *
         * @param flag 如果为 true 则此选项不需要值
         * @return this
         */
        @Nonnull
        public Builder flag(boolean flag) {
            this.flag = flag;
            if (flag) {
                this.type = OptionType.BOOLEAN;
            }
            return this;
        }

        /**
         * 设置默认值。
         *
         * @param defaultValue 默认值
         * @return this
         */
        @Nonnull
        public Builder defaultValue(@Nullable Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }

        /**
         * 设置枚举选项是否忽略大小写（默认 false）。
         *
         * @param ignoreCase 是否忽略大小写
         * @return this
         */
        @Nonnull
        public Builder enumIgnoreCase(boolean ignoreCase) {
            this.enumIgnoreCase = ignoreCase;
            return this;
        }

        /**
         * 构建 {@link CliOption} 实例。
         *
         * @return CliOption 实例
         * @throws IllegalStateException 如果长选项名称为空，或 ENUM 类型未指定枚举类
         */
        @Nonnull
        public CliOption build() {
            if (StringUtils.isBlank(longName)) {
                throw new IllegalStateException("Long option name must not be blank");
            }
            if (type == OptionType.ENUM && enumType == null) {
                throw new IllegalStateException("ENUM 类型选项必须通过 type(Class<?>) 指定枚举类");
            }
            return new CliOption(this);
        }
    }
}
