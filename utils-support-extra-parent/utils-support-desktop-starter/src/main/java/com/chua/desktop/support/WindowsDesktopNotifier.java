package com.chua.desktop.support;
/**
 * @author CH
 */

public class WindowsDesktopNotifier implements NativeDesktopNotifier {
    @Override
    /** 通知 */
    public void notify(String title, String content, String icon) throws Exception {
        String script = String.format(
            "[System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms') | Out-Null;"
            + "$ni=New-Object System.Windows.Forms.NotifyIcon;"
            + "$ni.Icon=[System.Drawing.SystemIcons]::Information;"
            + "$ni.Visible=True;"
            + "$ni.ShowBalloonTip(5000,'%s','%s',[System.Windows.Forms.ToolTipIcon]::Info)",
            title.replace("'", "''"), content.replace("'", "''"));
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.waitFor();
    }
    @Override public boolean isSupported() { return System.getProperty("os.name","").toLowerCase().contains("win"); }
    @Override public String getPlatform() { return "windows"; }
}
