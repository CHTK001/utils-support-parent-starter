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

### Fixed
- `Socks5ProxyServer`：`AbstractProxyServer.readBytes` 返回线程内复用的 64 字节缓冲，被直接交给 `InetAddress.getByAddress(...)` → 长度非法抛异常 → `target=null` → CONNECT 回 4（host unreachable）。**即所有 SOCKS5 客户端的 IPv4/IPv6 连接都不可能成功**；域名分支还会把缓冲残留字节解进域名。改为返回精确长度副本。
- `JdkWebSocketServer`：handler 通过 `setResult` 设置的内容**从不回写**（客户端能发不能收）；`>64KB` 帧的 64 位长度字段顺序写反（先低 4 字节再写 4 个 0）；绑定后未回写实际端口。
- `JdkTelnetServer` / `JdkUdpServer`：绑定后未回写实际端口，`port=0` 时 `getPort()` 恒为 0。
- `DiscoveryConfiguration`：`close()` 不反注册（实例残留在注册中心直到 TTL，滚动重启期间流量打到死节点）；环境元数据合并在 BFPP 阶段强制实例化业务 Bean，改到 `FactoryBean.start()`。
- `DiscoveryService`：同协议第二个实例的 Bean 名 `serviceDiscovery#<protocol>#0` 不满足 `endsWith` 判断，解析永远回落默认实现；`defaultBalance` 写死为 `weight`，改为可配。
- SPI：`getServiceDefinition` 每次都全表扫描（`definitions` 的 key 本来就是服务名），改为 key 快路径 + 扫描兜底；注册/查找并发无同步（`SortedArrayList` 非线程安全），补 `synchronized(list)`；未命中加 `missCache`，注册时清空。
- `DefaultServiceProvider`：`keepAlive` 缓存 10 分钟到期会 `close` 实例，可能把调用方正在持有的实例关掉，改为只淘汰不关闭。

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

性能（Windows / loopback；同 JVM 自压为下限，WS ≥2 万用独立进程；建连必须分批 100 条/250ms）：

| 场景 | 并发 | 结果 |
|---|---|---|
| NIO HTTP `/echo` | 64 | **113,216 QPS**，p50 381µs / p99 3.87ms，0 错误 |
| AIO HTTP `/echo` | 256 | 77,922 QPS，p50 2.96ms / p99 9.80ms |
| 直连 TCP 回环（基线） | 128 | 36,785 QPS |
| `AioTcpProxyServer` 三层 | 128 | **31,440 QPS（基线 85.5%）**，p99 7.80ms |
| `Socks5ProxyServer` 隧道 | 128 | 15,708 QPS（42.7%），p99 16.6ms |
| `TcpProxyServer`（JDK 阻塞） | 128 | **9,828 QPS（26.7%）**，p99 34.0ms |
| `JdkUdpServer` | 128 | 32,924 pps，失败 0.3% |
| `JdkHttpProxyServer`（透传，keep-alive） | 128 | **18,827 QPS**，p50 6.34ms / p99 15.75ms，0 重连 |
| `HttpProxyServer`（反向，keep-alive） | 8 / 32 / 128 | **≈10,900–11,160 QPS**（三档持平），p50 0.51→9.34ms、p99 3.3→55.8ms，0 重连 |
| `AioHttpProxyServer`（正向，keep-alive） | 128 | 1,681 QPS，p50 23.9ms / p99 105ms，**97 次客户端重连** ⚠️ |
| WS 连接容量 | — | 5k/10k/**50k** 全通过；50k 时 4.21KB/连接、205MB 堆、2,193 条/秒 |
| WS 回环消息 | 64 conn | 35,217 条/秒 |
| WS 广播扇出 | 500 / 10k / 20k / **50k** | 2.2ms / ~200ms / 302ms / **1,364ms** 每次 |

未测：WS 百万连接（需多源 IP/多机）、50 万连接下的广播耗时、TLS 吞吐、过滤器单测。

HTTP 代理的测量口径订正：把多个场景放在同一个 JVM 里背靠背跑，会互相干扰（前一场景的连接未完全释放），表现为大量「客户端重连」——反向代理曾因此出现 105–258 次重连、QPS 从 1 万掉到 1 千。**按场景单独跑**后，反向与透传都是 0 重连、吞吐稳定（反向在 8/32/128 三档持平约 11k QPS）。

剩下唯一真实异常是正向代理 `AioHttpProxyServer`：隔离跑仍有 97 次重连、p50 23.9ms、p99 105ms，且 QPS（0.9k–4k）跨轮次波动大。根因已定位：`forward()` 直接 `connectAsync` 新建后端连接，`acquireBackend`/`returnBackend` 从未被调用（连接池是死代码），**每个请求都要重新建一次 TCP 连接**——p50 ≈ 24ms 正是建连开销。尝试把池接上后回归更差（QPS 71、重连 174 次，池里的通道带残留读状态会串响应），已回退并把「每请求新建后端连接」记为已知限制，待重做连接池（需要读状态隔离）。

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
