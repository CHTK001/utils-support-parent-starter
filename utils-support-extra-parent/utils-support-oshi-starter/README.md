# utils-support-oshi-starter

> OSHI 系统硬件信息工具模块：以静态门面 `Oshi` 采集**本机** CPU、内存、文件系统、网络接口与系统信息，附带 20 个数据模型和 `oshc` 命令行仪表盘。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承根 pom：`maven.compiler.release=25` + `--enable-preview`）｜包名：`com.chua.oshi.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 数据模型](#4-数据模型)
- [5. oshc 命令行](#5-oshc-命令行)
- [6. 配置项](#6-配置项)
- [7. SPI 扩展点](#7-spi-扩展点)
- [8. 构建与依赖](#8-构建与依赖)
- [9. 边界：这些能力不在本模块](#9-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-oshi-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父模块：`utils-support-extra-parent`（`4.0.0.51`）→ 根模块 `utils-support-parent-starter`。
pom 自述：**「OSHI 系统硬件信息工具模块：CPU、内存、磁盘、网络等本机指标采集」**。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 采集门面 | 静态工具类 `Oshi`：5 个 `new*` 即时采集方法 + 3 个 OSHI 原生句柄 getter | 不是 Spring starter：pom 无任何 Spring 依赖，无自动配置类 |
| 数据模型 | 20 个 Lombok `@Data` 模型 + 1 个 record（`SensorInfo`），已配 GraalVM 反射注册 | 不做历史存储 / 趋势 / 告警：所有方法都是「当前时刻快照」 |
| 命令行 | 内置 `oshc` CLI（`OshiCli`）：`status` / `cpu` / `mem` / `disk` / `net` / `sys` 六个命令 | 不支持远程主机：仅采集 JVM 所在本机（`new SystemInfo()` 无目标参数） |
| 依赖 | 只依赖 `utils-support-common-starter` 与 `com.github.oshi:oshi-core:6.8.0` | 不重造 OSHI API：深度定制直接走 `Oshi.getHardware()` / `getOperatingSystem()` 原生句柄 |

**判断口径**：查本机硬件/系统指标 → 先用 `Oshi.new*`；`Oshi` 没有的采集项（如 GPU、传感器明细、进程列表）→ 用原生句柄自己取，不要在本模块外加一层重复封装。

### 1.3 规模与包结构

共 **30** 个 Java 源文件（当前无 `src/test`）：

```text
com.chua.oshi.support                 （21 个）
├── Oshi                              静态门面：newCpu/newMem/newSys/newSysFile/newNetwork + 3 个 getter
├── 20 个数据模型                       Cpu/Mem/Sys/SysFile/Network/... （@Data；SensorInfo 为 record）
└── cli                               （9 个）
    ├── OshiCli                       oshc 入口：命令路由 + help/version
    ├── display.Formatter             终端 panel/table/bar 渲染（中文宽度对齐）
    └── command                       AbstractCommand + 6 个命令实现
```

资源文件仅 2 个 GraalVM native-image 配置：`reflect-config.json`、`resource-config.json`（见 [第 8 节](#8-构建与依赖)）。

---

## 2. 核心 API 与速查表

唯一门面：`com.chua.oshi.support.Oshi`（`final`，私有构造，全静态方法）。他人最常查的入口如下：

| 需求 | 入口 | 返回 | 说明 |
| --- | --- | --- | --- |
| CPU 使用率 | `Oshi.newCpu(long tickMillis)` | `Cpu` | 两次 `getSystemCpuLoadTicks()` 采样，**方法内 sleep `tickMillis` 毫秒**；得 `user`/`sys`/`wait`/`free`/`used`（0–100）与 `cpuNum`（逻辑核数）；`used = 100 - free` |
| 内存用量 | `Oshi.newMem()` | `Mem` | `total`/`used`/`free`（字节），`usage` 百分比 |
| 系统信息 | `Oshi.newSys()` | `Sys` | `osName`（OSHI 的 `OperatingSystem.toString()`）、`osArch`（`os.arch`）、`computerIp`（首个非环回且 UP 的 IPv4，取不到回落 `InetAddress.getLocalHost()`，再失败为 `127.0.0.1`） |
| 文件系统/挂载点 | `Oshi.newSysFile()` | `List<SysFile>` | 每个挂载点的 `dirName`/`typeName`/`total`/`used`/`free`/`usage` |
| 网络接口 | `Oshi.newNetwork()` | `List<Network>` | 见下方「注意 2」 |
| OSHI 原生句柄 | `Oshi.getSystemInfo()` / `getHardware()` / `getOperatingSystem()` | `oshi.*` | 单例，进程内共享；未被 `Oshi.new*` 覆盖的采集全走这里 |

**注意 1（usage 语义）**：实现按**百分比 0–100** 计算（`used * 100.0 / total`），CLI 也按 0–100 渲染；但 `Mem`、`SysFile` 的 Javadoc 写的是 `[0.0, 1.0]`，与实现不一致，**以实现为准**。

**注意 2（Network 字段填充范围）**：`newNetwork()` 目前只填充 `name`、`displayName`、`mac`、`ipv4`、`receiveBytes`、`transmitBytes`、`speed`；`Network` 模型中的其余字段（`ipv6`、`subnetMasks`、`prefixLengths`、`ifAlias`、`index`、`mtu`、`packetsRecv`、`packetsSent`、`inErrors`、`outErrors`、`inDrops`、`collisions`、`timeStamp`、`ifOperStatus`、`ifType`、`connectorPresent`）当前**没有生产者填充**（源码未提供），需要时用 `Oshi.getHardware().getNetworkIFs()` 自取。

**注意 3（采集失败不抛异常）**：`new*` 方法内部 `catch (Exception)` 后打 `warn` 日志并返回已填部分/零值对象，调用方需自行判断字段有效性。

---

## 3. 快速开始

### 3.1 Java API

```java
import com.chua.oshi.support.Cpu;
import com.chua.oshi.support.Mem;
import com.chua.oshi.support.Network;
import com.chua.oshi.support.Oshi;
import com.chua.oshi.support.Sys;
import com.chua.oshi.support.SysFile;

// CPU：采样间隔 1000ms（注意：方法内会阻塞该时长）
Cpu cpu = Oshi.newCpu(1000);
cpu.getCpuNum();   // 逻辑核心数
cpu.getUsed();     // 总使用率 0-100
cpu.getUser();     // 用户态
cpu.getSys();      // 系统态
cpu.getWait();     // I/O 等待
cpu.getFree();     // 空闲

// 内存 / 系统 / 文件系统 / 网络
Mem mem = Oshi.newMem();
Sys sys = Oshi.newSys();
java.util.List<SysFile> files = Oshi.newSysFile();
java.util.List<Network> nets = Oshi.newNetwork();

// Oshi 未封装的采集：直接用 OSHI 原生句柄
oshi.hardware.HardwareAbstractionLayer hw = Oshi.getHardware();
double cpuTemp = hw.getSensors().getCpuTemperature();          // CPU 温度（摄氏度）
long uptime = Oshi.getOperatingSystem().getSystemUptime();     // 系统运行秒数
```

### 3.2 oshc 命令行

```bash
# Windows 快捷入口（模块根目录的 oshc.bat；不带参数 = status）
utils-support-extra-parent\utils-support-oshi-starter\oshc.bat status
utils-support-extra-parent\utils-support-oshi-starter\oshc.bat cpu
utils-support-extra-parent\utils-support-oshi-starter\oshc.bat net --up
utils-support-extra-parent\utils-support-oshi-starter\oshc.bat sys --services

# 任意平台的等价 Maven 方式（单行）
mvn -q exec:java -f pom.xml -pl utils-support-extra-parent/utils-support-oshi-starter -Dexec.mainClass=com.chua.oshi.support.cli.OshiCli -Dexec.args=status
```

`oshc.bat` 内部即上面的 `mvn exec:java`（`-f` 指向仓库根 pom，`-pl` 指向本模块）；`OshiCli.main` 无参时也默认执行 `status`。

---

## 4. 数据模型

全部位于 `com.chua.oshi.support`，除 `SensorInfo`（record）外均为 Lombok `@Data` POJO。**注意**：`Oshi` 仅为下表前 5 个模型提供 `new*` 工厂；其余 15 个模型只有数据结构、没有内置采集方法（源码未提供），需要时通过原生句柄自行填充。

| 模型 | 由谁生产 | 字段（均与源码一致） |
| --- | --- | --- |
| `Cpu` | `Oshi.newCpu` | `user` `sys` `wait` `free` `used`（0–100）、`cpuNum` |
| `Mem` | `Oshi.newMem` | `total` `used` `free`（字节）、`usage` |
| `Sys` | `Oshi.newSys` | `computerIp` `osName` `osArch` |
| `SysFile` | `Oshi.newSysFile` | `dirName` `typeName` `total` `used` `free` `usage` |
| `Network` | `Oshi.newNetwork` | `name` `displayName` `mac` `ipv4[]` `ipv6[]` `subnetMasks[]` `prefixLengths[]` `ifAlias` `index` `mtu` `receiveBytes` `transmitBytes` `packetsRecv` `packetsSent` `inErrors` `outErrors` `inDrops` `collisions` `speed` `timeStamp` `ifOperStatus` `ifType` `connectorPresent` |
| `VirtualMemory` | 无（仅模型） | `swapTotal` `swapUsed` `swapFree` `pagefileTotal` `pagefileUsed` `pagefileFree` `swapUsage` `pagefileUsage` |
| `PhysicalMemory` | 无（仅模型） | `capacity` `memoryType` `clockSpeed` `manufacturer` `manufacturerId` `deviceLocator` `serialNumber` `configuredClockSpeed` `minVoltage` `maxVoltage` |
| `Disk` | 无（仅模型） | `controllerType` `model` `serial` `size` `transferSpeed` `smartStatus` `solidStateDrive` `rotationSpeed` |
| `HWPartition` | 无（仅模型） | `name` `device` `type` `offset` `size` `parentName` |
| `LogicalVolumeGroup` | 无（仅模型） | `name` `physicalVolumes[]` `totalSize` `freeSpace` |
| `ComputerSystem` | 无（仅模型） | `manufacturer` `model` `uuid` `serialNumber` |
| `Baseboard` | 无（仅模型） | `manufacturer` `model` `version` `serialNumber` |
| `Firmware` | 无（仅模型） | `vendor` `name` `version` `releaseDate` `biosSize` |
| `Display` | 无（仅模型） | `name` `vendor` `serialNumber` `width` `height` `bitDepth` `refreshRate` |
| `Gpu` | 无（仅模型） | `name` `vendor` `driverVersion` `vram` `gpuUsage` `temperature` |
| `SoundCard` | 无（仅模型） | `name` `vendor` `driverVersion` |
| `Sensor` | 无（仅模型） | `name` `currentTemperature` `maxTemperature` `currentFanSpeed` `maxFanSpeed` `currentVoltage` `type` |
| `SensorInfo` | 无（仅模型，record） | `name` `type` `currentTemperature` `maxTemperature` `currentFanSpeed` `maxFanSpeed` `currentVoltage` |
| `ProcessInfo` | 无（仅模型） | `pid` `parentPid` `name` `user` `cpuUsage` `memUsage` `upTime` `state` `commandLine` |
| `Battery` | 无（仅模型） | `name` `capacity` `currentCapacity` `maxCapacity` `state` `timeRemaining` |

---

## 5. oshc 命令行

入口 `com.chua.oshi.support.cli.OshiCli`（程序名 `oshc`，版本 `1.0.0`）。参数解析用 `com.chua.common.support.utils.CommandLine.parse(...)`。

| 命令 | 实现类 | 输出内容 |
| --- | --- | --- |
| `status`（默认） | `StatusCommand` | 全局仪表盘：系统面板（OS/Arch/Host/Serial/IP）、CPU 使用率进度条、内存、Swap、物理内存条数、磁盘分区表、物理磁盘表、网络接口表、传感器（CPU 温度/风扇/电压）、电源 |
| `cpu` | `CpuCommand` | CPU 型号/物理与逻辑核数/当前与最大频率/厂商/Family/Model/Stepping、每核使用率表、1/5/15 分钟负载、上下文切换与中断数、Top10 进程（按 CPU，PID/Name/CPU/RSS） |
| `mem` | `MemCommand` | 物理内存面板（Total/Used/Free/Usage/Available）、内存条表格（Bank/Part Number/Type/Capacity/Speed/Manufacturer）、Swap 面板（含 pages in/out）、Top10 进程（按 RSS，PID/Name/RSS/VSZ） |
| `disk` | `DiskCommand` | 物理磁盘表（Model/Serial/Size/Partitions/State）、分区表（ID/Disk/Mount/Size/Type）、文件系统用量表（Mount/Type/Total/Used/Free/Usage/Bar）+ 汇总面板 |
| `net` | `NetworkCommand` | 网络接口表（Name/Display/IPv4/MAC/RX/TX/Speed/Status）；`--up` 只显示 `ifOperStatus == UP` 的接口 |
| `sys` | `SysCommand` | 系统面板（OS/Arch/IP/Manufacturer/Model/Serial/UUID）、Firmware（BIOS）面板、Baseboard 面板、CPU 摘要、内存摘要、进程状态统计（Running/Sleeping/Stopped/Zombie/Other）、Uptime 与 Boot Time、显示器分辨率（解析 EDID）、声卡、电源；`--services` 额外列运行中服务（最多 100 条，PID/Name/State） |
| `version` | — | 打印 `oshc 1.0.0` |
| `help` | — | 命令列表与全局 flag（`--help` / `--up` / `--services`） |

- 无参运行等价于 `status`；未知命令打印 `Unknown command: xxx` 并抛 `IllegalStateException`。
- 输出渲染由 `com.chua.oshi.support.cli.display.Formatter` 完成，公共静态方法可复用：
  - `Formatter.panel(String title, String... kvPairs)`：键值面板（键值交替传入）；
  - `Formatter.table(String title, String[] headers, List<String[]> rows)` 及带 `String[] summary` 汇总行的重载：表格；
  - `Formatter.bar(double pct, int length)`：`█`/`░` 进度条；
  - `Formatter.formatBytes(long)`：B/KB/MB/GB 自适应；
  - 内部按码点计宽（`> 0x2E80` 记 2 列），中文对齐不跑偏。
- 新命令需继承 `com.chua.oshi.support.cli.command.AbstractCommand`（抽象方法 `name()` / `description()` / `execute(CommandLine)`），但 `OshiCli` 的命令表 `COMMANDS` 为私有常量、无运行期注册入口（源码未提供），新增命令需修改该数组。

---

## 6. 配置项

**无。**

依据：本模块 `src/main/resources` 下仅有 `META-INF/native-image/com.chua/utils-support-oshi-starter/` 的 `reflect-config.json` 与 `resource-config.json` 两个文件；没有 `application*.yml` / `*.properties`，没有 `@ConfigurationProperties`，pom 中也没有 Spring 相关依赖。可调参数只有方法入参（如 `Oshi.newCpu(long tickMillis)` 的采样间隔）与 CLI 参数（`--up`、`--services`）。

---

## 7. SPI 扩展点

**无。**

依据：`src/main/resources` 下没有 `META-INF/extensions` 目录，模块内也没有定义任何 SPI 接口或 `@Spi`/`@Extension` 注解使用。本模块是「被依赖的工具库」，不是「被扩展的框架」：

- 想扩展采集能力 → 直接使用 `Oshi.getHardware()` / `Oshi.getOperatingSystem()` 的 OSHI 原生 API，在自己的模块里实现；
- 想扩展 CLI 命令 → 继承 `AbstractCommand`（限制见第 5 节末条）；
- GraalVM 反射配置见第 8 节，新增模型类时需同步维护 `reflect-config.json`。

---

## 8. 构建与依赖

### 8.1 依赖清单（pom 实际声明，仅 2 个）

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（`4.0.0.51`） | `Wraps`（集合创建）、`StringUtils`、`CommandLine`（CLI 参数解析） |
| `com.github.oshi:oshi-core` | `6.8.0`（根 pom `dependencyManagement` 统一管理） | 全部硬件/系统采集的底层实现 |

> 根 pom 注释明确：`oshi-core → 7` 因「驱动 API 不兼容」列入高风险跨 major 项，暂保留 `6.8.0` 不升级。

### 8.2 构建与运行

```bash
# 编译（仓库标准闸门口径）
mvn -o -q compile -DskipTests

# 运行 oshc（exec-maven-plugin 3.6.4，mainClass 已固定为 com.chua.oshi.support.cli.OshiCli）
mvn exec:java -pl utils-support-extra-parent/utils-support-oshi-starter                 # 默认 status
mvn exec:java -pl utils-support-extra-parent/utils-support-oshi-starter -Dexec.args=cpu # 指定命令
```

JDK：`25`（根 pom `maven.compiler.release=25`，编译参数含 `--enable-preview`）。

### 8.3 GraalVM native-image

- `reflect-config.json`：注册本模块全部 21 个类（`Oshi` + 20 个模型）的构造器/方法/字段，以及 OSHI 的 7 个类型（`oshi.SystemInfo`、`oshi.hardware.CentralProcessor`、`oshi.hardware.GlobalMemory`、`oshi.hardware.HardwareAbstractionLayer`、`oshi.hardware.NetworkIF`、`oshi.software.os.OSFileStore`、`oshi.software.os.OperatingSystem`）。
- `resource-config.json`：包含 `META-INF/services/.*`。

### 8.4 仓库内被依赖情况

- `spring-support-report-client-starter`：pom 中直接声明依赖本模块；
- `spring-support-api-starter`、`spring-support-parent-starter`：pom 中也引用了 `utils-support-oshi-starter`（版本管理处）。

---

## 9. 边界：这些能力不在本模块

| 需求 | 现状 | 应去哪里 |
| --- | --- | --- |
| 指标存储、时序化、上报 | 本模块只产即时快照对象，不落库、不外发 | `utils-support-metrics-starter` 等指标模块 |
| 历史趋势、阈值告警、通知 | 无（源码未提供） | 自行在上层基于 `Oshi.new*` 轮询实现 |
| 远程主机 / 多节点采集 | 无；`new SystemInfo()` 仅本机 | 需远程采集的在上层走网络协议，本模块不提供 |
| GPU 利用率/温度、进程列表等深采集的现成方法 | `Gpu`、`ProcessInfo` 等只是数据模型，`Oshi` 无对应工厂 | `Oshi.getHardware()` / `Oshi.getOperatingSystem()` 原生 API |
| Windows/Linux/macOS 差异的补偿逻辑 | 不加工；字段可得性由 oshi-core 决定（如 Windows 的 pagefile、部分平台传感器读数为 0） | 以 oshi-core 行为为准 |
| Spring 自动配置 / actuator endpoint / Micrometer 绑定 | 无；虽名带 starter，实为纯工具库 | 上层自行封装 |
