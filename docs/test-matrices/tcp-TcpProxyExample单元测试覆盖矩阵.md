# TcpProxyExample 单元测试覆盖矩阵

## 版本信息
- 类名：TcpProxyExample
- 模块：utils-support-example-starter
- 包路径：com.chua.example.network.proxy.tcp
- 作者：CH
- 更新日期：2026-07-28

## 代理实现覆盖矩阵

| 代理类型 | 别名 | 底层 Filter | 模块 | TCP 代理 | 双向转发 | 启动/停止 | 压测 | 状态 |
|:--------|:-----|:-----------|:-----|:---------|:---------|:---------|:----|:----|
| tcp-proxy | tcp | TcpProxyServerFilter | utils-support-network-starter | ✅ | ✅ | ✅ | ✅ | 通过 |

## 压测结果（wrk, 30s, 4 threads）

> TCP 代理层 → 后端 echo server，wrk 通过 HTTP 协议测试 TCP 通道吞吐。

| 代理类型 | 监听端口 | 后端端口 | 100 conns | 1000 conns | 2000 conns | 5000 conns |
|:--------|:--------|:--------|:----------|:-----------|:-----------|:-----------|
| tcp-proxy | 8300 | 8301 | — | — | — | — |

> 注：压测结果由 docker-compose.benchmark.yml 在 172.16.0.40 上运行后填充。

## 优化记录

| 日期 | 优化项 | 说明 |
|:-----|:------|:-----|
| 2026-07-28 | 虚拟线程代理 | 使用 Thread.ofVirtual() 替代 newCachedThreadPool，每个连接一个虚拟线程 |
| 2026-07-28 | 连接计数 | 新增 activeConnections 指标，监控并发连接数 |
| 2026-07-28 | SO_TIMEOUT | 客户端 socket 也设置读超时，避免僵尸连接 |
| 2026-07-28 | 默认 backlog | 默认 backlog 128，提高连接排队能力 |
| 2026-07-28 | 线程池优化 | 从 CachedThreadPool → VirtualThreadPerTaskExecutor，消除线程创建开销 |