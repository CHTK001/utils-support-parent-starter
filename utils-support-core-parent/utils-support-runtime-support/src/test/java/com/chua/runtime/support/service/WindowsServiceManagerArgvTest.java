package com.chua.runtime.support.service;

import com.chua.common.support.lang.cmd.CmdResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows 服务管理器（runtime-support 副本）的参数数组化验证。
 *
 * <p>直接 {@code main()} 运行，不依赖 JUnit。</p>
 */
public class WindowsServiceManagerArgvTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        WindowsServiceManager manager = new WindowsServiceManager();

        CmdResult ok = manager.status("rpcss");
        check("真实服务查询成功", ok.isSuccess() && !ok.getStdout().isBlank());
        check("不存在的服务返回失败", !manager.status("qoder-no-such-service").isSuccess());

        Path marker = Paths.get(System.getProperty("java.io.tmpdir"), "qoder-support-sc-marker.txt");
        Files.deleteIfExists(marker);
        // 闭合并重开引号：拼接进 cmd.exe 的旧实现会在此落 marker，参数数组形式必须保持惰性
        String payload = "a\" & echo pwned > \"" + marker + "\" & \"";
        CmdResult injected = manager.status(payload);
        check("服务名中的 shell 载荷未被执行", !Files.exists(marker));
        check("载荷服务名以失败结束", !injected.isSuccess());
        Files.deleteIfExists(marker);

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failed++;
            System.out.println("[FAIL] " + name);
        }
    }
}
