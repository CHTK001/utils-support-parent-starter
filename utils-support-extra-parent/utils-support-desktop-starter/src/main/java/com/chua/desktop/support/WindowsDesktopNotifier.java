package com.chua.desktop.support;

import com.chua.common.support.lang.cmd.OsFamily;

/**
 * Windows 桌面通知实现。
 *
 * @author CH
 * @since 4.0.0
 */

public class WindowsDesktopNotifier implements NativeDesktopNotifier {
    /**
     * 通知
    */
    @Override
    public void notify(String title, String content, String icon) throws Exception {
        String script = String.format(
            "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null;"
            + "$ni=New-Object System.Windows.Forms.NotifyIcon;"
            + "$ni.Icon=[System.Drawing.SystemIcons]::Information;"
            + "$ni.Visible=True;"
            + "$ni.ShowBalloonTip(5000,'%s','%s',[System.Windows.Forms.ToolTipIcon]::Info)",
            title.replace("'", "''"), content.replace("'", "''"));
        var pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() { return OsFamily.current().isWindows(); }
    @Override public String getPlatform() { return "windows"; }
}
