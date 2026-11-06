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
功能验证用 `fncheck.ps1`（9 个场景 × NIO/AIO 双协议，含 SSE 流式帧），背压验证用 `slowclient.ps1`，
平台标定用 `rawcal.ps1`，历史版本同类代码对照用 `baseline.ps1` + `srvcpu.ps1 -Prepend`。

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

注意上表的绝对值只在**同一次连续测量**内可比。后台负载变化会让同一份代码整体平移
（实测同一版本在 63K~81K ns/请求之间移动），跨时段只能比「同一次开机内背靠背的 A/B 差值」，
不能比绝对值。历史版本对照可用 `baseline.ps1 -Rev <git提交>` 把旧版 `NioHttpServer` /
`AioHttpServer` / `NioServerResponse` 编到临时目录，再用 `srvcpu.ps1 -Prepend <classes目录>`
把它放到 classpath 最前，实现同一时刻的同类代码 A/B。

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

### 4. 连接数上限（`NioHttpServer` / `AioHttpServer` / `ServerSetting`）

`ServerSetting.autoConfig()` 会按堆大小把 `maxConnections` 设为 10 万~100 万，但 **HTTP 的 NIO/AIO
两条路径从来没有读过这个值**：任何数量的连接都能被接纳，`maxConnections` 是个空设置。
同时 `AbstractServer.start()` 会再调一次 `autoConfig()`，把用户显式设置的值静默覆盖回堆推导值。

修复分三处：

1. `ServerSetting` 增加 `maxConnectionsExplicit` 标记与显式 `setMaxConnections()`，`autoConfig()`
   仅在未显式设置时才按堆推导——与既有的 `maxConcurrencyExplicit` 同一模式。
2. `NioHttpServer` 增加 `activeConnections` 计数：`accept` 成功后 +1，`closeConn` 时 -1；
   超限的新连接在 accept 阶段立刻关闭，语义对齐 `AbstractProxyServer`（`Semaphore` 耗尽即关闭）。
   占位用「先 increment 再判断，超额立刻 decrement 归还」，并发 accept 下不会突破上限。
3. `AioHttpServer` 已有 `activeConnections` 计数，补上同样的上限校验（原本只统计不校验）。

计数回收覆盖了全部关闭路径：NIO 的 `closeConn` 是唯一出口，另外三条绕过它的路径
（SSL 阻塞路径的 `finally`、`pendingAcceptQueues` 中连接已失效/register 失败、事件循环的异常兜底）
也逐一补了归还。

验证用 `maxconncheck.ps1` + `MaxConnProbe.java`（`-Max N` 启动服务端，开 2N 个 keep-alive 连接）：

```
=== nio (maxConnections=5) ===
  [probe] max=5 kept=5 extra_served=0 extra_rejected=5 total_served=5
  [probe] PASS
  [probe] after_release_served=5/5
  [probe] LEASE-OK
=== aio (maxConnections=5) ===  同上
```

`after_release_served=5/5` 专门用于证明名额会归还——若计数泄漏，长时间运行后必然全量拒连。
该项对吞吐无影响（每连接一次原子操作，不在每请求路径上）：实测 NIO 71K / AIO 73K QPS、
每请求 CPU 53µs。

### 5. 单连接写队列背压门限（`ServerSetting` / `NioHttpServer` / `AioHttpServer` / `NioServerResponse`）

连接数上限解决的是「同时有多少条连接」，这条解决的是「一条连接能积压多少未写出的字节」。
SSE 生产端（`AiProtocolServerFilter` 的 `Consumer<ChatResponse>` 回调、同步 `sseEvent` 循环）
是**持续推送**模型：客户端不读，队列就无界增长，`maxConcurrency` 只数请求、不约束队列深度，
一个卡死的客户端足以把堆吃穿。

**策略：永不阻塞生产者。** NIO 的内联派发与 AIO 的 SSE 推送都跑在 IO 线程上（事件循环 /
IOCP 回调组），一旦阻塞，整条分片上的所有连接一起停摆。因此统一规则：
`pendingWriteBytes + 本帧 > maxPendingWriteBytes` → 记 warn 日志、关闭该连接、丢弃这一帧。
默认门限 `ServerSetting.DEFAULT_MAX_PENDING_WRITE_BYTES = 4MB`，`<= 0` 关闭该机制。

三个必要的配套细节：

1. **整帧丢弃，不留半帧。** `sseEvent` 原本把 chunk 长度行、数据、CRLF 分三次入队；
   断在中间会写出「有长度行没数据」的破损分块编码。改为 `buildChunk(byte[])` 拼成一整帧单次入队，
   顺带少两次入队与两次系统调用。`sseClose` 的零块同理。
2. **非阻塞通道不再忙等。** `NioServerResponse.writeBytesDirect` 原来 `while (hasRemaining())`
   反复重试，非阻塞通道写不动时事件循环线程 100% 空转。改为 `written == 0` 即返回，
   剩余部分由队列 + OP_WRITE 续写（正常响应路径本来就由 `asyncWriter` 接管，这里只兜底 SSE 直写）。
3. **生产端要能知道消费者没了。** 断开连接只是止损，若生产循环不知道，仍会在无人接收的流上
   空转到事件数耗尽。新增 `ServerResponse.isChannelClosed()`（接口默认 `false`，
   `NioServerResponse` 覆盖为真实状态）与 `NioServerResponse.abort()`；服务器在背压断开后调用
   `abort()`，生产端轮询 `isChannelClosed()` 即可提前退出。实测 50 万事件的生产循环在
   4MB 处断开后立刻停止（实际生成 ~8,100 帧，而不是跑完 50 万帧）。

顺带修掉两处相关缺陷：`sendFile` 在有流式钩子时，原来响应头与 `transferTo` 的文件体走两条通道，
既可能在 AIO（`channel == null`）下 NPE，也无法保证头/体顺序；现在改为头与 256KB 分块体统一经
流式钩子入队保序（同时让大文件下载也受门限约束），无钩子时才退回「直写头 + `transferTo`」。

**为什么不用 Reactor。** 工程里确实有 `reactor-core` 依赖，但这一层不需要它：门限本身是纯字节记账
（NIO 侧一个 `long` 计数器在 `writeQueue` 监视器内，AIO 侧一个 `AtomicLong`），加一次比较即可。
Reactor 的价值在于 `request(n)` 需求传播——要求上游是 `Publisher` 且能被反压。而当前 SSE 生产端是
`Consumer<ChatResponse>` 回调与同步 `for` 循环（`AiProtocolServerFilter` 内），没有 `Publisher` 形态，
改造成本远大于收益，还会把响应式语义扩散到整个过滤器链。`ServerSetting.reactor` 目前也只是
`autoConfig()` 里按 CPU 数写的死人配置（无任何读取方）。

验证用 `slowclient.ps1`（`SlowClientProbe.java`）：同一次运行先跑「快速消费者收到全量且帧结构完整」，
再跑「慢消费者（小接收窗口 + 完全不读）」：

```
=== nio ===
  [A] fast consumer   : PASS  framing=ok events=2000
  [B] slow consumer   : PASS  closed_by=FIN received=73924 expected=260000000 growthMb=-5.6
  [C] producer stopped: PASS  generated=8099 total=500000
  [D] server alive    : PASS
=== aio ===
  [A] fast consumer   : PASS  framing=ok events=2000
  [B] slow consumer   : PASS  closed_by=FIN received=33872 expected=260000000 growthMb=1.3
  [C] producer stopped: PASS  generated=8022 total=500000
  [D] server alive    : PASS
```

读法：慢消费者只拿到 73KB / 33KB（内核发送缓冲里在途的那部分），4MB 门限处连接被服务端 FIN 断开，
其余 260MB 从未分配；服务端工作集不涨（`growthMb` 是噪声级别的正负几 MB）；
断开后 `/echo` 仍然 200——事件循环没有被慢客户端拖住。生成的帧数 8,099 × 527B ≈ 4.1MB，
正好落在门限上，证明丢弃发生在门限处而非别的地方。

**成本**：门限记账是每响应一次原子加/减（NIO 是监视器内的 `long +=`，AIO 是两个 `AtomicLong`
操作），不在解析路径的关键环节上。用 `baseline.ps1 -Rev c08bf20593`（引入背压之前的版本）做同类代码
A/B：AIO 79,893 vs 80,856 ns/请求（差 1.2%），AIO 81,266 vs 81,460（差 0.2%），
NIO 80,833 vs 78,729（当前反而低 2.6%）——全部在噪声带内，即无可测量的每请求成本。

### 6. 压测客户端解析 bug（`HttpBench`）

`readFullResponse` 原来只检查缓冲区最后 4 个字节是否为 `\r\n\r\n`。keep-alive 下当
header 与 body 在同一次 read 中到达时，行尾标记不在缓冲区尾部，客户端会**永远阻塞**，
这是前期所有读数不可信的根本原因。改为 `findHeaderEnd` / `parseContentLength` 全缓冲区扫描。

### 7. 尝试过但已回退

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
1. ~~连接数门限~~ — **已完成**，见上文第 4 项
2. ~~背压门限~~ — **已完成**，见上文第 5 项（单连接写队列按字节封顶 + 慢消费者断开 +
   生产端可感知退出）。剩余可做：`maxConcurrency` 只在 `AbstractServer` 的请求计数上起作用，
   仍不约束「单连接在途请求数」；`writeRaw` 的缓冲模式（非 SSE）不受门限约束
3. **批量系统调用** — 真正的下一步：单次 `select` 后按批分发、把多个响应合并成一次 gather write；
   在 Windows 上受限于 IOCP/epoll 语义，Linux 上用 `epoll` + `writev` 才有空间
4. **AIO 的 inline 派发** — 该条实为误判，已修正：`AioHttpServer` 是纯异步 IOCP 回调状态机，
   全程没有 `executor.submit`，不存在「线程跳转」开销，因此不需要 NIO 那种 inline 快速路径
5. **更低的 stack 膨胀：虚拟线程 vs goroutine** — JDK 25 的虚拟线程可以比 goroutine 更轻松地扩展到 100 万线程
6. **零拷贝 I/O** — `transferTo` + NIO gather into...
7. **TLS 优化** — 使用 SSLEngine 的批量 wrap/unwrap
8. **算力和监听** — SO_REUSEPORT、epoll level triggered。注意：Windows 上 `ServerSetting.autoConfig()`
   会锁定 `eventLoops = 4`，这是刻意为之，不要为压测去改

### C. 架构优化
- 去掉 javassist/servlet 依赖；Servlet 能力放在 spi 扩展里
- 多层（Jetty）通过 SPI 注册为扩展，不塞主包

### D. 测试验证
- 功能回归：`docs/perf/bench/fncheck.ps1`（快路径 200 / 自定义多头的慢路径 / 自定义状态码 /
  用户覆盖系统头 / 空体 / 100KB 大体 / keep-alive 复用 / 404 / SSE 5 帧），NIO 与 AIO 各跑一遍
- 连接数门限：`docs/perf/bench/maxconncheck.ps1 -Max 5`（限额生效 + 名额归还）
- 写队列背压：`docs/perf/bench/slowclient.ps1`（快速消费者全量成帧 + 慢消费者被断开 +
  生产端停止 + 服务端存活），NIO 与 AIO 各一遍
- 性能：`srvcpu.ps1 -Option '-Dnio.combine=0'` 与默认配置交错跑，比较 `cpu_ns_per_request`
- 历史版本对照：`baseline.ps1 -Rev <git提交>` 生成旧版类目录，`srvcpu.ps1 -Prepend <目录>`
- 平台下限：`rawcal.ps1`
- 本机 Windows 11 测试
