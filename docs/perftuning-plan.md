# 优化路线图：让 Java Server 逼近 Go 的百万并发低延迟

**目标定位**：不是"写一个 Go 替换 Java"，而是基于当前 `common-starter` 的 `Server` SPI 体系，把单节点能力推到 Go 同等水平（10万~百万级并发连接、亚毫秒~低毫秒延迟）。

## 当前基线

- java 25.0.4.1 LTS (Temurin) — 虚拟线程、handlebara 容器、Preview (record patterns/switch/unlimit)
- `NioHttpServer`: Selector 分片 + 虚拟线程 worker + 内联 fast path
- `AioHttpServer`: Proactor/IOCP + 虚拟线程 worker
- `JdkHttpServer`: jdk HttpServer + 虚拟线程
- 没有测试网络 (没下载 junit-platform-launcher/surefire deps)，本机用自定义 JUnit 6.0.3 runner + 手动温度平衡测试

## 实测记录（Windows 11 / i5-12600KF / JDK 25.0.4.1）

压测代码在 `docs/perf/bench/`，服务端与压测端**分属两个 JVM**（同 JVM 内跑客户端会把机器打满、扭曲延迟）。
功能验证用 `fncheck.ps1`（8 个场景 × NIO/AIO 双协议），平台标定用 `rawcal.ps1`。

### 关键结论：这台机器上，度量吞吐量做不到可靠比较

后台常驻进程（GameViewer、Vivaldi、Steam++ 等）会吃掉 30%~45% CPU，导致同一份代码的
吞吐量在 15K~84K QPS 之间随机漂移，**波动幅度大于任何一项优化的收益**。因此：

- **吞吐量/QPS 只能做同一次运行内的背靠背 A/B，不能跨轮次比较。**
- **可靠的指标是「每请求消耗的服务端 CPU 纳秒」**（`ThreadMXBean.getThreadCpuTime` 累计 ÷ 请求数）。
  后台抢占只拉长 wall time、压低 QPS，不改变实际消耗的 CPU，所以该指标对噪声免疫。
  采样点必须在压测线程退出**之前**——线程退出后 `getThreadCpuTime` 返回 -1，统计会严重失真
  （裸 echo 一开始就踩了这个坑，算出 95ns/请求的荒谬值）。
- `HttpClientMain` 的直方图只有 5001 个 µs 桶，`p99≈4980us` 是截断伪影；`avg` 来自完整延迟求和。

### 平台下限标定：用户态已无剩余空间

闭环压测下每个连接同一时刻只有一个在途请求，所以「一次 read + 一次 write」就是 syscall 的最少次数。
用裸 Selector-NIO echo（`RawEchoCpuMain`，与 `NioHttpServer` 完全相同的 read/write 形状）做下限参照：

| 实现 | CPU ns/请求 |
|---|---|
| 裸 Selector-NIO echo（无 HTTP 解析、无响应构建） | 83,518 ~ 88,631 |
| `NioHttpServer`（完整 HTTP 解析 + 过滤器链 + 响应构建） | 71,780 ~ 72,338 |
| `AioHttpServer` | 71,886 ~ 72,935 |

**HTTP 服务端的每请求 CPU 已经低于裸 echo。** 说明剩余开销几乎全部是 Windows loopback 的内核
syscall 成本（约 30~40µs/次），用户态解析/构建/过滤器的成本在噪声之内。这也解释了为什么
`eventLoops` 从 2 调到 16、以及拆分多个压测 JVM 都没有带来吞吐提升——瓶颈不在并行度，而在
每次 syscall 的固定成本。

## 已应用的优化

### 1. 响应头与响应体合并写出（`NioHttpServer`）

原实现把 header 和 body 作为两个 `ByteBuffer` 依次 `write`，每请求两次 `SocketChannel.write`。
新实现 `appendResponseBuffers(st, header, body)` 在两者总长 ≤ `COMBINE_THRESHOLD`（默认 64KB）时
合并成一个堆缓冲一次写出，三个 `setAsyncWriter` 位置（内联路径、worker OP_WRITE 路径、直写路径）统一使用。

实测（同一次运行内背靠背 A/B，两次独立 pass）：

| 配置 | pass 1 CPU ns/请求 | pass 2 CPU ns/请求 |
|---|---|---|
| 合并（默认） | 72,276 | 72,121 |
| `-Dnio.combine=0` | 113,589 | 127,750 |

**每请求 CPU 降低约 1.6~1.8 倍**。开关保留为系统属性 `-Dnio.combine=<字节数>`（只在类初始化时读一次，
无运行时开销），便于在异常平台上回退；设为 `0` 即完全关闭。

### 2. 响应头 Map 惰性化（`NioServerResponse`）

原来每个响应对象在构造函数里就创建 `TreeMap` + `Wraps.sortedMap` 包装。实际绝大多数响应
（含所有 fast path 响应）只带系统自动生成的几个头，业务代码一次 `setHeader` 都不会调。
改为 `headers` 字段惰性创建，新增 `headers()` / `headersEmpty()` 两个内部助手，`reset` / `sendFile` /
`buildHttpHeaders` / `writeHeaders` 全部改为判空。

NIO 与 AIO 共用同一个 `NioServerResponse`（`AioHttpServer` 只 `import` 它），所以两个协议一并受益。

### 3. 过滤器在日志关闭时直接短路（`AccessLogFilter` / `TracingFilter`）

`TracingFilter` 每请求生成 `UUID.randomUUID()`（走 `SecureRandom`）、再做 `replace("-","").substring(0,16)`
三次字符串操作；`AccessLogFilter` 每请求拼 remoteAddr/method/path。而 SLF4J 未绑定实现时
`log.isInfoEnabled()` 恒为 false，这些全是纯浪费。两者改为 `if (!log.isInfoEnabled()) { chain.doFilter(...); return; }`。

该项在吞吐量上测不出差异（噪声远大于收益），但属于确定性的无用功消除，保留。

### 4. 压测客户端解析 bug（`HttpBench`）

`readFullResponse` 原来只检查缓冲区最后 4 个字节是否为 `\r\n\r\n`。keep-alive 下当
header 与 body 在同一次 read 中到达时，行尾标记不在缓冲区尾部，客户端会**永远阻塞**，
这是前期所有读数不可信的根本原因。改为 `findHeaderEnd` / `parseContentLength` 全缓冲区扫描。

### 5. 尝试过但已回退

**内联路径省略 `interestOps(0)` / `interestOps(OP_READ)`**：内联处理全程同步、兴趣位本来就是
OP_READ，理论上可省两次 selector 更新。实测出现服务端吞吐崩塌（后续轮次连接被拒），
且未观察到收益，已完整回退。

## 优化方向（按收益排序）

### A. 已排除（不构成瓶颈）
- ~~增加 eventLoops~~：2/4/8/16 无差异，瓶颈是 syscall 固定成本而非并行度
- ~~压测端饱和~~：3 个客户端 JVM × 100 连接聚合后服务端仍是上限
- ~~日志字符串构建~~：见上，测不出差异
- ~~响应头解析分配~~：`substring`/`trim`/`toLowerCase` 每头约 4 个临时对象，但相对 syscall 成本可忽略

### B. 性能关键（能追上 Go 的前提是换掉 syscall 模型）
1. **连接数门限 + 背压门限** — 当前 `ServerSetting.maxConnections` 只在 `AbstractProxyServer` 里起效，
   HTTP@NIO/AIO 里面没有连接计数门限，不对
2. **批量系统调用** — 真正的下一步：单次 `select` 后按批分发、把多个响应合并成一次 gather write；
   在 Windows 上受限于 IOCP/epoll 语义，Linux 上用 `epoll` + `writev` 才有空间
3. **更低的延迟：inline dispatch** — NIO 已实现，AIO 尚无等效 inline（AIO 已有小响应合并逻辑）
4. **更低的 stack 膨胀：虚拟线程 vs goroutine** — JDK 25 的虚拟线程可以比 goroutine 更轻松地扩展到 100 万线程
5. **零拷贝 I/O** — `transferTo` + NIO gather into...
6. **TLS 优化** — 使用 SSLEngine 的批量 wrap/unwrap
7. **算力和监听** — SO_REUSEPORT、epoll level triggered。注意：Windows 上 `ServerSetting.autoConfig()`
   会锁定 `eventLoops = 4`，这是刻意为之，不要为压测去改

### C. 架构优化
- 去掉 javassist/servlet 依赖；Servlet 能力放在 spi 扩展里
- 多层（Jetty）通过 SPI 注册为扩展，不塞主包

### D. 测试验证
- 功能回归：`docs/perf/bench/fncheck.ps1`（快路径 200 / 自定义多头的慢路径 / 自定义状态码 /
  用户覆盖系统头 / 空体 / 100KB 大体 / keep-alive 复用 / 404），NIO 与 AIO 各跑一遍
- 性能：`srvcpu.ps1 -Option '-Dnio.combine=0'` 与默认配置交错跑，比较 `cpu_ns_per_request`
- 平台下限：`rawcal.ps1`
- 本机 Windows 11 测试
