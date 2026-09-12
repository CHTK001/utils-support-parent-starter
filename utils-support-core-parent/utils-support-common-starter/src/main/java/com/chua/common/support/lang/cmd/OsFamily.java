package com.chua.common.support.lang.cmd;

/**
* 操作系统族，用于收敛分散在各模块中的 {@code System.getProperty("os.name").contains("win")} 判断。
*
* <p>不同模块此前各自用字符串匹配判断平台，规则不一致（有的判断 {@code "windows"}，
* 有的判断 {@code "win"}，有的判断 {@code "mac"}），这里统一为单一事实来源。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* if (OsFamily.current().isWindows()) {
*     // Windows 专有逻辑
* }
* String exe = "tool" + OsFamily.current().executableSuffix();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public enum OsFamily {

    /** Windows */
    WINDOWS,
    /** macOS */
    MACOS,
    /** Linux 及其他类 Unix（不含 macOS） */
    LINUX,
    /** 无法识别的操作系统，按类 Unix 处理 */
    UNKNOWN;

    /** 系统属性名：操作系统名称 */
    private static final String OS_NAME_PROPERTY = "os.name";

    /** 操作系统名称小写形式，用于前缀匹配 */
    private static final String OS_NAME = System.getProperty(OS_NAME_PROPERTY, "").toLowerCase();

    /** 当前操作系统族，在类加载时确定 */
    private static final OsFamily CURRENT = detect();

    /**
    * 检测当前操作系统族。
    *
    * @return 当前操作系统族，不会返回 {@code null}
     */
    private static OsFamily detect() {
        if (OS_NAME.contains("win")) {
            return WINDOWS;
        }
        if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) {
            return MACOS;
        }
        if (OS_NAME.contains("nux") || OS_NAME.contains("nix") || OS_NAME.contains("aix")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    /**
    * 获取当前操作系统族。
    *
    * @return 当前操作系统族
     */
    public static OsFamily current() {
        return CURRENT;
    }

    /**
    * 判断是否为 Windows。
    *
    * @return 是 Windows 返回 true
     */
    public boolean isWindows() {
        return this == WINDOWS;
    }

    /**
    * 判断是否为 macOS。
    *
    * @return 是 macOS 返回 true
     */
    public boolean isMacOs() {
        return this == MACOS;
    }

    /**
    * 判断是否为 Linux 或无法识别的系统（按类 Unix 处理）。
    *
    * @return 是类 Unix 系统返回 true
     */
    public boolean isUnixLike() {
        return this == LINUX || this == UNKNOWN;
    }

    /**
    * 获取该平台下可执行文件的扩展名。
    *
    * <p>Windows 返回 {@code ".exe"}，其余平台返回空字符串。
    * 该后缀只用于拼装候选文件名，若可执行文件本身已带扩展名则调用方无需追加。</p>
    *
    * @return 可执行文件扩展名，非 Windows 返回空字符串
     */
    public String executableSuffix() {
        return this == WINDOWS ? ".exe" : "";
    }

    /**
    * 获取该平台下搜索可执行文件时需要考虑的候选扩展名。
    *
    * <p>Windows 依次为 {@code .exe}、{@code .bat}、{@code .cmd}（顺序与 PATHEXT 惯例一致），
    * 其余平台只包含空后缀，表示只匹配无扩展名的可执行文件。</p>
    *
    * @return 候选扩展名数组
     */
    public String[] executableSuffixes() {
        if (this == WINDOWS) {
            return new String[]{".exe", ".bat", ".cmd", ""};
        }
        return new String[]{""};
    }
}
