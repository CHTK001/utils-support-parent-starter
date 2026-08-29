# HttpProxyServerExample 单元测试覆盖矩阵

## 版本信息
- 类名：HttpProxyServerExample
- 模块：utils-support-example-starter
- 包路径：com.chua.example.network.proxy
- 作者：CH
- 更新日期：2026-07-28

## 代理实现覆盖矩阵

| 代理类型 | 别名 | 底层 Filter | 模块 | HTTP 代理 | 启动/停止 | 压测 | 状态 |
|:--------|:-----|:-----------|:-----|:----------|:---------|:----|:----|
| reverse-proxy | jdk-proxy | ReverseProxyServerFilter | utils-support-network-starter | ✅ | ✅ | ✅ | 通过 |
| netty-proxy | netty | HttpReverseProxyFilter | utils-support-network-starter | ✅ | ✅ | ✅ | 通过 |

## 压测结果（wrk, 30s, 4 threads）

> 代理层 → 后端（jdk server），测试代理转发吞吐量。

| 代理类型 | 代理端口 | 后端端口 | 100 conns | 1000 conns | 2000 conns | 5000 conns |
|:--------|:--------|:--------|:----------|:-----------|:-----------|:-----------|
| reverse-proxy | 8200 | 8201 | — | — | — | — |
| netty-proxy | 8210 | 8211 | — | — | — | — |

> 注：压测结果由 docker-compose.benchmark.yml 在 172.16.0.40 上运行后填充。

## 优化记录

| 日期 | 优化项 | 说明 |
|:-----|:------|:-----|
| 2026-07-28 | HttpReverseProxyFilter 复用 EventLoopGroup | 从每次请求创建同组 → 全局共享一组 EventLoopGroup |
| 2026-07-28 | HttpReverseProxyFilter 异步回调 | 使用 ChannelFutureListener 替代 CountDownLatch 阻塞等待 |
| 2026-07-28 | ReverseProxyServerFilter 异步 HttpClient | 使用 sendAsync 替代同步 send，虚拟线程池 |
| 2026-07-28 | ReverseProxyServerFilter HTTP/1.1 | 明确指定 HTTP/1.1 版本，避免 HTTP/2 升级开销 |
| 2026-07-28 | 无连接池化 | 每次请求独立 TCP 连接，避免代理层成为瓶颈 |