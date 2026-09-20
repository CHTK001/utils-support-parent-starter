package com.chua.common.support.datasearch.software;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.datasearch.software.spi.impl.CargoSoftwareProvider;
import com.chua.common.support.datasearch.software.spi.impl.NpmSoftwareProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.LineCallback;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 包管理器 provider 的命令注入回归测试（真实调用本机 cargo / npm）。
 *
 * <p>这些 provider 过去以字符串拼命令交给 {@code CmdExecutors}，而字符串形式会走
 * {@code cmd.exe /c}（见 {@code ProcessCmdExecutor.parseCommand}），keyword 里的
 * {@code &} 可拼出任意命令。改为数组形式后参数直传进程，不再经 shell。</p>
 *
 * <p>探针用「注入语句能否写出一个临时文件」判定 shell 是否参与了执行：
 * 先用旧的字符串形式做一次对照（应写出文件，证明探针有效），再用 provider 走一遍
 * （不得写出文件）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class SoftwareInjectionTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) throws IOException {
        SoftwareProvider cargo = new CargoSoftwareProvider();
        SoftwareProvider npm = new NpmSoftwareProvider();

        List<SoftwareInfo> crates = cargo.search("serde");
        System.out.println("      cargo search(serde) -> " + brief(crates));
        check("cargo 数组形式仍可正常执行并解析", !crates.isEmpty());
        check("cargo 版本已解析", crates.stream().allMatch(s -> !s.getVersion().isEmpty()));

        List<SoftwareInfo> packages = npm.search("express");
        System.out.println("      npm search(express) -> " + brief(packages));
        check("npm 数组形式仍可正常执行并解析", !packages.isEmpty());
        check("npm 描述已解析", packages.stream().anyMatch(s -> !s.getDescription().isEmpty()));

        probe(cargo, "cargo");
        probe(npm, "npm");

        control();

        System.out.println("[SoftwareInjectionTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 让 provider 处理一条带 shell 元字符的 keyword，确认它没有被当作命令执行。
     *
     * @param provider 被测 provider
     * @param tag 标记
     */
    private static void probe(SoftwareProvider provider, String tag) throws IOException {
        Path marker = marker(tag);
        Files.deleteIfExists(marker);
        String payload = "serde & echo pwned > " + marker.toString().replace("\\", "/");
        List<SoftwareInfo> results = provider.search(payload);
        boolean wrote = Files.exists(marker);
        Files.deleteIfExists(marker);
        System.out.println("      " + tag + " 注入探针 -> results=" + results.size() + " markerWritten=" + wrote);
        check(tag + " 的 keyword 未经 shell 执行", !wrote);
    }

    /**
     * 对照实验：同样的写法走旧的字符串命令通道，必须真的执行，否则上面的断言毫无意义。
     */
    private static void control() throws IOException {
        Path marker = marker("control");
        Files.deleteIfExists(marker);
        CmdExecutors.executeWithOutput("cargo search serde & echo pwned > "
                + marker.toString().replace("\\", "/"), 30, TimeUnit.SECONDS, new LineCallback() {
                    @Override
                    public void onLine(String line) {
                        // 对照实验只关心标记文件
                    }

                    @Override
                    public void onComplete(int exitCode) {
                    }

                    @Override
                    public void onError(String command, Throwable throwable) {
                    }
                });
        boolean wrote = Files.exists(marker);
        Files.deleteIfExists(marker);
        System.out.println("      字符串形式对照 -> markerWritten=" + wrote);
        check("对照：字符串形式确实会被 shell 执行（探针有效）", wrote);
    }

    private static Path marker(String tag) {
        return Path.of(System.getProperty("java.io.tmpdir"), "cmd-inject-" + tag + ".txt");
    }

    private static String brief(List<SoftwareInfo> list) {
        StringBuilder sb = new StringBuilder("size=" + list.size());
        list.stream().limit(2).forEach(s -> sb.append(" | ").append(s.getName())
                .append(' ').append(s.getVersion()).append(" :: ").append(s.getDescription()));
        return sb.toString();
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
