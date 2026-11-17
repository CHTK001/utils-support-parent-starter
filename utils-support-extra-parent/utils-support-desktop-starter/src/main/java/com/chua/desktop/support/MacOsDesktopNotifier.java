package com.chua.desktop.support;

import com.chua.common.support.lang.cmd.OsFamily;

/**
 * macOS 桌面通知实现。
 *
 * @author CH
 * @since 4.0.0
 */

public class MacOsDesktopNotifier implements NativeDesktopNotifier {
    /**
     * 通知
    */
    @Override
    public void notify(String title, String content, String icon) throws Exception {
        String script = String.format("display notification \"%s\" with title \"%s\"",
            content.replace("\\", "\\\\").replace("\"", "\\\""),
            title.replace("\\", "\\\\").replace("\"", "\\\""));
        ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() { return OsFamily.current().isMacOs(); }
    @Override public String getPlatform() { return "macos"; }
}
