package com.chua.desktop.support;
/**
 * @author CH
 * @since 4.0.0
 */

public class LinuxDesktopNotifier implements NativeDesktopNotifier {
    @Override
    /**
     * 通知
    */
    public void notify(String title, String content, String icon) throws Exception {
        ProcessBuilder pb;
        if (icon != null && !icon.isBlank()) {
            pb = new ProcessBuilder("notify-send", "-i", icon, title, content);
        } else {
            pb = new ProcessBuilder("notify-send", title, content);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() {
        String os = System.getProperty("os.name","").toLowerCase();
        return !os.contains("win") && !os.contains("mac");
    }
    @Override public String getPlatform() { return "linux"; }
}
