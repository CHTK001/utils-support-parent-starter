package com.chua.nmap.support;

import com.chua.nmap.support.NmapScanner.HostInfo;
import com.chua.nmap.support.NmapScanner.PortInfo;
import com.chua.nmap.support.NmapScanner.PortState;
import com.chua.nmap.support.bridge.RustNmapBridge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Nmap 扫描器结果口径冒烟门。
 *
 * <p>不触碰任何真实网络：只验证三类契约</p>
 * <ul>
 *   <li>Rust 侧未实现的路径（UDP 扫描、OS 识别）必须显式抛错，不得返回空结果冒充"扫描完成"</li>
 *   <li>native 未加载时必须抛错，不得返回 openPorts=0 的成功结果</li>
 *   <li>解析必须按 Rust 真实 JSON 契约：存活主机是字符串数组、缺 state 字段判 UNKNOWN、
 *       IPv4 枚举与点分十进制互转正确</li>
 * </ul>
 *
 * <p>运行方式：{@code java com.chua.nmap.support.NmapHonestySmokeTest}，
 * 任一校验失败输出 FAIL 并以退出码 1 结束。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class NmapHonestySmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        testUnimplementedPathsThrow();
        testNativeMissingThrows();
        testAliveHostListFormat();
        testEmptyHostListIsNotAFakeHost();
        testMissingStateIsNotOpen();
        testIpv4Helpers();

        System.out.println("========================================");
        System.out.println("NmapHonestySmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * Rust 侧未实现的能力必须显式拒绝
     */
    private static void testUnimplementedPathsThrow() {
        RustNmapScanner scanner = new RustNmapScanner();
        check(throwsUnsupported(() -> scanner.scanUdpPorts("192.0.2.1", new int[]{53})),
                "UDP 扫描显式抛 UnsupportedOperation（native 缺失时同样不得返回空结果）");
        check(throwsUnsupported(() -> scanner.detectOs("192.0.2.1")),
                "OS 识别显式抛 UnsupportedOperation");
    }

    /**
     * native 未加载时不得返回"扫描完成"
     */
    private static void testNativeMissingThrows() {
        if (RustNmapBridge.isLoaded()) {
            System.out.println("[SKIP] native 已加载，跳过未加载路径断言");
            return;
        }
        RustNmapScanner scanner = new RustNmapScanner();
        Throwable tcp = capture(() -> scanner.scanTcpPorts("192.0.2.1", new int[]{22, 80}));
        check(tcp != null, "native 缺失时 TCP 扫描抛错而非返回空结果（实际=" + describe(tcp) + "）");
        check(capture(() -> scanner.ping("192.0.2.1")) != null, "native 缺失时 ping 抛错");
        check(capture(() -> scanner.scanSubnet("192.0.2.0/30")) != null, "native 缺失时子网扫描抛错");
    }

    /**
     * 存活主机列表按 Rust 的 JSON 字符串数组解析
     */
    private static void testAliveHostListFormat() {
        List<?> hosts = invokeHostParser("[\"10.0.0.5\",\"10.0.0.6\"]");
        check(hosts != null && hosts.size() == 2, "两台存活主机解析为 2 条（实际=" + sizeOf(hosts) + "）");
        if (hosts != null && hosts.size() == 2) {
            check("10.0.0.5".equals(ipOf(hosts.get(0))), "首条 IP 为 10.0.0.5（实际=" + ipOf(hosts.get(0)) + "）");
            check("10.0.0.6".equals(ipOf(hosts.get(1))), "次条 IP 为 10.0.0.6（实际=" + ipOf(hosts.get(1)) + "）");
            String first = ipOf(hosts.get(0));
            check(first.indexOf('[') < 0 && first.indexOf('"') < 0,
                    "IP 未夹带方括号或引号（实际=" + first + "）");
        }
    }

    /**
     * 空数组不得产出一条 ip="[]" 的假主机
     */
    private static void testEmptyHostListIsNotAFakeHost() {
        List<?> hosts = invokeHostParser("[]");
        check(hosts != null && hosts.isEmpty(), "无存活主机时返回空列表（实际=" + sizeOf(hosts) + "）");
    }

    /**
     * 端口项缺少 state 字段时判 UNKNOWN，不默认 OPEN
     */
    private static void testMissingStateIsNotOpen() {
        try {
            Method m = RustNmapScanner.class.getDeclaredMethod("parsePortRangeResult", String.class, String.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<PortInfo> ports = (List<PortInfo>) m.invoke(new RustNmapScanner(),
                    "[{\"port\":22,\"service\":\"ssh\"}]", "TCP");
            check(ports.size() == 1, "端口项解析出 1 条（实际=" + ports.size() + "）");
            check(ports.get(0).getState() == PortState.UNKNOWN,
                    "缺 state 字段判 UNKNOWN（实际=" + ports.get(0).getState() + "）");
        } catch (Exception e) {
            check(false, "端口结果解析可调用: " + e);
        }
    }

    /**
     * IPv4 互转与边界校验
     */
    private static void testIpv4Helpers() {
        try {
            Method toLong = RustNmapScanner.class.getDeclaredMethod("ipv4ToLong", String.class);
            Method toIp = RustNmapScanner.class.getDeclaredMethod("longToIpv4", long.class);
            toLong.setAccessible(true);
            toIp.setAccessible(true);
            long value = (Long) toLong.invoke(null, "192.168.1.1");
            check(value == (192L << 24) + (168L << 16) + (1L << 8) + 1L, "192.168.1.1 数值正确（实际=" + value + "）");
            check("192.168.1.1".equals(toIp.invoke(null, value)), "反向互转一致（实际=" + toIp.invoke(null, value) + "）");
            check("255.255.255.255".equals(toIp.invoke(null, 4294967295L)),
                    "最大地址不退化为负数（实际=" + toIp.invoke(null, 4294967295L) + "）");
            check(toLong.invoke(null, "999.1.1.1") == null, "非法八位组被拒绝");
        } catch (InvocationTargetException e) {
            String message = e.getCause() == null ? "" : String.valueOf(e.getCause().getMessage());
            check(message.contains("999.1.1.1"), "非法八位组报错并带原值（实际=" + message + "）");
        } catch (Exception e) {
            check(false, "IPv4 辅助方法可调用: " + e);
        }
    }

    /**
     * 调用存活主机解析器，兼容新旧方法名
     *
     * @param json Rust 返回的 JSON 文本
     * @return 主机列表，不可用时返回 null
     */
    private static List<?> invokeHostParser(String json) {
        RustNmapScanner scanner = new RustNmapScanner();
        try {
            Method m = RustNmapScanner.class.getDeclaredMethod("parseAliveHostList", String.class, String.class);
            m.setAccessible(true);
            return (List<?>) m.invoke(scanner, json, "冒烟");
        } catch (NoSuchMethodException e) {
            try {
                Method old = RustNmapScanner.class.getDeclaredMethod("parseHostListResult", String.class);
                old.setAccessible(true);
                return (List<?>) old.invoke(scanner, json);
            } catch (Exception inner) {
                System.out.println("[FAIL] 无法调用存活主机解析器: " + inner);
                failureCount++;
                return null;
            }
        } catch (Exception e) {
            System.out.println("[FAIL] 存活主机解析抛错: " + e);
            failureCount++;
            return null;
        }
    }

    /**
     * 取主机 IP
     *
     * @param host 主机对象
     * @return IP 文本
     */
    private static String ipOf(Object host) {
        try {
            return String.valueOf(((HostInfo) host).getIp());
        } catch (Exception e) {
            return "<error:" + e.getClass().getSimpleName() + ">";
        }
    }

    /**
     * 取列表大小
     *
     * @param list 列表
     * @return 大小说明
     */
    private static String sizeOf(List<?> list) {
        return list == null ? "null" : String.valueOf(list.size());
    }

    /**
     * 是否抛出 UnsupportedOperationException
     *
     * @param action 动作
     * @return 是否符合预期
     */
    private static boolean throwsUnsupported(Runnable action) {
        Throwable t = capture(action);
        return t instanceof UnsupportedOperationException;
    }

    /**
     * 捕获异常
     *
     * @param action 动作
     * @return 异常，未抛出时为 null
     */
    private static Throwable capture(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * 异常描述
     *
     * @param t 异常
     * @return 描述
     */
    private static String describe(Throwable t) {
        return t == null ? "no-throw" : t.getClass().getSimpleName();
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
