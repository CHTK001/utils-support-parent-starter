package com.chua.common.support.host.impl;

import com.chua.common.support.host.HostAnchor;
import com.chua.common.support.host.HostFacts;
import com.chua.common.support.host.HostInfoProvider;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.OsFamily;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Windows 主机不可变信息采集：单次 PowerShell 批量取回 CIM 与注册表项。
 *
 * <p>为什么要写成一个脚本而不是逐项取：Windows 上每次 PowerShell 冷启动约 200~400ms，
 * 七项分七次调用会把「生成一个 ID」拖到 2 秒以上。这里一次进程取全部，
 * 单项失败只让该项为空（脚本内每项都带 {@code -ErrorAction SilentlyContinue}）。</p>
 *
 * <p>取磁盘序列号用 {@code Get-Partition | Get-Disk}（Win8/Server2012 起可用），
 * 旧系统回退到 {@code Win32_DiskDrive} 的 0 号盘。两者的 serial 口径一致。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
@Spi(value = "windows-cim", order = 300)
public class WindowsHostInfoProvider implements HostInfoProvider {

    /**
     * SPI 名称
     */
    private static final String NAME = "windows-cim";

    /**
     * 默认权重：优先级 300
     */
    private static final int DEFAULT_PRIORITY = 300;

    /**
     * 采集超时（毫秒）：CIM 查询涉及 WMI，冷启动较慢，给足余量。
     *
     * <p>实测（Windows 10 + 慢盘/杀软环境）PowerShell 冷启动约 8~11 秒，
     * 加上 7 项 CIM 查询合计约 13 秒。15 秒上限会在慢机上把「取不到」误判成
     * 环境限制，进而让 {@code uniqueId()} 抛异常、{@code hasImmutableInfo()} 返回 false。
     * 本方法只在进程内首次调用时执行一次（结果由 {@link HostInfoRouter} 缓存），
     * 因此放宽到 60 秒不构成热路径开销。</p>
     */
    private static final long TIMEOUT_MS = 60_000L;

    /**
     * 输出行分隔：{@code key\tvalue}，用制表符避免序列号里出现空格时错位
     */
    private static final String SEP = "\t";

    /**
     * 采集脚本。全部字段用 {@code [string]} 强转，取不到时输出空串而不是 "null"。
     *
     * <p>这个脚本必须通过 {@link #ENCODED} ({@code -EncodedCommand}) 投递，不能直接放进 {@code -Command}
     * 参数：PowerShell 的参数解析会拆散 {@code \"}、吞掉 `` `t ``，最终得到空输出。</p>
     */
    private static final String SCRIPT = String.join("\n",
            "$ErrorActionPreference='SilentlyContinue'",
            "$ProgressPreference='SilentlyContinue'",
            "$sys=$env:SystemDrive",
            "$csp=Get-CimInstance Win32_ComputerSystemProduct",
            "$bb=Get-CimInstance Win32_BaseBoard",
            "$bios=Get-CimInstance Win32_BIOS",
            "$cpu=Get-CimInstance Win32_Processor | Select-Object -First 1",
            "$guid=(Get-ItemProperty 'HKLM:\\SOFTWARE\\Microsoft\\Cryptography').MachineGuid",
            "$diskSerial=''",
            "try{ $d=Get-Partition -DriveLetter $sys.TrimEnd(':') | Get-Disk; $diskSerial=[string]$d.SerialNumber }catch{}",
            "if([string]::IsNullOrWhiteSpace($diskSerial)){ $diskSerial=[string](Get-CimInstance Win32_DiskDrive | Where-Object { $_.Index -" +
                "eq 0 }).SerialNumber }",
            "$vol=Get-CimInstance Win32_LogicalDisk -Filter \"DeviceID='$sys'\"",
            "$nic=Get-NetAdapter | Where-Object { $_.Status -eq 'Up' -and -not $_.Virtual } | Sort-Object ifIndex | Select-Object -First 1",
            "$mac=''",
            "if($nic){ $mac=[string]$nic.MacAddress }",
            "$out=[ordered]@{",
            "  systemUuid=[string]$csp.UUID",
            "  boardSerial=[string]$bb.SerialNumber",
            "  biosSerial=[string]$bios.SerialNumber",
            "  machineId=[string]$guid",
            "  diskSerial=$diskSerial",
            "  cpuId=[string]$cpu.ProcessorId",
            "  volumeSerial=[string]$vol.VolumeSerialNumber",
            "  primaryMac=$mac",
            "  hostname=[string]$env:COMPUTERNAME",
            "}",
            "foreach($k in $out.Keys){ $v=($out[$k] -replace '\\s+',' ').Trim(); Write-Output ($k + \"`t\" + $v) }");

    /**
     * 脚本投递 token。
     *
     * <p>{@code -EncodedCommand} 接收 base64 编码后的 UTF-16LE 字节串，PowerShell 5.1/7 均接受。
     * 先编码为 ASCII，既不用在进程参数里转义引号，也彻底规避了 {@code -Command} 的参数引号规则。</p>
     *
     * @see <a href="https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_signing">关于 Windows PowerShell 5.1 的 -EncodedCommand 约定</a>
     */
    private static final String ENCODED;

    static {
        ENCODED = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports() {
        return OsFamily.current().isWindows();
    }

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public HostFacts capture() {
        Map<HostAnchor, String> values = new EnumMap<>(HostAnchor.class);
        CmdResult result = CmdExecutors.execute(
                new String[]{"powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                        "-EncodedCommand", ENCODED},
                TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (result == null || !result.isSuccess()) {
            log.debug("[host] windows-cim 采集失败: {}",
                    result == null ? "无结果" : result.getStderr());
            return new HostFacts(values, NAME);
        }
        for (String line : result.getStdout().split("\\r?\\n")) {
            int index = line.indexOf(SEP);
            if (index <= 0) {
                continue;
            }
            HostAnchor anchor = byField(line.substring(0, index).trim());
            if (anchor != null) {
                values.put(anchor, line.substring(index + SEP.length()));
            }
        }
        return new HostFacts(values, NAME);
    }

    /**
     * 按字段名反查锚点。
     *
     * @param field 字段名
     * @return 锚点；未知字段返回 null
     */
    private static HostAnchor byField(String field) {
        for (HostAnchor anchor : HostAnchor.values()) {
            if (anchor.field().equals(field)) {
                return anchor;
            }
        }
        return null;
    }
}
