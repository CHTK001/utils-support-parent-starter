package com.chua.common.support.lang.cmd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Linux → Windows PowerShell 命令翻译器。
 *
 * <p>提供常用 Linux 命令到 Windows PowerShell/cmd 的等价转换，
 * 使得跨平台脚本（SSH/WinRM/PTY）可复用同一份命令。</p>
 *
 * <h3>已支持的映射</h3>
 * <pre>{@code
 * ifconfig        → ipconfig
 * ping -c N       → ping -n N
 * ps aux          → Get-Process | Select-Object
 * ls / ls -la     → dir /a
 * cat file        → Get-Content file
 * grep pat file   → Select-String pat file
 * head -n N       → Select-Object -First N
 * tail -n N / -f  → Select-Object -Last N / -Wait
 * mkdir -p        → New-Item -Force
 * rm -rf          → Remove-Item -Force -Recurse
 * find -name      → Get-ChildItem -Filter
 * df -h           → Get-Volume
 * env / set       → [Environment]::GetEnvironmentVariables()
 * pwd             → Get-Location
 * echo            → Write-Output
 * }</pre>
 *
 * <p>不在映射表中的命令原样返回，调用方可安全兜底。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class CmdTranslateUtils {

    private static final Map<Pattern, String> LINUX_TO_WIN = new LinkedHashMap<>();

    static {
        // ---- 网络 ----
        add("^ifconfig\\s*(.*)", "ipconfig $1");
        add("^ping\\s+-c\\s+(\\d+)\\s+(.*)", "ping -n $1 $2");
        add("^ping\\s+(.*)", "ping -n 4 $1");
        add("^netstat\\s+(-[a-zA-Z]+)?\\s*(.*)", "netstat $2");
        add("^ss\\s+(.*)", "netstat -an $1");
        add("^ip\\s+addr\\s*(.*)", "Get-NetIPAddress $1 | Format-List");
        add("^ip\\s+route\\s*(.*)", "Get-NetRoute");
        add("^hostname\\s*(.*)", "hostname $1");
        add("^curl\\s+'([^']+)'\\s*(.*)", "Invoke-RestMethod -Uri '$1' $2");
        add("^wget\\s+'([^']+)'\\s*(.*)", "Invoke-WebRequest -Uri '$1' -OutFile 'download' $2");

        // ---- 进程/系统 ----
        add("^ps\\s+aux(?!.*powershell)",
                "Get-Process | Select-Object Id,ProcessName,CPU,WorkingSet64,StartTime | Format-Table -AutoSize");
        add("^ps\\s+(.*)", "Get-Process $1 | Format-Table -AutoSize");
        add("^uptime\\s*(.*)", "(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')");
        add("^uname\\s*(-a|-r|-s|-n)?\\s*(.*)",
                "[System.Environment]::MachineName + ' ' + [System.Environment]::OSVersion.ToString()");
        add("^whoami\\s*(.*)", "whoami $1");
        add("^id\\s*(.*)", "whoami /user; whoami /groups");

        // ---- 文件/目录 ----
        add("^ls\\s+-la\\s*(.*)", "dir /a $1");
        add("^ls\\s+-lh\\s*(.*)", "dir /a $1");
        add("^ls\\s+-a\\s*(.*)", "dir /a $1");
        add("^ls\\s+(-[a-zA-Z]+\\s+)?(.*)", "dir $2");
        add("^ls\\s*(.*)", "dir $1");
        add("^cat\\s+(.*)", "Get-Content $1 -Encoding UTF8");
        add("^less\\s+(.*)", "Get-Content $1 -Encoding UTF8 | More");
        add("^head\\s+-n\\s+(\\d+)\\s+(.*)", "Get-Content $2 -Encoding UTF8 | Select-Object -First $1");
        add("^head\\s+(\\d+)\\s+(.*)", "Get-Content $2 -Encoding UTF8 | Select-Object -First $1");
        add("^tail\\s+-n\\s+(\\d+)\\s+(.*)", "Get-Content $1 -Encoding UTF8 | Select-Object -Last $2");
        add("^tail\\s+(\\d+)\\s+(.*)", "Get-Content $1 -Encoding UTF8 | Select-Object -Last $2");
        add("^tail\\s+-f\\s+(.*)", "Get-Content $1 -Encoding UTF8 -Wait");

        // ---- 文件操作 ----
        add("^mkdir\\s+-p\\s+(.*)", "New-Item -ItemType Directory -Path '$1' -Force");
        add("^mkdir\\s+(.*)", "New-Item -ItemType Directory -Path '$1'");
        add("^rm\\s+-rf\\s+(.*)", "Remove-Item -Path '$1' -Force -Recurse -ErrorAction SilentlyContinue");
        add("^rm\\s+-r\\s+(.*)", "Remove-Item -Path '$1' -Recurse -Force");
        add("^rm\\s+(.*)", "Remove-Item -Path '$1' -Force");
        add("^rmdir\\s+(.*)", "Remove-Item -Path '$1' -Force");
        add("^del\\s+(.*)", "Remove-Item -Path '$1' -Force");
        add("^cp\\s+(.*)\\s+(.*)", "Copy-Item -Path '$1' -Destination '$2'");
        add("^mv\\s+(.*)\\s+(.*)", "Move-Item -Path '$1' -Destination '$2'");
        add("^ln\\s+-s\\s+(.*)\\s+(.*)", "New-Item -Path '$2' -ItemType Junction -Target '$1'");
        add("^find\\s+(.+?)\\s+-name\\s+['\"]([^'\"]+)['\"]",
                "Get-ChildItem -Path '$1' -Recurse -Filter '$2' | Select-Object FullName");
        add("^find\\s+(.*)", "Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Select-Object FullName,Length,LastWriteTime");

        // ---- 搜索/过滤 ----
        add("^grep\\s+-r\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' -Recurse | Select-Object Path,Line");
        add("^grep\\s+-i\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line");
        add("^grep\\s+(.+?)\\s+(.+)", "Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line");
        add("^grep\\s+(.*)", "Select-String -Pattern '$1' | Select-Object Path,Line");
        add("^ag\\s+(.*)", "Select-String -Pattern '$1' -Recurse");
        add("^rg\\s+(.*)", "Select-String -Pattern '$1' -Recurse");

        // ---- 磁盘/空间 ----
        add("^df\\s+-h\\s*(.*)",
                "Get-Volume | Where-Object { $_.DriveLetter } | ForEach-Object { '{0} {1:N1}GB rem / {2:N1}GB total' -f $_.DriveLetter, ($_.SizeRemaining/1GB), ($_.Size/1GB) }");
        add("^du\\s+-sh\\s+(.*)",
                "(Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1GB GB");
        add("^du\\s+-h\\s+(.*)",
                "Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum | ForEach-Object { '{0:N2} GB' -f ($_.Sum/1GB) }");

        // ---- 环境变量 ----
        add("^env\\s*(.*)", "[Environment]::GetEnvironmentVariables() | Format-Table");
        add("^set\\s*(.*)", "[Environment]::GetEnvironmentVariables() | Format-Table");
        add("^echo\\s+(.*)", "Write-Output '$1'");

        // ---- 其他 ----
        add("^date\\s*(.*)", "(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')");
        add("^time\\s*(.*)", "(Get-Date).ToString('HH:mm:ss')");
        add("^pwd\\s*(.*)", "Get-Location | Select-Object -ExpandProperty Path");
        add("^cd\\s+(.*)", "Set-Location '$1'; Get-Location");
    }

    private static void add(String regex, String replacement) {
        LINUX_TO_WIN.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
    }

    private CmdTranslateUtils() {
    }

    /**
     * 将 Linux 命令转换为等效的 PowerShell/cmd 命令。
     * 若不在映射表中则原样返回。
     *
     * @param cmd 原始命令（支持完整命令行，含参数）
     * @return 转换后的命令；未匹配时返回原命令
     */
    public static String translate(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return cmd;
        }
        String trimmed = cmd.trim();
        for (Map.Entry<Pattern, String> entry : LINUX_TO_WIN.entrySet()) {
            java.util.regex.Matcher m = entry.getKey().matcher(trimmed);
            if (m.matches()) {
                return m.replaceAll(entry.getValue());
            }
        }
        return trimmed;
    }

    /**
     * 判断给定命令是否可被翻译（即命中映射表）。
     *
     * @param cmd 原始命令
     * @return true 表示命中翻译规则
     */
    public static boolean canTranslate(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return false;
        }
        String trimmed = cmd.trim();
        for (Map.Entry<Pattern, String> entry : LINUX_TO_WIN.entrySet()) {
            if (entry.getKey().matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回当前注册的翻译规则数量。
     */
    public static int ruleCount() {
        return LINUX_TO_WIN.size();
    }
}
