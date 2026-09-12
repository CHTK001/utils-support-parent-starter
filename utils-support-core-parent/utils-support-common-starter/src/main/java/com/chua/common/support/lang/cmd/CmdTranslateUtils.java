package com.chua.common.support.lang.cmd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
* Linux → Windows PowerShell/cmd 命令翻译器。
*
* <p>提供常用 Linux 命令到 Windows 的等价转换，
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

    /** PowerShell 命令前缀（WinRM 默认走 cmd.exe，PS 命令需包装） */
    private static final String PS_PREFIX = "powershell -Command \"";
    private static final String PS_SUFFIX = "\"";

    private static final Map<Pattern, String> LINUX_TO_WIN = new LinkedHashMap<>();

    static {
        // ---- 网络 ----
        add("^ifconfig\\s*(.*)", "ipconfig $1");
        add("^ping\\s+-c\\s+(\\d+)\\s+(.*)", "ping -n $1 $2");
        add("^ping\\s+(.*)", "ping -n 4 $1");
        add("^netstat\\s+(-[a-zA-Z]+)?\\s*(.*)", "netstat $2");
        add("^ss\\s+(.*)", "netstat -an $1");
        add("^ip\\s+addr\\s*(.*)", ps("Get-NetIPAddress $1 | Format-List"));
        add("^ip\\s+route\\s*(.*)", ps("Get-NetRoute"));
        add("^hostname\\s*(.*)", "hostname $1");
        add("^curl\\s+'([^']+)'\\s*(.*)", ps("Invoke-RestMethod -Uri '$1' $2"));
        add("^wget\\s+'([^']+)'\\s*(.*)", ps("Invoke-WebRequest -Uri '$1' -OutFile 'download' $2"));

        // ---- 进程/系统 ----
        add("^ps\\s+aux(?!.*powershell)",
                ps("Get-Process | Select-Object Id,ProcessName,CPU,WorkingSet64,StartTime | Format-Table -AutoSize"));
        add("^ps\\s+(.*)", ps("Get-Process $1 | Format-Table -AutoSize"));
        add("^uptime\\s*(.*)", ps("(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')"));
        add("^uname\\s*(-a|-r|-s|-n)?\\s*(.*)",
                ps("[System.Environment]::MachineName + ' ' + [System.Environment]::OSVersion.ToString()"));
        add("^whoami\\s*(.*)", "whoami $1");
        add("^id\\s*(.*)", "whoami /user; whoami /groups");

        // ---- 文件/目录 ----
        add("^ls\\s+-la\\s*(.*)", "dir /a $1");
        add("^ls\\s+-lh\\s*(.*)", "dir /a $1");
        add("^ls\\s+-a\\s*(.*)", "dir /a $1");
        add("^ls\\s+(-[a-zA-Z]+\\s+)?(.*)", "dir $2");
        add("^ls\\s*(.*)", "dir $1");
        add("^cat\\s+(.*)", ps("Get-Content $1 -Encoding UTF8"));
        add("^less\\s+(.*)", ps("Get-Content $1 -Encoding UTF8 | More"));
        add("^head\\s+-n\\s+(\\d+)\\s+(.*)", ps("Get-Content $2 -Encoding UTF8 | Select-Object -First $1"));
        add("^head\\s+(\\d+)\\s+(.*)", ps("Get-Content $2 -Encoding UTF8 | Select-Object -First $1"));
        add("^tail\\s+-n\\s+(\\d+)\\s+(.*)", ps("Get-Content $1 -Encoding UTF8 | Select-Object -Last $2"));
        add("^tail\\s+(\\d+)\\s+(.*)", ps("Get-Content $1 -Encoding UTF8 | Select-Object -Last $2"));
        add("^tail\\s+-f\\s+(.*)", ps("Get-Content $1 -Encoding UTF8 -Wait"));

        // ---- 文件操作 ----
        add("^mkdir\\s+-p\\s+(.*)", ps("New-Item -ItemType Directory -Path '$1' -Force"));
        add("^mkdir\\s+(.*)", ps("New-Item -ItemType Directory -Path '$1'"));
        add("^rm\\s+-rf\\s+(.*)", ps("Remove-Item -Path '$1' -Force -Recurse -ErrorAction SilentlyContinue"));
        add("^rm\\s+-r\\s+(.*)", ps("Remove-Item -Path '$1' -Recurse -Force"));
        add("^rm\\s+(.*)", ps("Remove-Item -Path '$1' -Force"));
        add("^rmdir\\s+(.*)", ps("Remove-Item -Path '$1' -Force"));
        add("^del\\s+(.*)", "del $1");
        add("^cp\\s+(.*)\\s+(.*)", ps("Copy-Item -Path '$1' -Destination '$2'"));
        add("^mv\\s+(.*)\\s+(.*)", ps("Move-Item -Path '$1' -Destination '$2'"));
        add("^ln\\s+-s\\s+(.*)\\s+(.*)", ps("New-Item -Path '$2' -ItemType Junction -Target '$1'"));
        add("^find\\s+(.+?)\\s+-name\\s+['\"]([^'\"]+)['\"]",
                ps("Get-ChildItem -Path '$1' -Recurse -Filter '$2' | Select-Object FullName"));
        add("^find\\s+(.*)",
                ps("Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Select-Object FullName,Length,LastWriteTime"));

        // ---- 搜索/过滤 ----
        add("^grep\\s+-r\\s+(.+?)\\s+(.+)",
                ps("Select-String -Pattern '$1' -Path '$2' -Recurse | Select-Object Path,Line"));
        add("^grep\\s+-i\\s+(.+?)\\s+(.+)",
                ps("Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line"));
        add("^grep\\s+(.+?)\\s+(.+)",
                ps("Select-String -Pattern '$1' -Path '$2' | Select-Object Path,Line"));
        add("^grep\\s+(.*)", ps("Select-String -Pattern '$1' | Select-Object Path,Line"));
        add("^ag\\s+(.*)", ps("Select-String -Pattern '$1' -Recurse"));
        add("^rg\\s+(.*)", ps("Select-String -Pattern '$1' -Recurse"));

        // ---- 磁盘/空间 ----
        add("^df\\s+-h\\s*(.*)",
                ps("Get-Volume | Where-Object { $_.DriveLetter } | Select-Object DriveLetter, @{N='Rem(GB)';E={[math]::Round($_.SizeRemaining/1GB,1)}}, @{N='Total(GB)';E={[math]::Round($_.Size/1GB,1)}} | Format-Table -AutoSize"));
        add("^du\\s+-sh\\s+(.*)",
                ps("(Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum / 1GB GB"));
        add("^du\\s+-h\\s+(.*)",
                ps("Get-ChildItem -Path '$1' -Recurse -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum | ForEach-Object { '{0:N2} GB' -f ($_.Sum/1GB) }"));

        // ---- 环境变量 ----
        add("^env\\s*(.*)", ps("[Environment]::GetEnvironmentVariables() | Format-Table"));
        add("^set\\s*(.*)", ps("[Environment]::GetEnvironmentVariables() | Format-Table"));
        add("^echo\\s+(.*)", "echo $1");

        // ---- 其他 ----
        add("^date\\s*(.*)", ps("(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')"));
        add("^time\\s*(.*)", ps("(Get-Date).ToString('HH:mm:ss')"));
        add("^pwd\\s*(.*)", ps("Get-Location | Select-Object -ExpandProperty Path"));
        add("^cd\\s+(.*)", "cd $1; pwd");
    }

    private static void add(String regex, String replacement) {
        LINUX_TO_WIN.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement);
    }

    /** 将 PowerShell 表达式包装为 powershell -Command "..." */
    private static String ps(String expr) {
        return PS_PREFIX + expr + PS_SUFFIX;
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
                try {
                    return m.replaceAll(entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Replacement string contains illegal group reference (e.g. $2 with no group 2).
                    // Fall back to replacing captured groups one-by-one.
                    return replaceCaptures(m, entry.getValue());
                }
            }
        }
        return trimmed;
    }

    /**
    * 手动替换 Matcher 捕获组（避免 Illegal group reference）。
     */
    private static String replaceCaptures(java.util.regex.Matcher m, String template) {
        int g = m.groupCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.length(); i++) {
            if (template.charAt(i) == '$' && i + 1 < template.length() && Character.isDigit(template.charAt(i + 1))) {
                int num = template.charAt(i + 1) - '0';
                if (num > 0 && num <= g) {
                    sb.append(m.group(num));
                    i++;
                } else {
                    sb.append('$'); // drop invalid reference
                }
            } else {
                sb.append(template.charAt(i));
            }
        }
        return sb.toString();
    }

    /**
    * 判断给定命令是否可被翻译（即命中映射表）。
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
