# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [4.0.0.52] - 2026-11-13

### Added
- 网络服务实现真实链路测试矩阵（此前代理全家 0 用例）：
  - 代理：`TcpProxyServerTest`(5)、`AioTcpProxyServerTest`(4)、`HttpProxyServerTest`(3)、`AioHttpProxyServerTest`(2，含 CONNECT 隧道)、`Socks5ProxyServerTest`(3)
  - 服务器：`JdkHttpServerTest`(2)、`JdkTelnetServerTest`(2)、`JdkUdpServerTest`(2)、`JdkWebSocketServerTest`(2)
  - 其它：`ServiceDefinitionFinderTest`(3)、`DiscoveryLifecycleTest`(2)
- 压测（默认跳过）：`ProxyThroughputTest`（`-Dbench.throughput=true`）、`WebSocketThroughputTest`（`-Dbench.ws=true`）以及独立进程客户端 `WebSocketFanoutClient`（`-Dbench.ws.processes` 可拆多进程）
- `WebSocketSyncServer` 增加 accept/握手诊断计数：`getAcceptedTotal()` / `getHandshakeOkTotal()` / `getHandshakeFailTotal()` / `getLastHandshakeError()`
- 非原生包（`utils-support-network-starter`）用例：`VertxHttpServerTest`(2)、`VertxTcpServerTest`(3)、`VertxHttpProxyServerTest`(2)、`VertxWebSocketServerTest`(1)（该模块原先连 junit 依赖都没有，本轮补上）
- 非原生包 `sync/netty` 端到端用例：`NettyWebSocketSyncServerTest`(4)、`NettyWebSocketSyncClientTest`(3)、`NettyHttpSyncServerTest`(3)。四个实现此前均零覆盖，且三对真实链路全部是「先写用例跑红 → 再修实现」。模块合计 18 条全绿。
- 新增 `能力矩阵.md`：服务端实现清单（协议/IO 模型/TLS/关键能力/状态）、能力矩阵、两轮吞吐实测（上一轮隔离 + 本轮复测）、测试覆盖矩阵、已知限制与复现命令。压测脚本 `test/perf/run-proxy-isolated.ps1`（每场景独立 JVM）。
- 独立进程压测工具：`HttpLoadClient`（HTTP keep-alive）与 `NioBenchServer`（按文件内容回包），用于与 nginx 做同 payload 对比

### Fixed
- `Socks5ProxyServer`：`AbstractProxyServer.readBytes` 返回线程内复用的 64 字节缓冲，被直接交给 `InetAddress.getByAddress(...)` → 长度非法抛异常 → `target=null` → CONNECT 回 4（host unreachable）。**即所有 SOCKS5 客户端的 IPv4/IPv6 连接都不可能成功**；域名分支还会把缓冲残留字节解进域名。改为返回精确长度副本。
- `JdkWebSocketServer`：handler 通过 `setResult` 设置的内容**从不回写**（客户端能发不能收）；`>64KB` 帧的 64 位长度字段顺序写反（先低 4 字节再写 4 个 0）；绑定后未回写实际端口。
- `JdkTelnetServer` / `JdkUdpServer`：绑定后未回写实际端口，`port=0` 时 `getPort()` 恒为 0。
- `DiscoveryConfiguration`：`close()` 不反注册（实例残留在注册中心直到 TTL，滚动重启期间流量打到死节点）；环境元数据合并在 BFPP 阶段强制实例化业务 Bean，改到 `FactoryBean.start()`。
- `DiscoveryService`：同协议第二个实例的 Bean 名 `serviceDiscovery#<protocol>#0` 不满足 `endsWith` 判断，解析永远回落默认实现；`defaultBalance` 写死为 `weight`，改为可配。
- SPI：`getServiceDefinition` 每次都全表扫描（`definitions` 的 key 本来就是服务名），改为 key 快路径 + 扫描兜底；注册/查找并发无同步（`SortedArrayList` 非线程安全），补 `synchronized(list)`；未命中加 `missCache`，注册时清空。
- `DefaultServiceProvider`：`keepAlive` 缓存 10 分钟到期会 `close` 实例，可能把调用方正在持有的实例关掉，改为只淘汰不关闭。
- `HttpProxyServer`：后端响应头是逐字节读**原始 socket**（一个响应头 100~200 次系统调用），改为按 Socket 缓存 `BufferedInputStream`（关闭时同步清理，不能每请求新建否则会吞掉多余字节）。
- `NioServerResponse`：`ECHO_HEADER_CACHE` 以 `Content-Length` 为键且无上限（可被撑爆），改为 >64KB 不缓存、条数上限 256。
- `VertxWebSocketServer`：`onSubscribe` 注册的处理器**永远不会被调用**——消息回调里走的是 `handleRequest`（路由/过滤器链），那条路径不查 `topicHandlers`。改为直接调用订阅处理器（文本帧与二进制帧两条路径都修）。修复前用例 16.5s 读超时，修复后 1.1s 通过。
- `NettyWebSocketSyncServer`（名字含 Netty，实为手写 socket 帧解析）四处真缺陷，修复前 3 条用例全红：
  - 服务端发的文本帧**带随机掩码**。RFC 6455 只允许客户端帧掩码，浏览器/标准客户端会以 1006 直接关连接（JDK 版实现已明确修过这一处，此类漏了）。
  - `length==127` 分支只读 4 字节长度（应为 8 字节），读到的是高位全 0 的 4 个字节，70KB 大帧被解析成 0 长度并串帧。
  - 连接器是 `0..200` 的 `SynchronousQueue` + `CallerRunsPolicy`：第 201 条长连接的 handler 落到 **accept 线程自己**并阻塞在读帧上，之后再有新连接都进不来。实测 300 条并发长连接只接纳 200 条；改虚拟线程池后 300/300。
  - `bind()` 用默认 backlog 50 且绑定后不回写实际端口：突发建连丢 SYN（100 条只成 90 条）、`port=0` 时 `getPort()` 恒为 0。改为配置 backlog 并回写端口（与 JDK 版口径一致）。
- `NettyWebSocketSyncClient`：构造传入的 `serverUrl` **被完全丢弃**，`connect()` 固定连 `ws://localhost:8080` —— 无论配什么地址都连不上，`NettyWebSocketSyncFlow` 建连必抛异常（修复前 3 条端到端用例全部 `WebSocket 连接失败`）。另修：客户端帧不掩码且只写 7 位长度（>125 字节必坏）、接收端用 `InputStreamReader` 按字符解帧（非 ASCII 负载错位/读挂）、127 分支同样只读 4 字节。改为按字节解析 + 掩码 + 7/16/64 位长度。
- `NettyHttpSyncServer` / `NettyHttpSyncClient`：这对 HTTP 同步链路的上行消息**从来发不出去**。客户端把 `topic` 当参数名拼出 `up=from-client`，服务端 `extractParam` 只找 JSON 片段 `"topic"`/`"message"`，于是每个 send 都回 400，而客户端不检查状态码 → 静默丢弃。改为客户端发 `topic=...&message=...` 表单、服务端按表单解析（保留 JSON 兼容）并对非 200 抛错。另修：405 分支只调 `sendResponseHeaders(405, 0)` 不关闭 exchange，chunked 没有结束块、客户端等到超时；204 改为 `-1` 无响应体；`port=0` 回写实际端口；请求体按 UTF-8 解码。
- `AbstractServer`：构造函数里的 `initBuiltinFilters()` 走的是**可覆写**的 `addFilter`，而构造期子类字段初始化器尚未执行 —— 代理实现（`AbstractProxyServer`）在覆写里登记 `ipFilters`，读到的还是 null。结果是**所有代理服务器一 `new` 就 NPE**：`TcpProxyServerTest` 5/5、`AioTcpProxyServerTest`/`HttpProxyServerTest`/`AioHttpProxyServerTest`/`Socks5ProxyServerTest` 全部构造失败，压测 8 个场景里 4 个直接 error。改为构造期走私有的 `addBuiltinFilter`（不派发子类覆写）。修复后代理用例 17/17、`ProxyThroughputTest` 8/8 全绿。
- `AioHttpProxyServer`：`forward()` 每个请求都新建一条后端 TCP 连接（连接池是死代码）。128 并发 keep-alive 压测下，本地临时端口被 TIME_WAIT 迅速占满，实测**从预热后的数千 QPS 退化到 13–22 QPS**、p50 落在 TCP 重传的 1s 档、重连 350–635 次。改为**按客户端会话 1:1 复用后端连接**（同目标、通道仍 open 就复用；不做跨客户端池——池内残留读状态会串响应，此前实测过）。客户端断开或响应要求 `close` 时才关闭。
- `AioHttpProxyServer`：**高并发 keep-alive 粘包丢响应头**（真缺陷）。`pumpParse` 每次 `read` 用新的 16KB 缓冲，`onResponseHead` 只消费「头+body 前缀」就把缓冲丢弃；同一后端连接上后续响应若与本次响应落在同一 TCP 段（粘包），**下一响应头的前半段被丢** → 下一请求从 body 中间解析 → 解析失败 → 连接被关。128 并发不易触发，256 并发实测 **err(重连)=6,245**、512 并发 8,248。修复：①`ClientCtx` 增加连接级 `backendBuf`，`readBackendResponse` 先消费残留字节再读网络；②`onResponseHead` 只取 `min(remaining, contentLength)` 并 `compact()` 保留后续字节；③`LimitedRelay` 增加 `overflow` 缓冲，读到超过本响应剩余量的字节留回 `backendBuf`；④`pumpParse` 不再 `clear()`、并对 `ERROR` 显式走失败回调（原先会被当 DONE 触发 NPE）。修复后 128/256/512 并发 **err=0**；同时 128 并发吞吐 12,725 → **24,775 QPS**（+95%）。
- 测试夹具加固（`ProxyTestSupport` / `ProxyThroughputTest`）：`KeepAliveHttpBackend` 与压测客户端原先**逐字节 `read()`** 解析头部，256 并发下每秒数十万次系统调用，在 Windows 虚拟线程上引发偶发连接异常，**被误记为「代理断连」**。改为 `BufferedInputStream`（16KB）。同时压测建连改为分批（64 条/100ms）：Windows 内核把 listen backlog 截断到约 200，一次性 256/512 并发建连会被 RST（表现为 `ConnectException`），这是平台限制而非代理缺陷。新增 `AioHttpProxyStressTest`（256 并发 keep-alive 全程零断开）与 `DirectBackendStressTest`（直连后端对照，用于区分代理与夹具问题）。
- `TcpProxyServer` / `JdkHttpProxyServer` / `Socks5ProxyServer`：双向转发泵从虚拟线程改为**平台线程**（accept 循环仍用虚拟线程，stop 时一并回收）。原因：每连接 2 条驻留虚拟线程在 Windows 上全挂在共享 poller 上，128 并发时 park/unpark 竞争把吞吐腰斩。同骨架对照实验（128 并发、隔离 JVM）：虚拟线程泵 14,618 QPS、去掉 SO_TIMEOUT 10,997（与超时无关）、平台线程泵 **27,984**。落地后：`tcp-proxy-jdk` 9,944 → **21,775**（2.19×，基线 25,721 的 84.7%）、`socks5` 11,166 → **26,827**、`http 透传` 12,450 → **14,430**。代价：每连接常驻 2 个平台线程（空闲 60s 回收），并发连接数建议上限约 2k，更大连接数用 `AioTcpProxyServer`（IOCP）。容量实测（新增 `ProxyConnectionScaleTest`，默认跳过）：`TcpProxyServer` 2k 连接保持期线程 **4,020**（≈2/连接）、堆增量 341MB；`AioTcpProxyServer` 同连接数线程恒 **37**（零驻留）、堆增量 53MB，5k / 10k 连接线程仍 37（堆 141 / 251MB），1 万连接存活率 100%。

> 上述两处 proxy/http 改动**没有实测吞吐提升**：反向代理三轮复测 9.9k / 11.1k / 14.8k QPS（改动前 10.9k），处在噪声范围内；消除系统调用是真的，但在该并发下不是瓶颈。不计入性能收益。
> 本轮的 `AioHttpProxyServer` 粘包修复与泵模型改造**有实测收益**（128 并发 12,725 → 24,775 QPS、256/512 并发 err 归零），单独计。

### 测试与性能矩阵

功能覆盖（Server 家族 9 个实现中 8 个有真实链路用例）：

| 实现 | 用例 | 状态 |
|---|---|---|
| `NioHttpServer` | `HttpBackpressureWriteTest` / `DirectBufferReadTest` / `ConnectionScaleLoadTest` | ✅ |
| `AioHttpServer` | `HttpBackpressureWriteTest`（含 TLS 流水线） | ✅ |
| `JdkTcpServer` | `JdkTcpServerFixVerificationTest`(9) / `TcpServerNioIntegrationTest`(2) | ✅ |
| `AioTcpServer` | `AioTcpFrameFramingTest` | ✅ |
| `JdkHttpServer` / `JdkTelnetServer` / `JdkUdpServer` | 各 2 | ✅ |
| `JdkWebSocketServer` | 2（本轮补齐） | ✅ |
| `WebSocketSyncServer` | 规模 / 广播 / 扇出 / 吞吐 | ✅ |
| `TcpProxyServer` | 5 | ✅ 回退必红 |
| `AioTcpProxyServer` / `HttpProxyServer` / `JdkHttpProxyServer` / `AioHttpProxyServer` / `Socks5ProxyServer` | 4 / 3 / 1 / 2 / 3 | ✅ |
| `VertxHttpServer` / `VertxTcpServer` / `VertxHttpProxyServer` / `VertxWebSocketServer` | 2 / 3 / 2 / 1 | ✅ |
| `NettyWebSocketSyncServer` + `NettyWebSocketSyncClient` | 4 + 3（端到端：握手/掩码/大帧/UTF-8/并发长连接/随机端口） | ✅ 回退必红 |
| `NettyHttpSyncServer` + `NettyHttpSyncClient` | 3（端到端：上下行往返 / 405 / 204 / 随机端口） | ✅ 回退必红 |

性能（Windows / loopback；同 JVM 自压为下限，WS ≥2 万用独立进程；建连必须分批 100 条/250ms）：

| 场景 | 并发 | 结果 |
|---|---|---|
| NIO HTTP `/echo` | 64 | **113,216 QPS**，p50 381µs / p99 3.87ms，0 错误 |
| AIO HTTP `/echo` | 256 | 77,922 QPS，p50 2.96ms / p99 9.80ms |
| 直连 TCP 回环（基线） | 128 | 36,785 QPS |
| `AioTcpProxyServer` 三层 | 128 | **31,440 QPS（基线 85.5%）**，p99 7.80ms |
| `Socks5ProxyServer` 隧道 | 128 | 15,708 QPS（42.7%）→ 改后 **26,827**，p99 12.8ms |
| `TcpProxyServer`（JDK 阻塞） | 128 | 9,828 QPS（26.7%）→ 改后 **21,775**（基线 84.7%），p99 17.9ms |
| `JdkUdpServer` | 128 | 32,924 pps，失败 0.3% |
| `JdkHttpProxyServer`（透传，keep-alive） | 128 | 18,827 QPS（上一轮）→ 改后同批 14,430（该批基线 25,721，机器负载更高），0 重连 |
| `HttpProxyServer`（反向，keep-alive） | 8 / 32 / 128 | **≈10,900–11,160 QPS**（三档持平），p50 0.51→9.34ms、p99 3.3→55.8ms，0 重连 |
| `AioHttpProxyServer`（正向，keep-alive） | 128 | 1,681 QPS，p50 23.9ms / p99 105ms，**97 次客户端重连** ⚠️（同日已修复，见下） |
| WS 连接容量 | — | 5k/10k/**50k** 全通过；50k 时 4.21KB/连接、205MB 堆、2,193 条/秒 |
| WS 回环消息 | 64 conn | 35,217 条/秒 |
| WS 广播扇出 | 500 / 10k / 20k / **50k** | 2.2ms / ~200ms / 302ms / **1,364ms** 每次 |

未测：WS 百万连接（需多源 IP/多机）、50 万连接下的广播耗时、TLS 吞吐、过滤器单测。
非原生包仍未测：`KcpServer` / `KcpHttpServer`、`RSocketServer`、`MqttServer`、`SmbServer`、`DHTServer`、`SshServer`、`WebRtcServer`、`JRebelHttpServer`、`Netty*SyncFlow`（Flow 层只测了其依赖的 client/server 真实链路）。

HTTP 代理的测量口径订正：把多个场景放在同一个 JVM 里背靠背跑，会互相干扰（前一场景的连接未完全释放），表现为大量「客户端重连」——反向代理曾因此出现 105–258 次重连、QPS 从 1 万掉到 1 千。**按场景单独跑**后，反向与透传都是 0 重连、吞吐稳定（反向在 8/32/128 三档持平约 11k QPS）。

正向代理 `AioHttpProxyServer` 的修复（同日）：隔离复测发现它不只是慢，而是**不可用**——
128 并发 keep-alive 下退化到 13–22 QPS、p50 落在 TCP 重传的 1s 档、重连 350–635 次（本地临时端口被每请求新建的 TIME_WAIT 占满）。按「客户端会话 1:1 复用后端连接」修复后，同口径 **12,725 QPS、p50 9.1ms、p99 29.7ms、0 重连**，与反向/透传同一水平。复测数据与口径见 `能力矩阵.md`。

已知不稳定用例：`HttpBackpressureWriteTest#aioHttpServerTlsKeepsEveryPipelinedResponse`（满负载 60s+ 超时，单独复跑 4.6s 通过）、`TimeWheelExpirySchedulerTest#refailReschedulesDeadline`、`FtpServerPortBindingTest` FTPS 并发场景。

## [4.0.0.43] - 2026-09-04

### Added
- Request Collapse framework (inspired by collapse-executor):
  - `concurrent/collapse` kernel in common-starter: `CollapseConfig`, `CollapseExecutor`, `CollapseBatchFunction`, `CollapseResultMapper`, `CollapseExecutorFactory`, and `CollapseFlow` facade (chainable `threshold`/`collectingWaitTime`/`virtualThread`, SPI auto-discovery with direct-execution fallback, `executorFactory` callback injection for pure-JDK collapse)
  - `network/client/CollapseHttpClient` in common-starter: collapses concurrent GET requests sharing the same URL into one real call; `HttpClientFactory.collapse(...)` entry point
  - New module `utils-support-collapse-starter`: `DefaultCollapseExecutor` (merge-and-split via resultMapper / same-key collapse via batchFunction, CAS single collector + lock-free queue + virtual threads), `DefaultCollapseExecutorFactory` (`@Spi("collapse")`, registered via `META-INF/services`)
  - `@Collapsible` annotation + `CollapsibleAdvisor`/`CollapsibleIntercept` in spring-starter (v2 merge-and-split semantics: single Collection input + Map return; non-Map degrades to same-key collapse; empty input / missing SPI degrades to direct execution)
  - `CollapseAutoConfiguration` in springboot-starter (toggle `collapse.executor.enabled`, default enabled) registered in `AutoConfiguration.imports`
- `fallback` now supports `beanName#methodName` references via new `FallbackResolver`, applied to `@RateLimiter`/`@CircuitBreaker`/`@Bulkhead`/`@Timeout` (deduplicated 4 private implementations)
- Collapse enhancements:
  - `@Collapsible` gains `key()` (SpEL): elements are deduplicated/merged by a key extracted from each element, enabling merge-and-split for entities whose key differs from the element itself (identity behavior when empty)
  - `@Collapsible` gains `fallback()`: collapse batch failure degrades per-caller via `FallbackResolver` (`beanName#methodName` or same-class method)
  - Global defaults via Spring Boot `collapse.executor.wait-threshold` / `collapse.executor.collecting-wait-time` (new `CollapseProperties`; annotation sentinels `-1`/`-2` mean "unset", falling back to global then built-in defaults)
  - Collapse metrics: `CollapseExecutor.metrics()` (default empty) and `DefaultCollapseExecutor` statistics (`executedCount`, `batchExecutionCount`, `avgBatchSize`, `maxBatchSize`, `mergeRate`)
  - `CollapseHttpClient` collapse key now normalizes URL query parameters (sorted), so equivalent URLs differing only in parameter order (`?a=1&b=2` vs `?b=2&a=1`) collapse into one real call
  - `CollapseFlow.executorFactory(...)` now throws `IllegalStateException` if called after the first `execute()` (contract enforced instead of silently ignored)
  - `mergeAndSplit` guards against mixing different target instances under a shared executor name (explicit `@Collapsible(name)` collision across beans) — throws instead of returning silently corrupted results
  - Merge core invocation now `setAccessible(true)` before reflection invoke — fixes `IllegalAccessException` for interface / package-private-declared methods invoked cross-package (CGLIB override methods are public, so this is a no-op there)
  - Boot startup hardening: `UtilsSpringConfiguration` interceptor/advisor beans now back off when the same-name bean definitions already exist (`@ConditionalOnMissingBeanDefinition`, pure Spring-core mechanism); `CollapseAutoConfiguration` ordered `before UtilsSpringBootAutoConfiguration` so the `CollapseProperties`-aware interceptor deterministically wins in Boot; `UtilsSpringBootAutoConfiguration` now provides missing `BulkheadIntercept` / `TimeoutIntercept` beans
  - `StringValuePropertyResolver` is now null-tolerant (`placeholderSupport == null` degrades to a pass-through resolver) — fixes the `AbstractMethodAnnotationIntercept` constructor NPE that made `RateLimiterIntercept` / `CircuitBreakerIntercept` / `BulkheadIntercept` / `TimeoutIntercept` unconstructible
  - `ConcurrentEndpoint` (springboot-starter actuator endpoint) now takes its datasource repositories as optional `ObjectProvider` dependencies and degrades gracefully (empty results / clear error) when no datasource is configured — non-datasource Boot apps no longer fail at startup
  - `ConcurrentEndpoint` additionally backs off entirely (`@ConditionalOnClass` on the datasource repository classes) when the datasource starter jar itself is absent — no class-loading failure in apps that never pull datasource
  - New demo project `collapse-demo/`: standalone Spring Boot app (Boot 4.1.0 / Spring 7.0.8) exercising all collapse strategies via `AllStrategiesTest` — merge-split, same-input broadcast, `key()` SpEL merge, `bean#method` fallback, global defaults, `CollapseHttpClient` GET collapse / POST passthrough, `CollapseFlow` callback, stacking with `@CircuitBreaker`, plus rate limiting, standalone circuit-breaker degradation and distributed lock
  - `RateLimiterFlow.doCreate` now skips null SPI provider entries (defensive) — fixes a `NullPointerException` when the SPI list contains an instantiation-failed provider (first triggered by real `@RateLimiter` usage in the demo)
  - Tests added: `CollapseHttpClientTest` (`executeAsync` failure broadcast via Mono onError, `closeDelegate` three-state behavior), `CollapseFlowTest` (executorFactory callback actually used), `CollapseAutoConfigurationDiscoveryTest` (AutoConfiguration.imports registration + real SpringApplication discovery & binding with no auto-config exclusions), plus master-switch (`collapse.executor.enabled=false`) and illegal `key()` SpEL reporting coverage
  - JUnit regression tests: `DefaultCollapseExecutorTest` (collapse-starter), `FallbackResolverTest` + `CollapsibleInterceptTest` (spring-starter), `CollapseAutoConfigurationTest` (springboot-starter, verifies `collapse.executor.*` binding → `CollapsibleIntercept` injection chain); runnable via `mvn test -DskipTests=false -am` (root `skipTests=true` default overridable; test-scope deps `junit-jupiter`, `aspectjweaver`, `utils-support-collapse-starter` added to spring-starter/springboot-starter)

### Fixed
- `CollapsibleIntercept` no longer extends `AbstractMethodAnnotationIntercept` (its placeholder resolver NPEs on instantiation without a placeholder environment), so it can be created inside a Spring container
- `@Collapsible` annotation lookup failed under interface/JDK dynamic proxies: `CollapsibleAdvisor` now resolves annotations through the target class (`getMostSpecificMethod` + `Advised.getTargetSource`)
- CGLIB proxy class names (`$$SpringCGLIB$$`) polluted collapse executor names: default names now use `ClassUtils.getUserClass`
- Spring 7 removed `ClassUtils.findMethod`: fallback resolution switched to `getMethodIfAvailable`
- `beanName#methodName` fallback failed on non-calling threads: `SpringBeanUtils` holds the context in a ThreadLocal, so `FallbackResolver` now falls back to a globally registered context (registered by `CollapsibleIntercept` via `ApplicationContextAware`)
- `CollapseConfig` now rejects negative `waitThreshold` (`IllegalArgumentException`; `0` = execute immediately)
- Merge core invocation now resolves the most specific method on the target class (`getMostSpecificMethod`) before reflection invoke — fixes `IllegalAccessException` when the annotated method's declaring interface is package-private and cross-package
- common-starter: removed `maven-compiler-plugin` `failOnError=false` (module now compiles cleanly, so compile errors fail the build instead of being masked)

## [4.0.0.42] - 2026-07-25

### Fixed
- Resolve JDK25 compilation errors by restoring files deleted in commit `113dc19c9`
- Rewrite `ShutdownOnFailureStructuredConcurrencyProvider` and `ShutdownOnSuccessStructuredConcurrencyProvider` to use JDK25-compatible virtual-thread + CompletableFuture style (remove deprecated `StructuredTaskScope` usage)
- Fix `LockFlow.java` chain API compatibility with JDK25
- Fix `ClassPathResourceFinder.java` JDK25 compatibility
- Remove `DispatcherAutoConfiguration` chronicle bean reference (chronicle module excluded)
- Fix `OkHttpSseClient.java` syntax error
- Update Jackson version from 2.21.0 to 2.18.3 for dependency resolution
- Fix `DistributedLockIntercept.java` lambda exception compatibility
- Fix `LockAutoConfiguration.java` to use public `provider()` method

### Changed
- Exclude incomplete modules from root pom: cloud-parent, filesystem-parent, extra-parent, middleware-parent, deeplearning-parent, spider-starter, datalake-parent, datatask-parent, datasync modules
- Update module structure: remove spider-starter and deeplearning-starter from core-parent active modules

### Restored (from `113dc19c9^`)
- `concurrent/dispatcher/DispatcherProvider.java`
- `concurrent/dispatcher/DispatcherDefinition.java`
- `concurrent/dispatcher/DispatcherFlow.java`
- `concurrent/dispatcher/provider/MemoryDispatcherProvider.java`
- `concurrent/lock/LockFlow.java`
- `concurrent/pool/ConnectionPool.java`
- `concurrent/pool/GenericObjectPool.java`
- `config/center/AbstractConfigCenter.java`
- `network/client/*` (HttpClient, JdkHttpClientExecutor, etc.)
- `network/rpc/*` (RpcClient, RpcServer, etc.)
- `network/ipc/*` (IpcServer, etc.)
- `network/download/*` (Downloader, extractors)
- `network/sse/*` (SseClient, etc.)
- `ai/chat/*` (ChatClient, ChatClientSetting, etc.)
- `ai/agent/*` (Agent, AgentDefinition, etc.)
- `ai/bot/*` (BotClient, etc.)
- `ai/mcp/*` (McpClient, McpManager, etc.)
- `ai/rag/*` (RagClient, VectorService, etc.)
- `ai/skill/*` (SkillManager, etc.)
- `ai/embedding/*` (EmbeddingClient, etc.)
- `ai/image/*` (ImageClient, etc.)
- `ai/memory/*` (MemoryManager, etc.)
- `ai/calibration/*` (Calibrators)
- `ai/splitter/*` (TextSplitter, etc.)
- `ai/video/*` (VideoClient, etc.)
- `ai/context/*` (ContextCompressor, etc.)

## [4.0.0.41] - 2026-07-24

### Changed
- Integrate llama.java models (MiniCPM5, EmbeddingGemma, Otzaria, NeuTts2e, Gemma4, Bitnet)
- Add LlamaModelRegistrar and SPI
- Fix compiler source/target to 21
- Exclude incomplete modules from build
