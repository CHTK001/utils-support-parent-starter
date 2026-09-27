package com.chua.runtime.core.service;

import com.chua.common.support.lang.cmd.CmdResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Windows 服务管理器的参数数组化验证：服务名不得被 shell 解析。
 *
 * <p>直接 {@code main()} 运行，不依赖 JUnit。</p>
 */
public class WindowsServiceManagerArgvTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        WindowsServiceManager manager = new WindowsServiceManager();

        check("真实服务查询成功", queryOk(manager, "rpcss"));
        check("不存在的服务返回失败", !queryOk(manager, "qoder-no-such-service"));

        Path marker = Paths.get(System.getProperty("java.io.tmpdir"), "qoder-sc-marker.txt");
        Files.deleteIfExists(marker);
        // 闭合并重开引号：拼接进 cmd.exe 的旧实现会在此落 marker，参数数组形式必须保持惰性
        String payload = "a\" & echo pwned > \"" + marker + "\" & \"";
        CmdResult result = manager.status(payload);
        check("服务名中的 shell 载荷未被执行", !Files.exists(marker));
        check("畸形服务名以失败结束而非异常", result != null && !result.isSuccess());

        Files.deleteIfExists(marker);
        CmdResult quoted = manager.status("qoder\"quoted\"name");
        check("含引号的服务名不产生额外进程输出", quoted != null && !quoted.isSuccess());
        check("含引号的服务名未落 marker", !Files.exists(marker));

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean queryOk(WindowsServiceManager manager, String serviceName) {
        CmdResult result = manager.status(serviceName);
        return result.isSuccess() && !result.getStdout().isBlank();
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
