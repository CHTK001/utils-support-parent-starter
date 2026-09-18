package com.chua.desktop.support;

/**
* 桌面通知 SPI 接口。
*
* @author CH
* @since 1.0.0
 */
public interface NativeDesktopNotifier {

    /**
    * 发送桌面通知。
    *
    * @param title   通知标题
    * @param content 通知内容
    * @param icon    图标路径
    * @throws Exception 通知发送失败时抛出
    */
    void notify(String title, String content, String icon) throws Exception;

    /**
    * 判断当前平台是否支持桌面通知。
    *
    * @return 支持返回 true
    */
    boolean isSupported();

    /**
    * 获取当前平台名称。
    *
    * @return 平台名称（如 窗口、mac、Linux）
    */
    String getPlatform();

    /**
    * 根据操作系统创建对应的桌面通知实现。
    *
    * @return 桌面通知实例
    */
    static NativeDesktopNotifier create() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new WindowsDesktopNotifier();
        } else if (os.contains("mac")) {
            return new MacOsDesktopNotifier();
        } else {
            return new LinuxDesktopNotifier();
        }
    }
}
