# 优化路线图：让 Java Server 逼近 Go 的百万并发低延迟

**目标定位**：不是"写一个 Go 替换 Java"，而是基于当前 `common-starter` 的 `Server` SPI 体系，把单节点能力推到 Go 同等水平（10万~百万级并发连接、亚毫秒~低毫秒延迟）。

## 当前基线

- java 25.0.4.1 LTS (Temurin) — 虚拟线程、handlebara 容器、Preview (record patterns/switch/unlimit)
- `NioHttpServer`: Selector 分片 + 虚拟线程 worker + 内联 fast path
- `AioHttpServer`: Proactor/IOCP + 虚拟线程 worker
- `JdkHttpServer`: jdk HttpServer + 虚拟线程
- 没有测试网络 (没下载 junit-platform-launcher/surefire deps)，本机用自定义 JUnit 6.0.3 runner + 手动温度平衡测试

## 优化方向（按收益排序）

### A. 性能关键（能追上 Go）
1. **连接数门限 + 背压门限** — 当前 `ServerSetting.maxConnections` 只在 `AbstractProxyServer` 里起效，HTTP@NIO/AIO 里面没有连接计数门限，不对
2. **减少每请求分配** — 把每请求对象（`ServerMetrics` increment、垃圾 `ECHO_HEADER_CACHE`、过滤器链）减少
3. **更低的延迟：inline dispatch（事件循环直接执行 handler，不做 fake thread）** — 已经在 NIO 里实现，AIO 里没有等效 inline
4. **更低的 stack 膨胀：虚拟线程 vs goroutine** — JDK 25 的虚拟线程可以比 goroutine 更轻松地扩展到 100 万线程
5. **零拷贝 I/O** — `transferTo` + NIO gather into...
6. **TLS 优化** — 使用 SSLEngine 的批量 wrap/unwrap; 输入
7. **算力和监听** — SO_REUSEPORT、epoll level triggered

### B. 架构优化
- 去掉 javassist/servlet 依赖；Servlet 能力放在 spi 扩展里
- 多层（Jetty）通过 SPI 注册为扩展，不塞主包

### C. 测试验证
- 基准测试：10万连接回显，看延迟分布
- 本机 Windows 11 测试
