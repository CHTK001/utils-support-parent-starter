package com.chua.filesystem.log.support.bridge;

/**
* 平台检测工具类
*
* @author CH
* @since 4.0.0.42
 */
public final class PlatformSystems {

    /**
    * 操作系统名称属性
     */
    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase();

    /**
    * 私有构造函数，防止实例化
     */
    private PlatformSystems() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
    * 判断当前系统是否为 窗口
    *
    * @return true 如果系统是 窗口，否则为 false
     */
    public static boolean isWindows() {
        if (OS_NAME.contains("win")) {
            return true;
        }
        return false;
    }

    /**
    * 判断当前系统是否为 Linux
    *
    * @return true 如果系统是 Linux，否则为 false
     */
    public static boolean isLinux() {
        if (OS_NAME.contains("linux") || OS_NAME.contains("nux")) {
            return true;
        }
        return false;
    }

    /**
    * 判断当前系统是否为 macOS
    *
    * @return true 如果系统是 macOS，否则为 false
     */
    public static boolean isMacOs() {
        if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) {
            return true;
        }
        return false;
    }

    /**
    * 判断当前系统是否为类 Unix 系统（包括 Linux 和 macOS）
    *
    * @return true 如果系统是类 Unix 系统，否则为 false
     */
    public static boolean isUnixLike() {
        if (isLinux() || isMacOs()) {
            return true;
        }
        return false;
    }

    /**
    * 获取当前操作系统的名称
    *
    * @return 操作系统名称字符串
     */
    public static String getOsName() {
        return OS_NAME;
    }
}
