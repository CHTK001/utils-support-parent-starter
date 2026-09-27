package com.chua.common.support.service.impl;

/**
 * PID 进程追踪器验证：tasklist / taskkill 以参数数组下传后的真实语义。
 *
 * <p>直接 {@code main()} 运行，不依赖 JUnit。</p>
 */
public class PidFileProcessTrackerTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        PidFileProcessTracker tracker = new PidFileProcessTracker();
        long self = ProcessHandle.current().pid();

        check("自身进程判定为存活", tracker.isRunning(self));
        check("未占用的 PID 判定为不存在", !tracker.isRunning(999999));
        check("非法 PID 判定为不存在", !tracker.isRunning(0) && !tracker.isRunning(-1));

        Process child = isWindows()
                ? new ProcessBuilder("ping", "-n", "30", "127.0.0.1").redirectErrorStream(true).start()
                : new ProcessBuilder("sleep", "30").redirectErrorStream(true).start();
        long childPid = child.pid();
        check("子进程启动后判定为存活", tracker.isRunning(childPid));

        tracker.kill(childPid);
        child.waitFor();
        check("kill 之后子进程不再存活", !tracker.isRunning(childPid));

        tracker.kill(999999);
        check("kill 不存在的 PID 不抛异常", true);

        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
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
