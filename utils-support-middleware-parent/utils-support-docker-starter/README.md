# utils-support-docker-starter

> Docker 客户端集成，基于 docker-java SDK：把容器、镜像、网络、卷四类操作与系统信息封装成一套**链式 API**，同步与异步（`CompletableFuture`）双轨。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.docker.support.client`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
  - [2.1 创建客户端](#21-创建客户端)
  - [2.2 容器 ContainerOps](#22-容器-containerops)
  - [2.3 镜像 ImageOps](#23-镜像-imageops)
  - [2.4 网络 NetworkOps](#24-网络-networkops)
  - [2.5 卷 VolumeOps](#25-卷-volumeops)
  - [2.6 系统信息与异常](#26-系统信息与异常)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-docker-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | docker-java SDK 的链式封装门面：容器/镜像/网络/卷 + `ping`/`info`/`version` | 不是 Docker Engine，也不是 Docker CLI 的代理 |
| 形态 | 单一入口类 `DockerClient`（`implements Closeable`）+ `Builder` + 四个 Ops 内部类 | 不是 Spring Boot Starter：无自动配置类、无配置元数据 |
| 同步模型 | 每个 Cmd 均有 `execSync()` 同步与 `exec()` / `execAsync()` 返回 `CompletableFuture` 的异步 | 不返回 Reactor `Mono`（区别于 `utils-support-common-starter` 的 HttpClient） |
| 传输 | 基于 `docker-java-transport-httpclient5`（`ApacheDockerHttpClient`，连接池上限固定 100） | 不依赖 Docker 二进制文件，走 Docker Engine API（默认 API 版本 `1.40`） |

**判断口径**：需要以编程方式操控 Docker（建容器、拉镜像、组网络、挂数据卷）就用本模块；要编排多服务（Compose/Swarm 语义）、构建镜像或采集事件/统计，请看第 7 章边界。

### 1.3 规模

- 源码仅一个类：`com.chua.docker.support.client.DockerClient`（约 2490 行，`@since 4.0.0.42`），四类操作以静态内部类组织。
- 资源仅一份 GraalVM `native-image` 反射配置：`META-INF/native-image/com.chua/utils-support-docker-starter/reflect-config.json`（对 `DockerClient` 全量开放反射）。
- 日志走 SLF4J（`@Slf4j`）；集合构建复用 `utils-support-common-starter` 的 `com.chua.common.support.collection.wrap.Wraps`。

---

## 2. 核心 API 与速查表

### 2.0 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 创建客户端 | `DockerClient.builder().tcp(host, port).build()` | 也可 `DockerClient.create(host)` 一行创建 |
| 容器全生命周期 | `docker.container().create(...)` → `start` / `stop` / `restart` / `remove` / `kill` | 链式配置，`exec()` / `execSync()` 执行 |
| 查询容器 | `docker.container().list().all(true).execSync()` | 返回 `List<Container>`，支持 `filter(String...)` 名称过滤 |
| 容器日志与命令 | `docker.container().logs(id).tail(100).execSync()`；`docker.container().exec(id).command("sh", "-c", "...").execSync()` | 均返回 `String` |
| 拉取/查询/删除镜像 | `docker.image().pull("nginx").tag("1.27").exec()` | `pull` 支持 `execAsync(Consumer<String> onProgress)` 进度回调 |
| 创建/连接网络 | `docker.network().create("app-net").driver("bridge").exec()`；`docker.network().connect(netId, containerId)` | `exec()` 返回网络 ID |
| 创建/删除卷 | `docker.volume().create("app-data").exec()` | `exec()` 返回卷名 |
| 系统信息 | `docker.ping()` / `docker.info()` / `docker.version()` | `Info` / `Version` 来自 docker-java 模型 |

### 2.1 创建客户端

```java
import com.chua.docker.support.client.DockerClient;

// 方式一：建造器（推荐）
DockerClient docker = DockerClient.builder()
        .tcp("172.16.0.40", 2375)      // 等价 host("tcp://172.16.0.40:2375")
        .connectTimeout(5000)
        .readTimeout(30000)
        .build();

// 方式二：快速创建（默认 unix:///var/run/docker.sock 时传该地址即可）
DockerClient local = DockerClient.create("unix:///var/run/docker.sock");
```

- `DockerClient` 实现了 `Closeable`，建议 `try-with-resources`，`close()` 释放底层 HTTP 客户端。
- `getHost()` 返回主机地址；`getDelegate()` 返回 docker-java 原生 `com.github.dockerjava.api.DockerClient`，可执行本模块未封装的命令（见第 5、7 章）。

### 2.2 容器 ContainerOps

`docker.container()` 提供以下入口（`id` 可为容器 ID 或名称）：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `create(String name)` | `CreateCmd` | 链式配置后 `exec()` 返回容器 ID |
| `start(String id)` | `StartCmd` | `execSync()` 同步启动；`exec()` 异步 |
| `stop(String id)` | `StopCmd` | `timeout(int)` 等待秒数，默认 10 |
| `restart(String id)` | `RestartCmd` | `timeout(int)` 等待秒数，默认 10 |
| `remove(String id)` | `RemoveCmd` | `force(boolean)`、`removeVolumes(boolean)` |
| `list()` | `ListCmd` | `all(boolean)`（默认 false）、`filter(String...)`；`execSync()` 返回 `List<Container>` |
| `inspect(String id)` | `InspectCmd` | `execSync()` 返回 `InspectContainerResponse` |
| `logs(String id)` | `LogsCmd` | `tail(int)`（默认 100），stdout/stderr 均开；`execSync()` 返回日志文本 |
| `exec(String id)` | `ExecCmd` | `command(String...)` 后 `execSync()` 返回命令输出 |
| `kill(String id)` | `KillCmd` | `signal(String)`，默认 `KILL` |
| `wait(String id, long timeoutSeconds)` | `Integer` | 同步等待退出，返回退出码（注意：当前实现直接 `awaitStatusCode()`，`timeoutSeconds` 未参与等待逻辑） |
| `createAsync(String name, Consumer<CreateCmd> config)` | `CompletableFuture<String>` | 回调式创建 |
| `startAsync` / `stopAsync` / `removeAsync(String id)` | `CompletableFuture<Void>` | 异步启停删 |
| `listAsync()` | `CompletableFuture<List<Container>>` | 异步列表 |
| `waitAsync(String id, long timeoutSeconds, Consumer<Long> onExit)` | `CompletableFuture<Void>` | 异步等待，退出码经回调返回（异常时回调 `-1L`） |

`CreateCmd` 可配置项（全部链式返回自身）：

| 方法 | 说明 |
| --- | --- |
| `image(String img)` | 镜像名（必填，如 `nginx:latest`） |
| `port(int hostPort, int containerPort)` | 端口映射；固定绑定 `HostIp=0.0.0.0`、协议 `/tcp` |
| `volume(String host, String container)` | 目录/卷绑定，按 `host:container` 解析为 `Bind` |
| `env(String k, String v)` / `env(Map<String,String>)` | 环境变量（单个/批量） |
| `label(String k, String v)` | 标签 |
| `restart(String p)` | 重启策略：`no`（默认）/ `always` / `on-failure` / `unless-stopped` |
| `cmd(String...)` / `entrypoint(String...)` | 覆盖镜像默认 CMD / ENTRYPOINT |
| `workingDir(String)` / `user(String)` | 工作目录 / 运行用户 |
| `memory(long bytes)` / `cpus(long n)` | 内存限制（字节）/ CPU 核数（内部换算为纳核） |
| `stdinOpen(boolean)` / `tty(boolean)` | 打开标准输入 / 分配 TTY |
| `exec()` | 执行创建，返回容器 ID |

### 2.3 镜像 ImageOps

```java
docker.image().pull("nginx").tag("1.27").exec();              // 同步拉取（默认 tag latest）
docker.image().pull("nginx").tag("1.27")
      .execAsync(status -> log.info("pull: {}", status));      // 异步拉取 + 进度回调
docker.image().pull("nginx").tag("1.27")
      .execAsyncFuture(onProgress, onError);                   // 异步拉取 + 进度/错误回调，返回 CompletableFuture<Void>

docker.image().list().exec();                                 // List<Image>
docker.image().listAsync();                                   // CompletableFuture<List<Image>>
docker.image().inspect("nginx:1.27");                          // InspectImageResponse
docker.image().remove("nginx:1.27").force(false).exec();       // 删除镜像
docker.image().removeAsync("nginx:1.27");                      // 异步删除
```

### 2.4 网络 NetworkOps

```java
String netId = docker.network().create("app-net")
        .driver("bridge")            // 默认 bridge
        .internal(false)             // 是否内部网络
        .option("k", "v")            // 网络选项
        .label("env", "dev")         // 标签
        .exec();                     // 返回网络 ID

docker.network().connect(netId, containerId);      // 容器接入网络
docker.network().list().exec();                    // List<Network>
docker.network().inspect(netId);                   // Network
docker.network().disconnect(netId, containerId);   // 断开
docker.network().remove(netId).exec();             // 删除

docker.network().createAsync("app-net", cmd -> cmd.driver("bridge"));  // 异步创建
docker.network().listAsync();
docker.network().removeAsync(netId);
```

### 2.5 卷 VolumeOps

```java
String name = docker.volume().create("app-data")
        .driver("local")             // 默认 local
        .driverOpt("type", "none")   // 驱动选项
        .label("env", "dev")         // 标签
        .exec();                     // 返回卷名

docker.volume().list().exec();       // ListVolumesResponse
docker.volume().listAsync();
docker.volume().inspect("app-data"); // InspectVolumeResponse
docker.volume().remove("app-data").exec();
docker.volume().removeAsync("app-data");
```

### 2.6 系统信息与异常

```java
docker.ping();        // 探活，返回 Void
docker.info();        // Info：容器数、镜像数、驱动等系统信息
docker.version();     // Version：Docker 版本
```

- `DockerClient.DockerClientException extends RuntimeException`：模块内异常包装类型，构造参数 `(String msg, Throwable cause)`。
- `close()` 失败会抛出 `RuntimeException`（消息前缀「Docker 客户端关闭失败」）。

---

## 3. 快速开始

最短可运行示例：连接 Docker → 建容器 → 启动 → 看日志 → 执行命令 → 停止删除。

```java
import com.chua.docker.support.client.DockerClient;

public class DockerDemo {
    public static void main(String[] args) {
        // 1) 创建客户端（本机默认：DockerClient.create("unix:///var/run/docker.sock")）
        try (DockerClient docker = DockerClient.builder()
                .tcp("172.16.0.40", 2375)
                .connectTimeout(5000)
                .readTimeout(30000)
                .build()) {

            // 2) 系统信息
            docker.ping();
            System.out.println(docker.version());

            // 3) 创建并启动容器
            String id = docker.container().create("my-nginx")
                    .image("nginx:latest")
                    .port(8080, 80)
                    .env("TZ", "Asia/Shanghai")
                    .restart("always")
                    .exec();
            docker.container().start(id).execSync();

            // 4) 日志与容器内命令
            System.out.println(docker.container().logs(id).tail(100).execSync());
            System.out.println(docker.container().exec(id).command("nginx", "-v").execSync());

            // 5) 停止并删除
            docker.container().stop(id).timeout(10).execSync();
            docker.container().remove(id).removeVolumes(true).execSync();
        }
    }
}
```

异步风格（同一套 API）：

```java
docker.container().listAsync()
      .thenAccept(containers -> containers.forEach(c -> System.out.println(c.getNames()[0])));

docker.container().waitAsync(id, 60, exitCode -> System.out.println("exit=" + exitCode));

docker.image().pullAsync("redis", "7", status -> System.out.println(status));
```

> 前置条件：目标主机已启动 Docker Engine，并按所用 `host` 暴露 API（`unix:///var/run/docker.sock` 或 `tcp://host:2375`）。`port()` 目前固定映射 TCP 端口并绑定 `0.0.0.0`。

---

## 4. 配置项

本模块**不是 Spring Boot Starter**，`src/main/resources` 下没有 `application.properties` / `application.yml` 相关键，也没有自动配置类（依据：模块 resources 仅含 GraalVM `reflect-config.json`）。所有参数通过 `DockerClient.Builder` 传入：

| Builder 方法 | 默认值 | 说明 |
| --- | --- | --- |
| `host(String h)` | `unix:///var/run/docker.sock` | Docker 主机地址（docker-java `DockerHost`，支持 `unix://` / `tcp://`） |
| `tcp(String host, int port)` | — | 便捷方法，内部拼为 `tcp://host:port` |
| `certPath(String p)` | 未设置（`null`） | TLS 证书路径（走 `withDockerCertPath`） |
| `apiVersion(String v)` | `1.40` | Docker Engine API 版本（走 `withApiVersion`） |
| `connectTimeout(int ms)` | `5000` | 连接超时（毫秒），落到 `ApacheDockerHttpClient` 的 `connectionTimeout` |
| `readTimeout(int ms)` | `30000` | 读取超时（毫秒），落到 `ApacheDockerHttpClient` 的 `responseTimeout` |
| `build()` | — | 构建 `DockerClient` |

固定的内部行为（非配置项，来源均为源码）：

- HTTP 连接池上限固定 `maxConnections(100)`；
- 容器创建时 `port()` 固定写 `HostIp=0.0.0.0`、协议 `/tcp`；
- `StopCmd` / `RestartCmd` 的 `timeout` 默认 `10` 秒；`LogsCmd` 的 `tail` 默认 `100` 且同时采集 stdout 与 stderr；
- `KillCmd` 默认信号 `KILL`；`CreateCmd` 重启策略默认 `no`；`PullImageCmd` 默认 tag `latest`；`CreateNetworkCmd` 默认驱动 `bridge`；`CreateVolumeCmd` 默认驱动 `local`；`ListCmd` 默认 `all=false`。

---

## 5. SPI 扩展点

**无。** 依据：模块源码只有 `com.chua.docker.support.client` 包下的一个类 `DockerClient` 及其内部类；`src/main/resources` 中没有 `META-INF/extensions` 注册文件，也没有 `@Spi` / `@Extension` 注解——本模块是纯门面库，不提供 SPI 扩展点。

两种「扩展」方式：

1. **逃生舱**：`docker.getDelegate()` 拿到 docker-java 原生 `com.github.dockerjava.api.DockerClient`，直接调用其全部命令（含本模块未封装的 `eventsCmd`、`statsCmd`、`buildImageCmd`、`authCmd` 等），例如：

   ```java
   docker.getDelegate().eventsCmd().exec(new EventCallback() { /* ... */ });
   ```

2. **包装**：在自己的代码中组合 `DockerClient` 的 `container()` / `image()` / `network()` / `volume()`，实现更高层语义（如「起一个带健康检查的服务」）。

原生镜像场景已内置 GraalVM 反射配置：`META-INF/native-image/com.chua/utils-support-docker-starter/reflect-config.json` 对 `DockerClient` 开放全部构造器、方法与字段。

---

## 6. 构建与依赖

在仓库根目录编译验证（与全仓交付口径一致）：

```text
mvn -o -q compile -DskipTests          → EXIT=0
mvn -o -q -pl utils-support-middleware-parent/utils-support-docker-starter -am compile -DskipTests
```

直接依赖（`pom.xml`）：

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | 随父 POM（当前 `4.0.0.51`） | 仅用到其 `Wraps` 集合工具与 SLF4J 体系 |
| `com.github.docker-java:docker-java` | `3.7.1`（根 POM `dependencyManagement` 统一管理） | 核心 API 与命令模型 |
| `com.github.docker-java:docker-java-transport-httpclient5` | `3.7.1`（同上） | `ApacheDockerHttpClient` 传输实现 |

传递依赖要点（模块内 `dtrees.txt` 为 `4.0.0.42` 时期的依赖树快照，彼时 docker-java 为 `3.5.1`；当前版本以根 POM `3.7.1` 为准）：`docker-java-core`、`docker-java-transport-jersey`、`docker-java-transport-netty`、`httpclient5`、`jna`、`junixsocket`（Unix Socket 支持）等随包带入；`lombok`、`jetbrains annotations`、`jsr305` 为 `provided`。

父模块：`com.chua:utils-support-middleware-parent`（中间件集成父模块），其上是 `com.chua:utils-support-parent-starter`。

---

## 7. 边界：这些能力不在本模块

以下能力源码均未封装（整份 `DockerClient.java` 已通读核对），需要时通过 `getDelegate()` 使用 docker-java 原生命令，或使用上层模块：

| 不提供 | 说明 |
| --- | --- |
| 镜像构建 | 未封装 Dockerfile 构建（无 `buildImageCmd` 相关 API） |
| 事件与统计 | 未封装 `events` / `stats` 命令，无容器指标采集 |
| 仓库认证 | `Builder` 无 registry 用户名/密码/鉴权配置入口 |
| UDP 等端口协议 | `port()` 固定 `/tcp` 且 `HostIp=0.0.0.0`；UDP、指定宿主 IP 需走原生命令 |
| 编排能力 | 无 Compose / Swarm / Stack 语义，不做多服务编排与调度 |
| 自动装配 | 不是 Spring Boot Starter：无自动配置、无条件装配、不托管客户端生命周期 |
| 资源清理策略 | 不做孤儿容器/卷的回收，也没有内置的清理任务 |
| 其他运行时 | 只面向 Docker Engine API；不覆盖 containerd / podman / Kubernetes |
