# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
