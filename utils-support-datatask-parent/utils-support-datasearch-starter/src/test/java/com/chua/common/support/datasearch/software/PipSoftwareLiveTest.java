package com.chua.common.support.datasearch.software;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.impl.PipSoftwareProvider;

import java.util.List;

/**
 * pip 软件包提供器联调测试（真实调用本机 pip 24.2）。
 *
 * <p>回归点：</p>
 * <ol>
 *   <li>{@code search} 曾把 {@code version} 硬编码为空串，pip 输出里的版本被丢弃；</li>
 *   <li>{@code search} 曾以字符串形式拼命令（{@code "pip install ... " + keyword + " 2>&1"}），
 *       {@code CmdExecutors} 对字符串命令走 {@code cmd.exe /c}，keyword 里的 {@code &}
 *       可拼接任意命令执行。改为数组形式后参数不再经 shell 解析。</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class PipSoftwareLiveTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        PipSoftwareProvider provider = new PipSoftwareProvider();

        List<SoftwareInfo> results = provider.search("requests");
        System.out.println("      search(requests) ->");
        results.forEach(s -> System.out.println("        " + s.getName() + " " + s.getVersion()
                + " src=" + s.getSource() + " id=" + s.getPackageId()));
        check("pip 可返回解析结果", !results.isEmpty());
        check("version 已填充（修复前恒为空串）", !results.isEmpty()
                && results.stream().allMatch(s -> s.getVersion() != null && !s.getVersion().isEmpty()));
        check("包名不含版本约束残渣", results.stream().allMatch(s ->
                !s.getName().contains(">") && !s.getName().contains("=")));
        check("source 固定为 pip", results.stream().allMatch(s -> "pip".equals(s.getSource())));

        List<SoftwareInfo> injected = provider.search("requests & echo Collecting evil-pkg");
        System.out.println("      search(注入串) -> size=" + injected.size());
        injected.forEach(s -> System.out.println("        " + s.getName() + " " + s.getVersion()));
        check("keyword 中的 & 未被当作命令执行", injected.stream()
                .noneMatch(s -> "evil-pkg".equals(s.getName())));

        System.out.println("[PipSoftwareLiveTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
