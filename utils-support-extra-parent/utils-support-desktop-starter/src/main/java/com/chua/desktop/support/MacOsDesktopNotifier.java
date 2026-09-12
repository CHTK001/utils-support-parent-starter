package com.chua.desktop.support;
/**
* @author CH
* @since 4.0.0
 */

public class MacOsDesktopNotifier implements NativeDesktopNotifier {
    @Override
    /** 通知 */
    public void notify(String title, String content, String icon) throws Exception {
        String script = String.format("display notification \"%s\" with title \"%s\"",
            content.replace("\\", "\\\\").replace("\"", "\\\""),
            title.replace("\\", "\\\\").replace("\"", "\\\""));
        ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() { return System.getProperty("os.name","").toLowerCase().contains("mac"); }
    @Override public String getPlatform() { return "macos"; }
}
