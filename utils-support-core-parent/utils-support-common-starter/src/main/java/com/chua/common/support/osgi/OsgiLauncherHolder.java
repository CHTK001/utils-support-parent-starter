package com.chua.common.support.osgi;


/**
 * OSGI 启动器全局单例持有者。
 * <p>
 * OSGI 框架有且只能启动一个实例，通过该类全局持有。
 * </p>
 *
 * @author CH
 */
public class OsgiLauncherHolder {

    private static volatile OsgiLauncher INSTANCE;

    private OsgiLauncherHolder() {}

    /**
     * 设置全局唯一的 OSGI 启动器实例。
     *
     * @param launcher OSGI 启动器
     */
    public static void setInstance(OsgiLauncher launcher) {
        INSTANCE = launcher;
    }

    /**
     * 获取全局唯一的 OSGI 启动器实例。
     *
     * @return OSGI 启动器，未设置时返回 null
     */
    public static OsgiLauncher getInstance() {
        return INSTANCE;
    }

    /**
     * 判断 OSGI 框架是否已激活。
     *
     * @return 已激活返回 true
     */
    public static boolean isActive() {
        OsgiLauncher launcher = INSTANCE;
        return launcher != null && launcher.isActive();
    }

    /**
     * 清除全局实例（通常在框架停止时调用）。
     */
    public static void clear() {
        INSTANCE = null;
    }
}
