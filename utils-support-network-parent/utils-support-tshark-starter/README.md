# utils-support-tshark-starter

TShark 网络数据包捕获与解析模块。支持**离线抓包文件**与**实时网卡采集**两条链路，
并提供从**单包还原**到**TCP 流重组**再到**会话聚合**的三级处理。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-tshark-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

无需手工安装 tshark：找不到时会按三级策略自动装配（见下文）。

---

## tshark 自动装配

`TsharkCliProvider` 按顺序尝试三级，命中即止：

| 级别 | 手段 | 覆盖场景 |
|------|------|----------|
| 1 | 显式路径 → `TSHARK_BIN` → PATH → 常见安装目录 → `where/which` | 运维已规范安装 |
| 2 | 系统包管理器（winget / choco / brew / apt / dnf / yum / apk）安装 `wireshark` | 允许安装软件的传统环境 |
| 3 | 从可配置镜像下载官方制品，装到 `~/.chua/tshark/<版本>` | 容器、受限终端、无管理员权限 |

三级全失败时返回带**人工安装指引**的 `TsharkProvisioningReport`，
而不是抛一句"找不到 tshark"：

```java
TsharkProvisioningReport report = TsharkCliProvider.getInstance()
        .provision(TsharkCliConfig.defaults());
if (!report.available()) {
    log.warn(report.describe());   // 含逐级过程与人工指引
}
```

### 第 3 级的平台差异

官方在 Windows / macOS 只发布安装包，不发布免安装压缩包，因此这两类平台走"静默安装"：

- **Windows**：优先官方安装包（标准 NSIS，`/S /D=<目录>`），
  次选 PortableApps 封装包（内嵌安装器的启动壳，目录参数需用 `/DESTINATION=`，
  且无桌面会话时不可靠）。
  安装目录默认 `~/.chua/tshark` **不含空格**——NSIS 按原始命令行解析 `/D=`，
  路径含空格会导致静默安装失败。
- **macOS**：下载 DMG，挂载后拷出可执行文件并卸载。
- **类 Unix**：官方不发布二进制包，一律走包管理器；
  仅当显式配置了下载地址时才走下载。

> Windows 实时抓包还需 Npcap 驱动（<https://npcap.com/>）。
> 缺少驱动时离线 pcap 解析仍可用，实时采集会失败。

### 配置项

查找顺序为 **系统属性 → 环境变量 → `DirectoryPollerEnvironment` 属性 → 默认值**。
配置键 `tshark.xxx.yyy` 对应环境变量 `TSHARK_XXX_YYY`。

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `tshark.binary` | 空 | tshark 可执行文件路径 |
| `tshark.autoInstall` | `true` | 找不到时是否自动装配 |
| `tshark.install.dir` | `~/.chua/tshark` | 便携版安装根目录 |
| `tshark.download.version` | `4.6.9` | 要安装的 Wireshark 版本 |
| `tshark.download.url` | 空 | 完整下载地址，指定后忽略版本与镜像 |
| `tshark.download.mirrors` | 官方镜像列表 | 镜像基址，逗号分隔，按顺序尝试 |
| `tshark.download.sha256` | 空 | 制品期望 SHA-256，为空则跳过校验 |
| `tshark.packageManager` | 空 | 强制指定包管理器 |
| `tshark.stages` | `locate,packageManager,download` | 装配级别与顺序，可裁剪或重排 |
| `tshark.execTimeout.seconds` | `120` | 单次命令执行超时 |
| `tshark.versionTimeout.seconds` | `10` | 版本探测超时 |
| `tshark.downloadTimeout.seconds` | `900` | 单个下载制品超时 |
| `tshark.installTimeout.seconds` | `600` | 解压或静默安装超时 |
| `tshark.readFilter` | 空 | 离线读 pcap 时的显示过滤器 |

**所有阻塞操作都有上界**，不依赖包管理器、也不手工下载时可通过上表调大各级超时。

### 裁剪装配级别

三级默认全开。已配好内网镜像时可跳过包管理器——包管理器安装通常需要管理员权限，
在受限环境下会耗时数十秒才失败（实测 winget 无权限时约 36 秒），
而镜像可达时下载只要几秒：

```bash
-Dtshark.stages=locate,download
```

级别顺序可任意排列，`locate` 会被强制补到首位（后续级别都依赖它定位到可执行文件）。

### 显式配置优先

`setTsharkBinary` / `setCliConfig` 提供的配置**整体优先**于
`DirectoryPollerEnvironment` 中的 `tshark.*` 属性。
只有未显式配置时才用环境属性兜底——否则 `autoInstall(false)` 会被环境默认值
悄悄改回 `true`，在没装 tshark 的机器上触发一次 100MB 下载。

### 复用已装配的 tshark

定位级别除了搜 `PATH` 与常见安装目录，还会回落到本模块自己的安装目录
`~/.chua/tshark/<版本>/`：

- 先看配置指定版本对应的目录；
- 没有命中就遍历该根目录下的各个版本子目录，取版本号最大的一个。

这一步是必需的：`ExecutableLocator` 只搜 `PATH` 和系统安装目录，不含 `~/.chua/tshark`。
少了回落逻辑，本模块装好的 tshark 会被当成"未安装"，
于是**每次进程启动都重新下载上百 MB**。

另外定位成功后不会再单独跑一次版本探测——`tshark --version` 在 Windows 上
加载 DLL 需要数秒，重复探测会让每次装配白白多花几秒。

## Windows 平台实测结论

| 制品 | 静默安装参数 | 实测结果 |
|------|--------------|----------|
| 官方 `Wireshark-x.y.z-x64.exe` | `/S /D=<目录>` | **50 秒成功**，`tshark.exe` 落在目标根目录 |
| PortableApps `.paf.exe` | `/S /D=` 与 `/S /DESTINATION=` | **两次均静默无效**，安装目录零产出 |

因此解析器把官方安装包排在首位，便携版降为次选。
注意 NSIS 的 `/D=` 必须是最后一个参数且不能被引号包裹，
这也是默认安装目录 `~/.chua/tshark` 选在用户目录（不含空格）的原因。

### 重复执行安装器会先卸载既有安装

官方安装包是 NSIS 脚本，检测到既有安装时**会先静默卸载它**，再装到 `/D=` 指定的目录。

实测：把同一份 `4.6.9` 安装包再执行一次（即使 `/D=` 指向另一个目录），
`~/.chua/tshark/4.6.9` 下已装好的那份会被整个删除，目录只剩空壳。

由此有两个必须知道的后果：

- **跨目录互删**：手动用同一安装包装到别处，会把本模块托管目录里的那一份删掉。
  定位级别虽然会回落到托管目录，但目录已被删除，只能重新下载。
- **升级窗口期风险（推论）**：安装顺序是「先卸载旧版、再安装新版」。
  若新版安装中途失败，旧版此时已经没了——`removeEmptyTargetDir` 只清理空目录，
  救不了这种情况。

因此**不要在已有可用 tshark 的机器上随意重跑下载级别或手工执行安装包**。
需要换目录、换版本时，先确认原位置那份是否还需要保留。

包管理器侧的包名各不相同，不能用一个统一 ID：
`apt/dnf/yum` 用 `tshark`（Debian 系把 Wireshark 拆包），
winget 必须用完整标识 `WiresharkFoundation.Wireshark`——
短名 `wireshark` 会解析到官方的 **MSI 安装包**（依赖 Npcap、需管理员权限），
而不是免驱动的命令行工具。

---

## 实时网卡采集

`TsharkCapturePolledDirectory` 实现 `PolledDirectory`：
数据源虽不是文件系统而是外部进程输出流，但同样符合"数据源变更 → 解析 → 分发事件"的形状，
因此复用既有的生命周期与监听器约定。

```java
CaptureOptions options = CaptureOptions.builder()
        .interfaceId("3")                  // 取自 tshark -D
        .displayFilter("http or mysql")    // 协议级过滤
        .captureFilter("tcp port 3306")    // 抓包级过滤
        .build();

TsharkCapturePolledDirectory session = new TsharkCapturePolledDirectory(options);
session.addCaptureListener(new CaptureListener() {
    @Override
    public void onPacket(PacketRecord packet) {
        log.info("{} -> {}", packet.info(), packet.restoredText());
    }

    @Override
    public void onReassembled(ReassembledMessage message, String restored) {
        log.info("重组出完整消息: {}", restored);
    }

    @Override
    public void onExchange(ProtocolExchange exchange) {
        log.info("请求响应: {} 耗时 {}ms", exchange.requestText(), exchange.latencyMillis());
    }
});
session.start(env);
// ...
session.pause();      // 暂停投递，进程继续抓包
session.resume();
session.stop();
```

实际执行的命令是 `tshark -i <网卡> -T json -x -l`：

- `-T json` 保持与离线解析完全一致的层结构，30 个协议还原器**一行都不用改**；
- `-x` 附带十六进制转储，还原器据此从载荷字节解析协议头；
- `-l` 逐包刷新标准输出，**实时抓包必需**，否则输出被块缓冲，读侧收不到数据。

### 采集配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `capture.interface` | `1` | 网卡编号 |
| `capture.displayFilter` | 空 | tshark 显示过滤器 |
| `capture.filter` | 空 | BPF 抓包过滤器 |
| `capture.snapLen` | `0` | 每包捕获字节上限，0 为不限制 |
| `capture.promiscuous` | `true` | 混杂模式 |
| `capture.bufferMb` | `32` | 抓包缓冲大小 |
| `capture.ringFile` / `capture.ringFileCount` / `capture.ringFileSeconds` | 空 / 0 / 0 | 环形缓冲落盘 |
| `capture.durationSeconds` | `0` | 采集时长上限，0 为不限 |
| `capture.maxPackets` | `0` | 最多采集包数，0 为不限 |
| `capture.pollTimeoutMillis` | `200` | 读循环单次等待，决定暂停/停止的响应延迟 |
| `capture.idleTimeoutMillis` | `120000` | 连续无报文上限，超时置 `FAILED`；0 为不检测 |
| `capture.stopGraceMillis` | `5000` | 停止时先温和终止、超时再强杀的等待时长 |

---

## 离线抓包文件

`TsharkPolledDirectory` 监听目录中的 `.pcap` / `.pcapng`，文件新增或修改时自动解析：

```java
TsharkPolledDirectory poller = new TsharkPolledDirectory("/var/captures");
poller.setPacketListener(records -> records.forEach(r -> log.info("{}", r.info())));
poller.start(env);
```

`start()` 会把目录中已有文件写入初始快照，因此默认**只处理增量**。
把目录指向一个已积累大量 pcap 的历史目录时，可开启"处理已有文件"：

```java
poller.setProcessExisting(true);
```

开启后首次 `upgrade()` 会把已有但未投递过的文件当作新文件处理一次，之后不再重复。
无论解析成功与否都记为已投递，避免一个损坏的文件在每轮轮询里被反复重试、
把 tshark 拉起来一次又一次。

相对早期实现的三处修正：

1. **自动装配 tshark**（原先只认 PATH，找不到就抛异常）；
2. **流式解析**（原先把全部输出读进内存再解析，峰值约为 JSON 文本的两倍）；
3. **订阅过滤**（原先无视 `environment` 的事件订阅，只订阅 `DELETE` 也会收到 `CREATE`）。

---

## 三级数据处理

| 层级 | 入口 | 产出 |
|------|------|------|
| 单包还原 | `PacketParserService.parse / parseNode / parseAll` | `PacketRecord`：五元组、协议、摘要、TCP 生命周期、单包还原文本 |
| TCP 流重组 | `TcpStreamAssembler.accept` | `ReassembledMessage`：跨多个 TCP 段的**完整**应用层字节 |
| 会话聚合 | `SessionAggregator.accept` | `PacketConversation` / `ProtocolExchange`：按对端归并的会话与请求响应对 |

### 内存与背压

抓包是无界的，三层各有上界且都可配，超限都会**计数暴露**而非静默丢弃：

- `TcpStreamAssembler`：单方向待拼字节上限、并发流上限、空洞等待上限。
  序列号按 32 位**无符号**语义比较，跨 `2^31` 回绕不会误判为乱序。
- `SessionAggregator`：并发会话上限、每会话明细与请求响应对上限。
- `PacketParserService.StreamReader`：解析在后台线程完成，调用方在**有界队列**上等待，
  超时精确可控；队列满时丢弃最旧报文并计数。

> 解析**不能**用"`InputStream.available()` 判断还有没有数据"：
> JSON 游标会主动预读并把字节读进自己的缓冲区，底层流已无字节可读而游标内部仍攥着完整报文，
> 会周期性地把"有数据"误判成"没数据"。因此改为后台线程 + 有界队列。

### 方向与请求响应配对

客户端使用临时端口（通常远大于服务端），服务端是知名端口（80/3306/6379 等）。
因此缺少握手信息时**不能**按"端口较小的一侧是客户端"判定——那会把 HTTP 这类流量整个判反。
实现先看是否存在知名端口：存在则知名端口一侧为服务端。

---

## 功能概览

| 类 | 说明 |
|----|------|
| `TsharkCliProvider` | tshark 三级自动装配（定位 → 包管理器 → 下载安装） |
| `TsharkCliConfig` / `TsharkSettings` | 装配与采集配置，含各级超时 |
| `TsharkArtifact` / `TsharkArtifactResolver` / `TsharkArtifactInstaller` | 下载制品的描述、地址拼装与落盘 |
| `TsharkProvisioningReport` | 装配结果，含逐级过程与人工安装指引 |
| `TsharkCapturePolledDirectory` | 实时网卡采集会话（实现 `PolledDirectory`） |
| `CaptureOptions` / `CaptureListener` / `CaptureInterface` | 采集参数、回调、网卡枚举 |
| `CaptureSessionState` / `CaptureStatistics` | 会话状态机与统计计数 |
| `TsharkPolledDirectory` | 离线抓包文件轮询目录 |
| `PacketParserService` / `PacketRecord` / `PacketMeta` / `TsharkFields` | 单包解析、流式/增量读取、字段取值 |
| `TcpStreamAssembler` / `TcpStreamKey` / `ReassembledMessage` | TCP 流重组 |
| `SessionAggregator` / `PacketConversation` / `ProtocolExchange` | 会话聚合与请求响应配对 |
| `restorer/*`（30 个） | 协议还原器 SPI 实现 |

---

## 依赖关系

```
utils-support-tshark-starter
└── utils-support-common-starter
    ├── lang.cmd          CliTool / ExecutableLocator / PackageManager（工具定位与安装）
    └── network.download   Downloader / Extractor（制品下载与解压）
```

---

## 验证

本模块无 JUnit 依赖，按项目约定以 `main` 方法直接运行。

### 1. 解析与重组契约（合成报文，无需 tshark）

```bash
mvn -f utils-support-tshark-starter/pom.xml -o test-compile
java -cp "target/classes;target/test-classes;<依赖 classpath>" \
     com.chua.network.support.tshark.TsharkRestorerSmokeTest
```

覆盖：单包还原契约、层选择、流式与增量解析、元数据提取、
ISO-8601 与小数秒两种时间戳形态、TCP 流号与网卡名提取、
乱序/重传/序列号回绕下的 TCP 重组、请求响应配对、配置查找与超时上界。
全部通过输出 `PASS`。

### 2. 端到端集成（需要真实 tshark 与抓包驱动）

```bash
java -cp "target/classes;target/test-classes;<依赖 classpath>" \
     com.chua.network.support.tshark.TsharkCaptureIntegrationTest [tshark路径]
```

覆盖只有真实 tshark 才能验证的部分：命令拼装是否被 tshark 接受、
`-T json -x -l` 的流式输出能否被增量解析、协议还原器在真实层结构上是否命中、
TCP 重组与会话聚合在真实流量上是否产出结果，以及离线 pcap 链路。

不传 `tshark路径` 时按三级策略自动装配；tshark 不可用时输出 `SKIP` 并以退出码 0 结束
（不视为失败）。实时采集依赖抓包驱动（Windows 为 Npcap），驱动缺失时该项输出 `SKIP`。

实测结果（Windows 11 + tshark 4.6.9 + Npcap，回环网卡，持续产生流量）：

```text
packets≈290, bytes≈390000, conversations≈45, reassembled≈100
droppedByTshark=0, droppedByParser=0, droppedByAssembler=0, droppedByAggregator=0
pass=23, fail=0, skip=0
```

期间识别出的真实协议包括 `TLS`、`MYSQL`、`HTTP`、`TCP`。

> 采集量必须设上界：繁忙网卡的回环在 4 秒内可产生 39MB 流量，
> 不加 `snapLength` 与 `maxPackets` 会让解析线程长时间无法排空队列。

> 连续跑多轮时两轮之间要留出数秒间隔：抓包驱动同一时刻只允许一个会话占用某张网卡，
> 上一轮的 tshark 尚未释放时下一轮会打不开网卡。此时本模块会把 tshark 的标准错误
> 连同退出码一起抛出（`FAILED` 状态），而不是表现为"采集中但零包"。

