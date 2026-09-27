package com.chua.common.support.lang.cmd;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Windows 批处理外壳（{@code .cmd}/{@code .bat}）的定位与启动测试。
 *
 * <p>Windows 的 CreateProcess 不会套用 {@code PATHEXT}，因此数组形式启动 {@code npm}、
 * {@code gem} 这类只有批处理外壳的 CLI 会以 {@code CreateProcess error=2} 失败；
 * 而 {@code ExecutableLocator} 早期先匹配无扩展名文件，会把 Node 随附的 shell 脚本
 * {@code ...\nodejs\npm} 当成结果返回，同样无法启动。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class ExecutableLocatorShimTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) throws Exception {
        boolean windows = OsFamily.current().isWindows();
        System.out.println("      os=" + OsFamily.current() + " windows=" + windows);

        Path dir = Files.createTempDirectory("locator-shim");
        Path bare = dir.resolve("fakecli");
        Files.writeString(bare, "#!/bin/sh\necho bare\n");
        Path shim = dir.resolve("fakecli.cmd");
        Files.writeString(shim, "@echo off\necho shim\r\n");
        boolean launched = toExecutable(bare) && toExecutable(shim);
        System.out.println("      fixture=" + dir + " prepared=" + launched);

        Optional<Path> located = ExecutableLocator.locate(ExecutableLocator.LocateRequest
                .builder("fakecli")
                .candidateDirs(dir.toString())
                .searchPath(false)
                .systemLookup(false)
                .build());
        System.out.println("      locate(fakecli) -> " + located);
        check("定位结果优先取可启动的扩展名", windows
                ? located.map(p -> p.getFileName().toString().equals("fakecli.cmd")).orElse(false)
                : located.map(p -> p.getFileName().toString().equals("fakecli")).orElse(false));
        check("不会再返回无扩展名的 shell 脚本", windows
                ? located.map(p -> !p.getFileName().toString().equals("fakecli")).orElse(false)
                : located.isPresent());

        check("未安装的程序名原样返回",
                "not-installed-cli-xyz".equals(ExecutableLocator.resolveProgram("not-installed-cli-xyz")));
        check("已带扩展名的程序名原样返回",
                "cmd.exe".equals(ExecutableLocator.resolveProgram("cmd.exe")));
        check("绝对路径原样返回",
                bare.toString().equals(ExecutableLocator.resolveProgram(bare.toString())));
        if (!windows) {
            check("非 Windows 不改动程序名",
                    "fakecli".equals(ExecutableLocator.resolveProgram("fakecli")));
        }

        String program = windows ? "npm" : "sh";
        Optional<Path> present = ExecutableLocator.locate(program);
        if (present.isPresent()) {
            String resolved = ExecutableLocator.resolveProgram(program);
            System.out.println("      resolveProgram(" + program + ") -> " + resolved);
            if (windows) {
                check("裸名被替换为批处理外壳", resolved.toLowerCase().endsWith(".cmd")
                        || resolved.toLowerCase().endsWith(".bat")
                        || resolved.toLowerCase().equals(program + ".exe"));
            }
            String[] argv = windows
                    ? new String[]{program, "--version"}
                    : new String[]{program, "-c", "echo ok"};
            CmdResult result = CmdExecutors.execute(argv, 120, TimeUnit.SECONDS);
            String out = String.valueOf(result.getStdout()).trim();
            if (out.isEmpty()) {
                out = String.valueOf(result.getStderr()).trim();
            }
            System.out.println("      execute(" + java.util.Arrays.toString(argv) + ") exit="
                    + result.getExitCode()
                    + " err=" + (result.getThrowable() == null ? "-" : result.getThrowable().getMessage())
                    + " out=" + (out.length() > 40 ? out.substring(0, 40) : out));
            check("数组形式能用裸名启动 " + program, result.getExitCode() == 0);
        } else {
            System.out.println("      " + program + " 未安装，跳过启动校验");
        }

        delete(dir);
        System.out.println("[ExecutableLocatorShimTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 置为可执行。
     *
     * @param path 文件路径
     * @return 成功返回 true
     */
    private static boolean toExecutable(Path path) {
        try {
            path.toFile().setExecutable(true);
            return Files.isExecutable(path);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 清理临时目录。
     *
     * @param dir 目录
     */
    private static void delete(Path dir) {
        try {
            Files.deleteIfExists(dir.resolve("fakecli"));
            Files.deleteIfExists(dir.resolve("fakecli.cmd"));
            Files.deleteIfExists(dir);
        } catch (Exception ignored) {
            // 临时文件残留不影响结论
        }
    }

    /**
     * 断言。
     *
     * @param name 用例名
     * @param ok   是否通过
     */
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
