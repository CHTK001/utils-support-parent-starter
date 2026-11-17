package com.chua.desktop.support;
import com.chua.common.support.lang.cmd.OsFamily;
import com.chua.common.support.utils.StringUtils;
/**
 * Linux 桌面通知实现。
 *
 * @author CH
 * @since 4.0.0
 */

public class LinuxDesktopNotifier implements NativeDesktopNotifier {
    /**
     * 通知
    */
    @Override
    public void notify(String title, String content, String icon) throws Exception {
        ProcessBuilder pb;
        if (StringUtils.isNotBlank(icon)) {
            pb = new ProcessBuilder("notify-send", "-i", icon, title, content);
        } else {
            pb = new ProcessBuilder("notify-send", title, content);
        }
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() {
        OsFamily os = OsFamily.current();
        return !os.isWindows() && !os.isMacOs();
    }
    @Override public String getPlatform() { return "linux"; }
}
