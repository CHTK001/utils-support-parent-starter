package com.chua.common.support.lang.cmd.tools;

import com.chua.common.support.lang.cmd.CliTool;
import com.chua.common.support.lang.cmd.CliToolDescriptor;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
* NVIDIA 显卡状态查询工具 nvidia-smi。
*
* <p>此前深度学习模块为了兼容 Windows 与 Linux，硬编码了
* {@code nvidia-smi} 与 {@code nvidia-smi.exe} 两个名字轮流尝试，
* 且没有处理 Windows 下默认不在 PATH 中的情况。改用本类后由
* {@code ExecutableLocator} 按平台统一处理名称与候选目录。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* NvidiaSmiTool nvidiaSmi = new NvidiaSmiTool();
* if (nvidiaSmi.isAvailable()) {
*     List<String> gpus = nvidiaSmi.queryGpuNames();
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("nvidia-smi")
public class NvidiaSmiTool extends CliTool {

    /** 版本输出格式：{@code NVIDIA-SMI 535.104.05   Driver Version: 535.104.05} */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("NVIDIA-SMI[^0-9]*(\\d+\\.\\d+(?:\\.\\d+)?)");

    /**
    * 创建 nvidia-smi 工具实例，使用预置的工具描述。
     */
    public NvidiaSmiTool() {
        super(CliToolDescriptor.builder("nvidia-smi")
                .displayName("NVIDIA 显卡状态查询工具")
                .windowsExecutable("nvidia-smi.exe")
                .envKey("NVIDIA_SMI_BIN")
                .candidateDirs(
                        "C:\\Program Files\\NVIDIA Corporation\\NVSMI",
                        "C:\\Windows\\System32",
                        "/usr/bin",
                        "/usr/local/bin")
                // 部分旧版驱动不支持 --version 参数，而不带参数执行时
                // 输出首行必定包含版本号，因此这里不传任何探测参数
                .versionArgs()
                .versionPattern(VERSION_PATTERN)
                .defaultTimeout(30, TimeUnit.SECONDS)
                .build());
    }

    /**
    * 查询所有 GPU 的名称。
    *
    * <p>等价于 {@code nvidia-smi --query-gpu=name --format=csv,noheader}，
    * 并在此处完成 CSV 解析，调用方无需再处理输出格式。</p>
    *
    * @return GPU 名称列表，查询失败或未安装时返回空列表
     */
    @Nonnull
    public List<String> queryGpuNames() {
        return queryGpuColumn("name");
    }

    /**
    * 查询指定 GPU 属性列，返回每块显卡一行的结果。
    *
    * <p>常用取值：{@code name}、{@code memory.total}、{@code memory.used}、
    * {@code utilization.gpu}、{@code temperature.gpu}、{@code driver_version}。</p>
    *
    * @param column GPU 属性名
    * @return 属性值列表，查询失败或未安装时返回空列表
     */
    @Nonnull
    public List<String> queryGpuColumn(@Nonnull String column) {
        CmdResult result = execute("--query-gpu=" + column, "--format=csv,noheader");
        if (!result.isSuccess()) {
            return List.of();
        }
        String stdout = result.getStdout();
        if (StringUtils.isNullOrEmpty(stdout)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    /**
    * 查询 GPU 显存总量，单位为 MiB。
    *
    * @return 每块显卡的显存总量列表，解析失败时该显卡对应位置为 null
     */
    @Nonnull
    public List<Integer> queryGpuMemoryTotal() {
        List<String> raw = queryGpuColumn("memory.total");
        List<Integer> result = new ArrayList<>(raw.size());
        for (String value : raw) {
            result.add(parseMemory(value));
        }
        return result;
    }

    /**
    * 解析显存数值，形如 {@code 8192 MiB}。
    *
    * @param value 原始值
    * @return 显存数值，解析失败返回 null
     */
    private static Integer parseMemory(String value) {
        if (StringUtils.isNullOrEmpty(value)) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return null;
        }
        try {
            return Integer.valueOf(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
