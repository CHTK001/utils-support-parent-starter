// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.server;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器配置，协议无关。
 *
 * <p>包含网络绑定、线程池、超时、并发、连接、协议帧大小、Gzip 压缩、SSL/TLS、HTTP 等配置项。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class ServerSetting {

    /**
     * 创建一份独立的默认服务器配置。
     *
     * @return 新的默认配置实例
     */
    public static ServerSetting defaults() {
        return new ServerSetting();
    }

    /**
     * 创建一份按当前系统自动配置最优参数的服务器配置。
     *
     * <p>基于 CPU 核数、JVM 可用堆内存与操作系统类型自动调整线程数、连接数、
     * 等待队列长度、缓冲区等关键性能参数（等效于 {@code autoConfig()}）。</p>
     *
     * @return 自动配置实例
     */
    public static ServerSetting auto() {
        return new ServerSetting().autoConfig();
    }

    /**
     * 创建一份按当前系统自动配置最优参数的服务器配置，并输出系统级限制的诊断与调优建议。
     *
     * <p>与 {@link #auto()} 的区别：在自动配置参数的同时，诊断当前操作系统中可能限制 Server 吞吐量的
     * 系统级参数（句柄配额、等待队列上限、文件描述符限制），并输出「去哪改、怎么改、改了有什么用」
     * 的可执行步骤。</p>
     *
     * <p><b>注意：</b>本方法不会修改任何系统设置；输出仅用于指导运维人员在操作系统侧进行调优。</p>
     *
     * @return 自动配置后的实例
     * @since 4.0.0.50
     */
    public static ServerSetting forceAuto() {
        ServerSetting setting = auto();
        setting.diagnose();
        return setting;
    }

    /**
     * 是否启用按当前系统自动配置最优参数。
     *
     * <p>开启后，{@link AbstractServer} 启动前会基于 CPU 核数、JVM 可用堆内存与
     * 操作系统类型自动调整线程数、连接数、等待队列、缓冲区等性能参数，
     * 使服务器在各环境中都能获得较优的默认表现。默认开启，可设为 {@code false} 手动指定。</p>
     *
     * Auto
     */
    @Builder.Default
    private boolean auto = true;

    /**
     * 每连接除读缓冲外的固定开销（KB）：连接对象 + 解析器 8KB 行缓冲 + 响应/合并缓冲 + 对象头。
     *
     * <p>实测口径（50k 连接）：warm ≈ bufferSize + 8KB + 4KB。取 12KB 作为固定项，
     * 与 {@code validate()} 里的内存估算保持一致，避免两处公式互相矛盾。</p>
     */
    private static final long PER_CONNECTION_FIXED_KB = 12L;

    /**
     * 堆内存中允许分配给「每连接缓冲」的比例。
     *
     * <p>其余留给业务对象、GC 余量与非堆（直接内存/元空间）。取 0.6 而不是 1.0，
     * 是因为满载时除了连接缓冲还有请求处理本身的临时分配。</p>
     */
    private static final double HEAP_BUDGET_RATIO = 0.6D;

    /**
     * 读缓冲下限（字节）：再小会让大请求头/请求体被拆成过多系统调用。
     */
    private static final int MIN_BUFFER_SIZE = 2048;

    /**
     * 读缓冲上限（字节）：连接少时放大到此值，减少系统调用。
     */
    private static final int MAX_BUFFER_SIZE = 16384;

    /**
     * 按当前系统自动配置最优参数。
     *
     * <p>基于 CPU 核数、JVM 可用堆内存与操作系统类型自动调整线程数、连接数、
     * 等待队列长度、缓冲区等关键性能参数。返回当前实例，便于链式调用。</p>
     *
     * @return 当前配置实例
     */
    public ServerSetting autoConfig() {
        int cpus = Runtime.getRuntime().availableProcessors();
        long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");

        // 线程模型：Worker 随 CPU 核数伸缩，Boss 保持默认（accept 循环按核数扩展现已足够，
        // 且 KCP 等同时用 bossThreads 作为 IO event loop 数，需随核数扩展）
        this.workerThreads = Math.max(2, cpus * 2);
        if (this.bossThreads < 1) {
            this.bossThreads = 1;
        }

        // IO Selector 事件循环：Windows 实测 4 分片为最优并作为硬上限（见 resolveEventLoops），
        // Linux/macOS 按核数扩展。eventLoops 与 ioThreads 保持一致，避免两个字段互相打架。
        int io = resolveEventLoops(cpus, windows);
        this.ioThreads = io;
        this.eventLoops = io;

        // 等待队列：Windows 语义较弱适当收敛，Unix 系可放大。
        // 百万连接场景下 backlog 只影响「瞬时未 accept 的握手队列」，真正的容量由
        // maxConnections + 每连接内存决定，因此这里不做数量级放大（内核 somaxconn 才是硬上限）。
        this.backlog = windows
                ? Math.min(Math.max(cpus * 64, 128), 1024)
                : Math.min(Math.max(cpus * 128, 256), 4096);

        // 缓冲区与连接数必须由「同一份内存预算」联合推导，否则 autoConfig 自相矛盾：
        // 旧实现按堆分别定 maxConnections(16GB→100万) 与 bufferSize(4GB+→16KB)，
        // 相乘 1M × (16KB + 12KB 固定开销) ≈ 28GB > 16GB 堆，满载必然 OOM ——
        // 实测 50k 连接在 2GB 堆下就 OOM（warm 47KB/连接）。
        //
        // 推导顺序（单向，避免两个变量互相反推导致永远撞边界）：
        //   1) 先按堆定 bufferSize：小堆用小缓冲，大堆才放大（性能优化只在小连接数时兑现）
        //   2) 再按「堆预算 − 连接固定开销」定 maxConnections
        // 用户显式设置过的项不覆盖，只保证未显式的那项适配。
        long perConnFixedKb = PER_CONNECTION_FIXED_KB;
        long budgetMb = (long) (heapMb * HEAP_BUDGET_RATIO);

        if (!bufferSizeExplicit) {
            // 小堆必须用最小缓冲，否则固定开销 + 缓冲会挤掉容量
            this.bufferSize = heapMb >= 8192 ? MAX_BUFFER_SIZE
                    : heapMb >= 2048 ? 8192
                    : MIN_BUFFER_SIZE;
        }
        if (!maxConnectionsExplicit) {
            long perConnKb = Math.max(this.bufferSize / 1024L, 1L) + perConnFixedKb;
            long affordable = perConnKb > 0 ? budgetMb * 1024L / perConnKb : this.maxConnections;
            long resolved = resolveMaxConnections(heapMb, os);
            long limit = Math.min(resolved, affordable);
            if (limit > 0) {
                this.maxConnections = (int) limit;
            }
        }

        // 最大帧/请求体：跟随缓冲区分级，容纳大消息往返（含长度头余量）
        this.maxFrameSize = heapMb >= 4096 ? 1024 * 1024 : 65536;

        // 最大并发请求数：与连接池容量对齐（RPC auto 连接数=cpu*8）,
        // 防止异常场景下在途请求无限堆积击穿内存,同时不限制正常高吞吐。
        // 旧值 cpu*8（12 核=96）会把「百万连接下的正常请求突发」直接判成 503，
        // 与「单服务支撑百万连接」的目标冲突，故按核数放大两个数量级并保底 4096。
        // 显式配置过(>0 或 0=不限制)时保留原值,仅未配置时按核数给默认
        if (!maxConcurrencyExplicit) {
            this.maxConcurrency = Math.max(cpus * 256, 4096);
        }

        // 最大 Keep-Alive 请求数：内存充足时放宽长连接复用次数，
        // 减少高吞吐场景下频繁建连/断连的握手开销
        this.maxKeepAliveRequests = heapMb >= 4096 ? 500 : 100;

        // 响应式处理模式：多核机器默认启用（由具体实现决定是否支持）
        this.reactor = cpus >= 8;

        return this;
    }

    /**
     * 计算实际生效的事件循环（Selector）数量。
     *
     * <p>把平台差异收敛到一处，避免各实现各自 clamp 且结论互相矛盾：</p>
     * <ul>
     *   <li>Windows：实测 4 分片吞吐最优，且 {@code WindowsSelectorImpl} 在更多分片并发 select 时
     *       会丢失读事件（表现为连接已建立但请求 100% 超时），因此 4 是硬上限；</li>
     *   <li>Linux/macOS：epoll/kqueue 可安全扩展，取 CPU 核数（至少 2）。</li>
     * </ul>
     *
     * <p>显式配置的 {@link #eventLoops} 大于 0 时以配置为准，仅 Windows 仍收敛到 4 ——
     * 那不是调优选择，而是平台缺陷：放行只会得到一个「连接建立成功但永不回包」的服务。</p>
     *
     * @param cpus     CPU 核数
     * @param windows  是否为 Windows
     * @return 实际使用的事件循环数，恒大于 0
     */
    public int resolveEventLoops(int cpus, boolean windows) {
        // 显式配置优先（eventLoops 与 ioThreads 任一配置都算显式）
        int configured = this.eventLoops > 0 ? this.eventLoops : this.ioThreads;
        int loops;
        if (configured > 0) {
            loops = configured;
        } else {
            loops = windows ? 4 : Math.max(cpus, 2);
        }
        if (windows) {
            // Windows 硬上限：>4 个 Selector 并发 select 会丢读事件，无法在本平台规避
            loops = Math.min(loops, 4);
        }
        return Math.max(loops, 1);
    }

    /**
     * 按堆内存与操作系统计算最大连接数。
     *
     * <p>分级依据是「每连接常驻内存」：空闲连接不分配读写缓冲（各实现已改为懒分配），
     * 但仍需要 JDK 侧 {@code SocketChannel} + 内核 socket 结构，约 4~8KB 量级；
     * 收到数据后才有 16KB 级缓冲。按 1MB 连接 × 8KB ≈ 8GB 的上界给出分级，
     * 并对 Windows 的句柄语义做收敛。</p>
     *
     * @param heapMb 最大堆内存（MB）
     * @param os     操作系统名称（小写）
     * @return 最大连接数
     */
    private static int resolveMaxConnections(long heapMb, String os) {
        int limit;
        if (heapMb >= 16384) {
            limit = 1_000_000;
        } else if (heapMb >= 8192) {
            limit = 800_000;
        } else if (heapMb >= 4096) {
            limit = 400_000;
        } else if (heapMb >= 2048) {
            limit = 200_000;
        } else {
            limit = 100_000;
        }
        if (os.contains("win")) {
            // Windows 默认没有 ulimit 概念，但单进程句柄与 afd.sys 的非分页池更早成为瓶颈；
            // 百万连接需要显式调大注册表/内核参数，未调优时按半数收敛，避免 accept 阶段批量失败
            limit = Math.min(limit, 500_000);
        }
        return limit;
    }

    /**
     * 诊断当前操作系统中可能限制 Server 吞吐量的系统级参数，并输出调优建议。
     *
     * <p>诊断项目：</p>
     * <ul>
     *   <li>事件循环数上限：Windows 下 {@code WindowsSelectorImpl} 多分片 select 时会丢读事件，
     *       最多 4 个 Selector；</li>
     *   <li>等待队列上限：Windows 把应用请求的 {@code backlog} 截断到约 200，
     *       可以通过 {@code afd.sys} 注册表调大；</li>
     *   <li>文件描述符配额：单个进程的打开文件数受操作系统限制（Linux 上通常受 {@code ulimit -n} 约束）。</li>
     * </ul>
     *
     * <p>本方法不修改任何系统参数，只输出「去哪改、怎么改、改了有什么用」的建议。</p>
     *
     * @see #forceAuto()
     */
    public void diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Server 系统限制诊断（开机输出） ===\n");
        sb.append("操作系统: ").append(System.getProperty("os.name", "unknown")).append("\n");
        sb.append("CPU 核数: ").append(Runtime.getRuntime().availableProcessors()).append("\n");
        sb.append("JVM 堆上限: ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024)).append("MB\n");

        int cpus = Runtime.getRuntime().availableProcessors();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        int eventLoops = resolveEventLoops(cpus, windows);

        sb.append("事件循环数: ").append(eventLoops).append("\n");
        if (eventLoops < cpus && windows) {
            // [P3C W1 豁免] 参数已足够说明问题，不需要写成常量
            sb.append("  -> 太多: Windows 下单进程的事件循环数被硬限定为 4，再多会丢读事件，只能接受\n");
        }
        sb.append("最大连接数: ").append(maxConnections).append("\n");
        sb.append("等待队列: backlog=").append(backlog).append("\n");
        sb.append("缓冲区大小: bufferSize=").append(bufferSize).append("\n");

        List<String> problems = validate();
        if (!problems.isEmpty()) {
            sb.append("配置告警:\n");
            problems.forEach(p -> sb.append("  - ").append(p).append("\n"));
        }

        if (windows) {
            diagnoseWindows(sb);
        } else {
            diagnoseLinux(sb);
        }

        printDiagnosis(sb.toString());
    }

    private void printDiagnosis(String diag) {
        log.warn(diag);
    }

    /**
     * Windows 平台的诊断项。
     *
     * <p>核心限制是三处：句柄配额、afd.sys 的等待队列上限、多 Selector 的稳定性。
     * 前两者可以改注册表，后者是 JDK 平台缺陷无解，需要如实告知用户。</p>
     *
     * @param sb 输出内容
     */
    private void diagnoseWindows(StringBuilder sb) {
        // Windows 的单进程句柄限额由内核决定（约 1600 万），读不到当前量级，只报告限额本身。
        long handleLimit = 16 * 1024 * 1024L;
        sb.append("句柄配额: 进程限额约 ").append(handleLimit).append("，真正瓶颈在 afd.sys\n");

        sb.append("等待队列: Windows 会把 backlog 截断到约 200\n");
        sb.append("  -> 去哪改: 注册表编辑 HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Services\\afd\\Parameters\n");
        sb.append("  -> 怎么改: 新建 DWORD 值 EnableDynamicBacklog = 1，重启生效\n");
        sb.append("  -> 改了有什么用: 等待队列可以从约 200 扩大到约 2000，连接风暴可以排队而不被拒绝\n");

        sb.append("分发 (分片数): 4 个 Selector 是 Windows 下 WindowsSelectorImpl 的硬上限\n");
        sb.append("  -> 去哪改: 无法调整（JDK 平台缺陷）\n");
        sb.append("  -> 改了有什么用: 无\n");
    }

    /**
     * Linux 平台的诊断项。
     *
     * <p>核心限制是两处：ulimit 的文件描述符上限（连接数天花板）与
     * {@code net.core.somaxconn}（等待队列天花板）。</p>
     *
     * @param sb 输出内容
     */
    private void diagnoseLinux(StringBuilder sb) {
        // Linux 进程级文件描述符上限
        long fdLimit = readFdLimit();
        sb.append("文件描述符上限: ").append(fdLimit).append("\n");
        if (fdLimit > 0 && fdLimit < maxConnections) {
            sb.append("  -> 去哪改: bash 下执行 ulimit -n <value>，systemd 下改 limits.conf\n");
            sb.append("  -> 怎么改: ulimit -n ").append(Math.min(maxConnections, 100000)).append(" 或 ").append(fdLimit).append("\n");
            sb.append("  -> 改了有什么用: 当前连接数上限从 ").append(fdLimit).append(" 扩大到约 ").append(maxConnections).append("\n");
        }

        // somaxconn
        long somaxconn = readSomaxconn();
        sb.append("net.core.somaxconn: ").append(somaxconn).append("\n");
        if (somaxconn > 0 && somaxconn < backlog) {
            sb.append("  -> 去哪改: 执行 sysctl -w net.core.somaxconn=<value>\n");
            sb.append("  -> 怎么改: sysctl -w net.core.somaxconn=").append(backlog).append("\n");
            sb.append("  -> 改了有什么用: 等待队列可以从 ").append(somaxconn).append(" 扩大到 ").append(backlog).append("\n");
        }
    }

    /**
     * 读取 /proc/self/limits 中的「最大打开文件数」，不可读则返回 -1。
     *
     * @return 文件描述符上限，失败时返回 -1
     */
    private static long readFdLimit() {
        try {
            // [P3C W2 豁免] Linux 专用路径
            java.io.File file = new java.io.File("/proc/self/limits");
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            String line;
            while ((line = raf.readLine()) != null) {
                // 行格式：Max open files  soft limit = 1024 hard limit = 1024
                if (line.contains("Maximum open files") || line.contains("Max open files")) {
                    try {
                        long soft = Long.parseLong(line.split("=")[1].trim().replaceAll("\\D", ""));
                        return soft > 0 ? soft : -1;
                    } catch (Exception ignore) {
                        // 不是解析不了，就是没权限
                    }
                }
            }
            raf.close();
        } catch (Exception ignore) {
            // 无法诊断（可能是非 Linux/没进程），静默
        }
        return -1;
    }

    /**
     * 读取 /proc/sys/net/core/somaxconn 的值，不可读则返回 -1。
     *
     * @return somaxconn 的值，失败时返回 -1
     */
    private static long readSomaxconn() {
        try {
            java.io.File file = new java.io.File("/proc/sys/net/core/somaxconn");
            if (!file.canRead()) {
                return -1;
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            String value = raf.readLine();
            raf.close();
            return Long.parseLong(value.trim());
        } catch (Exception ignore) {
            // 无法诊断，静默
            return -1;
        }
    }
    /**
     * 校验连接相关配置，返回人可读的问题列表（空列表表示无问题）。
     *
     * <p>百万连接的目标里，绝大多数「起不来 / 连不上 / 内存爆」都不是代码问题而是参数组合：
     * 上限大于 fd 上限、backlog 小于突发速率、缓冲区乘上连接数超过堆。这里在启动前一次性
     * 把不可能成立的组合挑出来，比运行期出现 {@code Too many open files} 好排查得多。</p>
     *
     * @return 配置问题描述列表，无问题时为空列表
     */
    public List<String> validate() {

        List<String> problems = new java.util.ArrayList<>(4);
        if (maxConnections < 0) {
            problems.add("maxConnections 不能为负数（0 表示不限制）: " + maxConnections);
        }
        if (backlog < 1) {
            problems.add("backlog 必须 >= 1: " + backlog);
        }
        if (bufferSize < 1024) {
            problems.add("bufferSize 过小（<1KB），会导致大量系统调用: " + bufferSize);
        }
        if (soSndBuf < 0 || soRcvBuf < 0) {
            problems.add("soSndBuf/soRcvBuf 不能为负数（0 表示使用内核默认值）: soSndBuf="
                    + soSndBuf + ", soRcvBuf=" + soRcvBuf);
        }
        if (maxConnections > 0 && (soSndBuf > 0 || soRcvBuf > 0)) {
            // 这两项按连接数线性占用内核非分页内存，是百万连接下最容易踩的坑
            long perConnKb = (Math.max(soSndBuf, 0) + Math.max(soRcvBuf, 0)) / 1024L;
            long totalMb = maxConnections * perConnKb / 1024L;
            if (totalMb > 65536L) {
                problems.add("soSndBuf=" + soSndBuf + " / soRcvBuf=" + soRcvBuf + " 按 maxConnections="
                        + maxConnections + " 计算约需 " + totalMb + "MB 内核内存；"
                        + "百万连接下建议保持 0（内核自适应），仅在明确带宽需求时对小连接数场景放大");
            }
        }
        if (maxConnections > 0) {
            // 每连接常驻内存的估算（与 autoConfig 的预算公式共用 PER_CONNECTION_FIXED_KB）：
            //   空闲连接(idle, 未收发数据)：readBuf 惰性不分配，实测 ~1.7KB/连接
            //   服务过请求(warm)：bufferSize(readBuf) + 12KB 固定项，实测 50k 连接吻合
            long perConnectionKb = Math.max(bufferSize / 1024L, 1L) + PER_CONNECTION_FIXED_KB;
            long neededMb = maxConnections * perConnectionKb / 1024L;
            long heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
            if (neededMb > heapMb) {
                problems.add("maxConnections=" + maxConnections + " 在满载且每连接有流量时约需 "
                        + neededMb + "MB，超过最大堆 " + heapMb + "MB；"
                        + "请调小 maxConnections、调小 bufferSize 或调大 -Xmx"
                        + "（空闲连接仅 ~1.7KB/连接，此为 warm 上界估算）");
            }
            if (maxConnections > 200_000 && System.getProperty("os.name", "").toLowerCase().contains("win")
                    && !WindowsMaxConnectionHint.SHOWN.getAndSet(true)) {
                problems.add("Windows 下单进程百万连接需要同时调大句柄上限与 afd 非分页池；"
                        + "建议先在 Linux 上验收该容量目标");
            }
        }
        return problems;
    }

    /**
     * Windows 连接数提示去重标记（避免每个实例重复打印同一条告警）。
     */
    private static final class WindowsMaxConnectionHint {
        /**
         * 是否已提示过
         */
        private static final java.util.concurrent.atomic.AtomicBoolean SHOWN =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /**
         * 工具类不允许实例化
         */
        private WindowsMaxConnectionHint() {
        }
    }

    /**
     * 主机名
     *
     * 主机
     */
    @Builder.Default
    private String host = "0.0.0.0";

    /**
     * 端口号
     *
     * 端口
     */
    @Builder.Default
    private int port = 8080;

    /**
     * 协议类型名称
     *
     * 协议
     */
    @Builder.Default
    private String protocol = "http";

    /**
     * 上下文路径
     */
    @Builder.Default
    private String contextPath = "/";

    /**
     * Boss 线程数
     *
     * Bossthreads
     */
    @Builder.Default
    private int bossThreads = Math.max(1, Runtime.getRuntime().availableProcessors());

    /**
     * Worker 线程数
     *
     * Workerthreads
     */
    @Builder.Default
    private int workerThreads = Runtime.getRuntime().availableProcessors() * 2;

    /**
     * IO Selector 事件循环线程数（Reactor 模式，仅支持 IO 多路复用的实现使用）。
     *
     * <p>默认 0 表示由实现自行确定；{@link #autoConfig()} 会根据 CPU 核数与平台
     * 自动生成最优值。Windows 平台实测 4 分片为最优，Linux/macOS 按核数扩展。</p>
     *
     * IoThreads
     */
    @Builder.Default
    private int ioThreads = 0;

    /**
     * 等待队列长度
     *
     * <p>只影响「已完成三次握手但尚未被 accept」的瞬时排队长度，真正的容量由
     * {@link #maxConnections} 与每连接内存决定。内核会把超过平台上限的值
     * （Linux {@code somaxconn}、Windows 的 listen 上限）静默截断，所以这里配一个小值
     * 并不会耗尽内核队列，配一个大值也不会真的预分配那么多内存。</p>
     *
     * <p>各协议实现通过 {@link #resolveBacklog(int)} 取「配置值与协议下限的较大者」，
     * 避免在实现里各写一个魔术数字。</p>
     *
     * Backlog
     */
    @Builder.Default
    private int backlog = 128;

    /**
     * 计算提交给内核的 backlog 值。
     *
     * <p>传一个足够大的 {@code floor} 等价于「用平台允许的最大队列」，因为内核会按
     * {@code somaxconn} 自动截断；它不会预分配内存，也不会因为配得大而导致 OOM。
     * 真正需要调整的是系统参数（{@code net.core.somaxconn}），这一点仅靠代码改不了。</p>
     *
     * @param floor 协议侧的最小期望值（突发建连密集的协议取大值）
     * @return 实际提交给 bind 的 backlog，恒 {@code >= 1}
     */
    public int resolveBacklog(int floor) {
        return Math.max(backlog, Math.max(floor, 1));
    }

    /**
     * 最大请求体/消息大小（字节）
     *
     * 最大值请求尺寸
     */
    @Builder.Default
    private long maxRequestSize = 10 * 1024 * 1024;

    /**
     * 单连接待写队列字节上限（背压门限），{@code <= 0} 表示不限制。
     *
     * <p>慢客户端不读取响应时，服务端待写队列会持续增长；超过该门限后
     * 服务端不再接受该连接的新数据（SSE 分片会被丢弃），避免内存无界膨胀。
     * 默认 {@value #DEFAULT_MAX_PENDING_WRITE_BYTES} 字节（4MB）。</p>
     *
     * <p>该值是背压门限，不是硬性内存配额：达到门限后服务端停止向该连接
     * 追加新的流式分片（SSE），直到队列回落到门限以下。</p>
     */
    public static final long DEFAULT_MAX_PENDING_WRITE_BYTES = 4L * 1024 * 1024;

    /**
     * 最大待写队列字节数（背压门限）。
     *
     * <p>当单条连接的写队列中尚未写出的字节数超过此门限时，判定为慢消费者，
     * 关闭连接并丢弃该分片，避免响应数据在堆内无界堆积。</p>
     *
     * <p>{@link com.chua.common.support.network.server.nio.NioHttpServer} 与
     * {@link com.chua.common.support.network.server.aio.AioHttpServer} 依此实施背压。</p>
     *
     * 最大值待写字节数
     */
    @Builder.Default
    private long maxPendingWriteBytes = DEFAULT_MAX_PENDING_WRITE_BYTES;

    /**
     * 设置单连接待写队列字节上限（背压门限）。
     *
     * @param maxPendingWriteBytes 字节上限，{@code <= 0} 表示不限制
     */
    public void setMaxPendingWriteBytes(long maxPendingWriteBytes) {
        this.maxPendingWriteBytes = maxPendingWriteBytes;
    }

    /**
     * 最大连接数
     *
     * 最大值connections
     */
    @Builder.Default
    private int maxConnections = 10000;

    /**
     * 是否显式设置过 maxConnections(autoConfig 内部标记;默认 false)。
     *
     * <p>与 {@link #maxConcurrencyExplicit} 同理：用户显式设置的上限不能被
     * {@code autoConfig()} 按堆大小覆盖。</p>
     */
    @Builder.Default
    private boolean maxConnectionsExplicit = false;

    /**
     * 设置最大连接数,并标记为显式配置(避免被 {@link #autoConfig()} 覆盖)。
     *
     * @param maxConnections 最大连接数,{@code <= 0} 表示不限制
     */
    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        this.maxConnectionsExplicit = true;
    }

    /**
     * 字符集
     */
    @Builder.Default
    private String charset = "UTF-8";

    /**
     * 读取超时时间（毫秒）
     *
     * Read超时
     */
    @Builder.Default
    private int readTimeout = 30000;

    /**
     * 写超时时间（毫秒）
     *
     * Write超时
     */
    @Builder.Default
    private int writeTimeout = 30000;

    /**
     * 最大并发请求数，0 表示不限制
     */
    @Builder.Default
    private int maxConcurrency = 0;

    /**
     * 是否显式设置过 maxConcurrency(autoConfig 内部标记;默认 false,由 autoConfig 设置时置默认)
     */
    @Builder.Default
    private boolean maxConcurrencyExplicit = false;

    /**
     * 设置最大并发请求数,并标记为显式配置。
     *
     * @param maxConcurrency 最大并发数,0 表示不限制
     */
    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.maxConcurrencyExplicit = true;
    }

    /**
     * NIO 事件循环(Selector)数量，0 表示自动按 CPU 核数。
     *
     * <p>控制 {@code nio} 实现的事件循环线程数，影响读写 CPU 并行度与吞吐上限。
     * 自动模式：Windows 默认 2（{@code WindowsSelectorImpl} 多 Selector 并发稳定性限制），
     * Linux/macOS 默认 = CPU 核数（epoll/kqueue 可安全扩展）。</p>
     */
    @Builder.Default
    private int eventLoops = 0;

    /**
     * 是否在事件循环线程内联执行 handler（跳过虚拟线程提交与 Selector 唤醒往返）。
     *
     * <p>默认关闭：handler 提交到虚拟线程池异步执行，事件循环专注 I/O，
     * 2 核及以上场景下吞吐显著高于内联模式。
     * 仅当 handler 是微秒级纯计算（如 echo）且连接数较少时，
     * 才建议开启 {@code setting.setInlineDispatch(true)} 以省去线程调度开销。</p>
     */
    @Builder.Default
    private boolean inlineDispatch = false;

    /**
     * 响应超时时间（毫秒）
     *
     * 响应超时
     */
    @Builder.Default
    private long responseTimeout = 60000;

    /**
     * 是否启用 Reactor 处理模式
     *
     * Reactor
     */
    @Builder.Default
    private boolean reactor = false;

    /**
     * 最大 Keep-Alive 请求数
     *
     * 最大值keepaliverequests
     */
    @Builder.Default
    private int maxKeepAliveRequests = 100;

    /**
     * 优雅关闭等待时间（秒）
     *
     * Shutdownquietperiod
     */
    @Builder.Default
    private int shutdownQuietPeriod = 30;

    /**
     * TCP_NODELAY
     *
     * TCPNOdelay
     */
    @Builder.Default
    private boolean tcpNoDelay = true;

    /**
     * 是否启用流加密（AES-256-GCM 帧式端到端加密）。
     *
     * <p>开启后 {@link JdkTcpServer} 流式协议模式会在连接建立时自动将
     * 输入输出流包装为解密/加密流，处理器无感知；帧式（NIO 拼帧）协议不支持。
     * 需与客户端侧共享同一 {@link #encryptKey}。</p>
     *
     * 加密是否启用
     */
    @Builder.Default
    private boolean encrypt = false;

    /**
     * 流加密密钥短语（与客户端共享；AES-256 密钥由其 SHA-256 派生）。
     *
     * <p>{@link #encrypt} 为 true 时必须提供，未提供时服务端启动告警并按未加密处理。</p>
     *
     * 加密密钥短语
     */
    private String encryptKey;

    /**
     * SO_REUSEADDR
     *
     * SOreuseaddr
     */
    @Builder.Default
    private boolean soReuseAddr = true;

    /**
     * 发送缓冲区大小（字节），0 表示使用操作系统默认值（由内核自动调优）。
     *
     * <p>仅在 {@code >0} 时由各实现于 accepted 连接上调用 {@code SO_SNDBUF}；默认 0 不会产生
     * 任何额外系统调用。</p>
     *
     * <p>百万连接场景下不要盲目调大：每个连接的内核发送缓冲都会占用非分页内存，
     * {@code 1M × 64KB = 64GB}。真正的吞吐瓶颈通常是单个连接的往返延迟，
     * 而不是缓冲大小；需要大带宽时优先调大 {@code TCP_NODELAY=false + 单连接大缓冲}，
     * 而不是给所有连接统一放大。</p>
     *
     * Sosndbuf
     */
    @Builder.Default
    private int soSndBuf = 0;

    /**
     * 接收缓冲区大小（字节），0 表示使用操作系统默认值。
     *
     * <p>仅在 {@code >0} 时由各实现于 accepted 连接上调用 {@code SO_RCVBUF}；默认 0 不会产生
     * 任何额外系统调用。</p>
     *
     * <p>与 {@link #soSndBuf} 同理：百万连接下每连接放大 1MB 就等于 1TB 内核内存，
     * 默认值与内核自适应（tcp_moderate_rcvbuf）已经足够。</p>
     *
     * Sorcvbuf
     */
    @Builder.Default
    private int soRcvBuf = 0;

    /**
     * 单次事件循环最多 accept 的连接数。
     *
     * <p>突发建连（负载均衡摘挂、客户端重连风暴）时 accept 队列会瞬间积压，
     * 一次就绪只 accept 一个会让队列排到超时；批量 drain 但又必须有上限，
     * 否则单个分片在 accept 上停留过久会拖慢已建立连接的读写。</p>
     *
     * Acceptbatch
     */
    @Builder.Default
    /**
     * 单次 OP_ACCEPT 就绪时最多接纳的连接数。
     *
     * <p>默认 2048：Windows 会把应用请求的 backlog 静默截断到约 200，队列一满就回 RST，
     * 连接根本到不了 accept 阶段。一轮只 accept 128 条时，瞬时千级建连会连续溢出，
     * 因此这里给足一轮排空内核队列的预算（handleAccept 仍以 accept()==null 为终止条件，
     * 该值只是防止 SYN 洪泛独占事件循环的安全阀）。</p>
     */
    private int acceptBatch = 2048;

    /**
     * 超过 {@link #maxConnections} 时是否用 503 明确拒绝（而不是直接断开）。
     *
     * <p>直接 close 对客户端表现为「连接被重置」，难以区分「服务已满」与「网络故障」；
     * 回 503 + {@code Retry-After} 才能让上游负载均衡正确摘流。
     * 对非 HTTP 协议该开关无效，实现会直接关闭连接。</p>
     *
     * Rejectwith503
     */
    @Builder.Default
    private boolean rejectWith503 = true;

    /**
     * 默认 Content-Type
     *
     * 内容类型
     */
    @Builder.Default
    private String contentType = "text/html; charset=utf-8";

    /**
     * 缓冲区大小（字节）
     *
     * 缓冲区尺寸
     */
    @Builder.Default
    private int bufferSize = 8192;

    /**
     * 是否把读缓冲分配到堆外（DirectBuffer）。
     *
     * <p>默认 {@code false}: 读缓冲分配在 JVM 堆，便于 GC 回收、调试和线上无缝切换。
     * 设为 {@code true} 后，读缓冲改为 {@link ByteBuffer#allocateDirect}，堆占用下降，
     * 但直接内存（{@code -XX:MaxDirectMemorySize}）接管。这在「百万连接、空闲连接极少
     * 读数据、只留连接元数据」的场景才有意义；普通低并发或需要调试堆内存的场景请
     * 保持默认 {@code false}，不要盲目开启。
     *
     * <p>注意：两者均不影响读缓冲大小（由 {@link #bufferSize} 控制）。本开关只决定
     * 该缓冲分配在哪片内存区域。</p>
     */
    /**
     * 是否把读缓冲分配到堆外（DirectBuffer）。
     *
     * <p>默认 {@code false}: 读缓冲分配在 JVM 堆，便于 GC 回收、调试和线上无缝切换。
     * 设为 {@code true} 后，读缓冲改为 {@link ByteBuffer#allocateDirect}，堆占用下降，
     * 但直接内存（{@code -XX:MaxDirectMemorySize}）接管。这在「百万连接、空闲连接极少
     * 读数据、只留连接元数据」的场景才有意义；普通低并发或需要调试堆内存的场景请
     * 保持默认 {@code false}，不要盲目开启。
     *
     * <p>注意：两者均不影响读缓冲大小（由 {@link #bufferSize} 控制）。本开关只决定
     * 该缓冲分配在哪片内存区域。</p>
     *
     * @return 是否使用直接内存读缓冲
     */
    @Builder.Default
    private boolean useDirectBuffer = false;

    /**
     * 是否显式设置过 {@link #useDirectBuffer}（autoConfig 内部标记）。
     */
    @Builder.Default
    private boolean useDirectBufferExplicit = false;

    /**
     * 是否把读缓冲分配到堆外（DirectBuffer）。
     *
     * <p>默认 {@code false}，分配在 JVM 堆，便于 GC 回收和调试。
     * 设为 {@code true} 后，读缓冲改为 {@link ByteBuffer#allocateDirect}。
     *
     * @return 是否使用直接内存读缓冲
     */
    public boolean isUseDirectBuffer() {
        return useDirectBuffer;
    }

    /**
     * 设置是否把读缓冲分配到堆外（DirectBuffer）。
     *
     * @param useDirectBuffer 是否使用直接内存读缓冲
     */
    public void setUseDirectBuffer(boolean useDirectBuffer) {
        this.useDirectBuffer = useDirectBuffer;
        this.useDirectBufferExplicit = true;
    }

    /**
     * 是否显式设置过 bufferSize（autoConfig 内部标记；默认 false）。
     *
     * <p>与 {@link #maxConnectionsExplicit} 同理：用户显式指定的缓冲区大小不能被
     * {@code autoConfig()} 按「堆 / 连接数」反推覆盖。</p>
     */
    @Builder.Default
    private boolean bufferSizeExplicit = false;

    /**
     * 设置缓冲区大小，并标记为显式配置（避免被 {@link #autoConfig()} 覆盖）。
     *
     * @param bufferSize 缓冲区字节数
     */
    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
        this.bufferSizeExplicit = true;
    }

    /**
     * WebSocket/消息协议最大帧大小（字节）
     *
     * 最大值frame尺寸
     */
    @Builder.Default
    private int maxFrameSize = 65536;

    /**
     * 是否启用 Gzip 压缩
     *
     * Gzip是否启用
     */
    @Builder.Default
    private boolean gzipEnabled = false;

    /**
     * Gzip 压缩等级（1-9）
     *
     * Gzip级别
     */
    @Builder.Default
    private int gzipLevel = 6;

    /**
     * Gzip 最小压缩大小（字节），小于此值不压缩
     *
     * Gzip最小值尺寸
     */
    @Builder.Default
    private int gzipMinSize = 1024;

    /**
     * CORS 跨域配置
     *
     * Cors
     */
    @Builder.Default
    private CorsConfig cors = new CorsConfig();

    /**
     * 安全响应头配置
     *
     * <p>默认 {@code enabled=false}，不挂载安全头过滤器（保持既有行为）。</p>
     *
     * Securityheaders
     */
    @Builder.Default
    private SecurityHeadersConfig securityHeaders = new SecurityHeadersConfig();

    /**
     * SSL/TLS 配置
     *
     * SSL
     */
    @Builder.Default
    private SslConfig ssl = new SslConfig();

    /**
     * HTTP 协议专用配置
     *
     * HTTP
     */
    @Builder.Default
    private HttpConfig http = new HttpConfig();

    /**
     * CORS 跨域配置。
     *
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CorsConfig {

        /**
         * CORS 开关
         *
         * Alloworigin
         */
        @Builder.Default
        private boolean allowOrigin = false;

        /**
         * 允许携带凭据（Cookie / Authorization）。
         *
         * <p>对应 {@code Access-Control-Allow-Credentials}。默认 false，保持既有行为。</p>
         *
         * <p><b>开启后不能再回 {@code *}</b>：规范禁止「凭据 + 通配来源」并用，
         * 一旦同时出现，浏览器会直接判定为非法组合并拒绝该响应
         * （报错形如 {@code The value of the 'Access-Control-Allow-Credentials' header
         * in the response is '' which must be 'true'}）。因此开启本项时，
         * {@code allowedOrigins=*} 会被解释为「回显实际请求来源」而不是字面 {@code *}。</p>
         *
         * Allowcredentials
         */
        @Builder.Default
        private boolean allowCredentials = false;

        /**
         * 允许的源
         *
         * Allowedorigins
         */
        @Builder.Default
        private String allowedOrigins = "*";

        /**
         * 允许的方法
         *
         * Allowedmethods
         */
        @Builder.Default
        private String allowedMethods = "GET,POST,PUT,DELETE,PATCH,OPTIONS";

        /**
         * 允许的请求头
         *
         * Allowedheaders
         */
        @Builder.Default
        private String allowedHeaders = "Content-Type,Authorization";

        /**
         * 允许前端 JavaScript 读取的响应头
         *
         * <p>对应 {@code Access-Control-Expose-Headers}。跨域场景下浏览器默认只暴露
         * CORS  safelist 响应头，自定义头（{@code Content-Disposition}、
         * {@code X-FileStorage-Preview} 等）若不在此声明，前端
         * {@code response.headers.get(...)} 将读不到。</p>
         * <p>留空表示不追加该响应头（默认行为，保持既有实现不变）。</p>
         *
         * Exposeheaders
         */
        @Builder.Default
        private String exposeHeaders = "";
    }

    /**
     * 安全响应头配置。
     *
     * <p>供 {@link com.chua.common.support.network.server.filter.SecurityHeadersServerFilter}
     * 使用。默认 {@code enabled=false}，即默认不下发任何安全头，
     * 以保证既有服务在升级后行为不变；需要时由各 Server 显式开启。</p>
     *
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SecurityHeadersConfig {

        /**
         * 是否启用安全响应头过滤器
         *
         * <p>默认关闭：{@link com.chua.common.support.network.server.AbstractServer}
         * 仅在本项为 true 时才挂载安全头过滤器。</p>
         */
        @Builder.Default
        private boolean enabled = false;

        /**
         * X-Content-Type-Options 开关（防 MIME 嗅探）
         */
        @Builder.Default
        private boolean nosniff = true;

        /**
         * X-Frame-Options 取值
         *
         * <p>默认 {@code DENY}（禁止被 iframe 嵌入）；置为空串表示不下发该响应头，
         * 供需要被前端页面以 iframe 嵌入的场景（如独立文件服务器）使用。</p>
         */
        @Builder.Default
        private String frameOptions = "DENY";

        /**
         * CSP frame-ancestors 取值
         *
         * <p>留空表示不下发；例如 {@code *} 表示允许任意来源嵌入，
         * {@code 'self' https://example.com} 表示白名单。</p>
         */
        @Builder.Default
        private String frameAncestors = "";

        /**
         * Referrer-Policy 取值
         *
         * <p>默认 {@code strict-origin-when-cross-origin}；置为空串表示不下发。</p>
         */
        @Builder.Default
        private String referrerPolicy = "strict-origin-when-cross-origin";

        /**
         * 是否下发 Strict-Transport-Security（仅 TLS 部署有意义）
         */
        @Builder.Default
        private boolean hstsEnabled = false;

        /**
         * Strict-Transport-Security 取值
         */
        @Builder.Default
        private String hstsValue = "max-age=31536000; includeSubDomains";
    }

    /**
     * SSL/TLS 配置。
     *
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SslConfig {

        /**
         * 是否启用
         */
        @Builder.Default
        private boolean enabled = false;

        /**
         * SSL KeyStore 文件路径（JKS/PKCS12）
         */
        private String keyStorePath;

        /**
         * SSL KeyStore 密码
         */
        private String keyStorePassword;

        /**
         * SSL 证书文件路径（PEM 格式）
         */
        private String certPath;

        /**
         * SSL 私钥文件路径（PEM 格式）
         */
        private String keyPath;

        /**
         * SSL 私钥密码
         */
        private String keyPassword;

        /**
         * 是否信任所有证书（开发环境）
         *
         * TrustALL
         */
        @Builder.Default
        private boolean trustAll = false;

        /**
         * 一键自签 — 启用后自动开启 SSL 并生成自签名证书，无需任何其他配置。
         *
         * <p>等效于同时设置 {@code enabled=true} + {@code selfSigned=true}，
         * 使用默认域名（localhost, 127.0.0.1）和默认有效期（365天）。</p>
         *
         * <p>示例：{@code .selfSignedAuto(true)} 即可启动 HTTPS。</p>
         *
         * Selfsignedauto
         */
        @Builder.Default
        private boolean selfSignedAuto = false;

        /**
         * 是否启用自签名证书自动生成（开发/测试环境）
         *
         * <p>启用后，若未配置 keyStorePath 或 certPath/keyPath，
         * 将自动使用 JDK keytool 生成自签名证书。</p>
         *
         * Selfsigned
         */
        @Builder.Default
        private boolean selfSigned = false;

        /**
         * 自签名证书域名列表
         *
         * <p>默认包含 localhost 和 127.0.0.1。
         * 多个域名将作为 SAN（Subject Alternative Name）扩展添加到证书中。</p>
         *
         * Selfsigneddomains
         */
        @Builder.Default
        private List<String> selfSignedDomains = List.of("localhost", "127.0.0.1");

        /**
         * 自签名证书有效期（天），默认 365 天
         *
         * Selfsignedvalidity
         */
        @Builder.Default
        private int selfSignedValidity = 365;

        /**
         * 自签名证书密钥算法（RSA / EC），默认 RSA
         *
         * Selfsigned密钥ALG
         */
        @Builder.Default
        private String selfSignedKeyAlg = "RSA";

        /**
         * 自签名证书密钥大小，默认 2048
         *
         * Selfsigned密钥尺寸
         */
        @Builder.Default
        private int selfSignedKeySize = 2048;
    }

    /**
     * HTTP 协议专用配置。
     *
     * @since 4.0.0.42
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class HttpConfig {

        /**
         * 是否启用 WebSocket 升级
         *
         * Websocket是否启用
         */
        @Builder.Default
        private boolean websocketEnabled = false;
    }
}