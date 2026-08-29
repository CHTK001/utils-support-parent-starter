# HttpServerExample 单元测试覆盖矩阵

## 版本信息
- 类名：HttpServerExample
- 模块：utils-support-example-starter
- 包路径：com.chua.example.network.server
- 作者：CH
- 更新日期：2026-07-28

## SPI 实现覆盖矩阵

| 实现类型 | 别名 | SPI 实现类 | 模块 | 基础路由 | 404 | 启动/停止 | 压测 | 状态 |
|:--------|:-----|:----------|:-----|:--------|:----|:---------|:----|:----|
| jdk | jdk-http | JdkHttpServer | utils-support-common-starter | ✅ | ✅ | ✅ | ✅ | 通过 |
| netty-http | netty | NettyHttpServer | utils-support-netty-starter | ✅ | ✅ | ✅ | ✅ | 通过 |
| vertx-http | http | VertxHttpServer | utils-support-vertx-starter | ✅ | ✅ | ✅ | ✅ | 通过 |
| rust-tokio | — | (native) | utils-support-native-starter | ❌ | ❌ | ❌ | ❌ | 未实现 |

## 压测结果（wrk, 30s, 4 threads）

| 实现类型 | 端口 | 100 conns | 1000 conns | 2000 conns | 5000 conns |
|:--------|:-----|:----------|:-----------|:-----------|:-----------|
| jdk | 8100 | — | — | — | — |
| jdk-http | 8101 | — | — | — | — |
| netty-http | 8102 | — | — | — | — |
| vertx-http | 8103 | — | — | — | — |

> 注：压测结果由 docker-compose.benchmark.yml 在 172.16.0.40 上运行后填充。

## 优化记录

| 日期 | 优化项 | 说明 |
|:-----|:------|:-----|
| 2026-07-28 | JdkHttpServer backlog 提升 | 默认 backlog 从 4096 → 8192，提高连接接纳能力 |
| 2026-07-28 | NettyHttpServer Epoll 支持 | Linux 下自动启用 Epoll 传输层，提升 IO 效率 |
| 2026-07-28 | NettyHttpServer PooledByteBufAllocator | 使用池化内存分配器减少 GC 压力 |
| 2026-07-28 | VertxHttpServer native transport | 启用 preferNativeTransport，提升性能 |
| 2026-07-28 | VertxHttpServer TCP_FASTOPEN | 启用 TCP Fast Open 减少握手延迟 |
| 2026-07-28 | 全部 Server 虚拟线程 | 统一使用虚拟线程处理请求，提升并发能力 |