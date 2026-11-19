# Utils Support Wechat Restore Starter

[![Java Version](https://img.shields.io/badge/Java-25%2B-blue)](https://openjdk.java.net/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

> 一句话定位：把**本机微信（Windows 4.x，`xwechat_files`）的聊天数据库还原为可读数据文件（CSV / SQL / Excel）**的工具模块——Java 25 FFM 直连 WCDB 原生库（优先），必要时降级为 SQLCipher 纯 JDK 直解、进程内存明文页提取，或编排 [Wechat-Export](https://github.com/minglin2012/Wechat-Export) Python 工具链（兜底）。
>
> - **模块名**：`utils-support-wechat-restore-starter`（父模块 `utils-support-filesystem-parent`，当前版本 `4.0.0.51`，类自 `4.0.0.42` 起提供）
> - **JDK 要求**：Java 25+（native / memory 路径依赖正式版 FFM API）
> - **根包名**：`com.chua.wechat.support.restore`（子包：`sqlcipher`、`jdbc`、`keyscan`、`memory`、`nativebridge`）
> - **SPI 注册名**：`wechat` / `wechat-export`

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界（这些能力不在本模块）](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

### 是什么

| 能力 | 说明 |
|---|---|
| 聊天记录还原 | 将微信 4.x 本机聊天数据库还原为 CSV / SQL / Excel 文件（每个会话/每张表一个文件） |
| `DataRestore` SPI 实现 | 以 SPI 名 `wechat`（或别名 `wechat-export`）接入统一的 `DataRestore` 还原框架 |
| 四条执行路径 | `native`（FFM 直连 WCDB 原生库）→ `sqlcipher`（纯 JDK 直解加密库）→ `memory`（读微信进程内存里的明文页，免密钥）→ `tool`（编排 Wechat-Export Python 工具链）；`auto` 模式按此顺序依次降级 |
| SQLCipher 4 直解 | 不依赖微信运行时与任何外部工具，用 JDK 自带密码学能力把 SQLCipher 4 加密库还原为明文 SQLite（对齐微信 4.x：AES-256-CBC、HMAC-SHA512、PBKDF2-HMAC-SHA512 256000 次、页大小 4096、每页保留 80 字节） |
| 密钥内存扫描 | 从运行中的 `Weixin.exe` 进程内存逐字节穷举出能解开目标库的密钥（免重启微信，需管理员权限，不保证成功） |
| 消息体解码 | 自动识别并解压微信 4.x 用 ZSTD（魔数 `0xFD2FB528`）/ zlib / gzip 压缩存进列里的消息正文 |
| 明文库导出 | 把明文 SQLite 逐表读出，输出 CSV / SQL / Excel；BLOB 列先解压再按文本解码，失败输出十六进制（超 4096 字节仅输出长度占位） |
| 命令行工具 | `WechatRestoreCli` 把上述能力串成一条命令（退出码 0 成功 / 1 还原失败 / 2 参数错误）；注意 `main` 目前是预留空方法，实际执行需调用 `WechatRestoreCli.run(args, System.out, System.err)`（见「方式三」） |

### 不是什么

| 容易误解的点 | 实际情况 |
|---|---|
| 微信在线 SDK | 本模块**不做**登录、收发消息、机器人、公众号等在线能力 |
| 图形界面工具 | 本模块只提供 Java API 与命令行 `WechatRestoreCli`，无 GUI |
| 密钥破解器 | 密钥无法凭空破解：需自行提供，或通过内存扫描 / Hook 捕获（均不保证成功） |
| 写入微信目录 | 全程**只读**还原，不修改、不写入微信数据目录 |
| 跨版本通用还原 | 面向微信 4.x（`xwechat_files` 目录结构）；更旧版本的数据格式源码未提供 |

---

## 核心 API 与速查表

### 常见入口速查表

| # | 你想做什么 | 入口 |
|---|---|---|
| 1 | 一键还原（最短路径） | `DataRestore.create("wechat").restore(new File("E:/微信/xwechat_files"))` |
| 2 | 命令行还原 / 探测 | `com.chua.wechat.support.restore.WechatRestoreCli`（`--list`、`--probe <hex>`、`--mode`、`--key` 等） |
| 3 | 指定输出格式 / 目录 / 模式 | `WechatDataRestore` + `DataRestoreConfig.builder().format(...).outputDir(...).options(...)` |
| 4 | 看看有哪些库、是明文还是加密 | `WechatRestoreCli --list`（或 `SqlCipherDecryptor.detect(...)` / `isPlainSqlite(...)`） |
| 5 | 带密钥直解 SQLCipher 库 | `SqlCipherDecryptor.parseHexKey(...)` → `isKeyValid(...)` → `decrypt(...)` / `decryptToTemp(...)` |
| 6 | 免密钥还原（微信正在运行） | `options.put("mode", "memory")`，走内存明文页路径，产出含 `messages.csv`、`report.html` |
| 7 | 扫描微信进程内存找密钥 | `WechatMemoryKeyScanner.selfTest()` → `scan(...)`（或 CLI `--scan-key`） |
| 8 | 导出明文 SQLite 为 CSV/SQL/Excel | `WechatJdbcExporter.export(dbFile, config)` |

### 核心类一览

| 类 | 职责 |
|---|---|
| `WechatDataRestore` | SPI 还原编排器（`@Spi({"wechat", "wechat-export"})`），四条路径的调度与降级；重写目录源入口，缺省输出目录为 `<源目录>/wechat-restore-out` |
| `WechatRestoreCli` | 命令行参数解析与执行编排（退出码：0 成功 / 1 还原失败 / 2 参数错误）；`main(String[])` 为预留空方法，可执行入口是 `run(args, out, err)` |
| `WechatRestoreDemo` | 用模拟数据演示 CSV / SQL 导出管道的端到端示例 |
| `WechatToolExporter` | `tool` 路径：编排 Wechat-Export（Python + Node.js + WCDB.dll），CSV / EXCEL 用其原生导出，SQL 先导 JSON 中间格式再转脚本 |
| `WechatExportUtils` | 公共工具：文件名清洗、CSV 转义与写入、`session.db` 定位、账号目录推导、SQL 脚本生成（`buildSqlScript`）、JSON 结果解析等 |
| `WechatBlobCodec` | 消息体 blob 解码：按魔数识别 ZSTD / zlib / gzip，解压后按可打印字符占比 ≥ 90% 判定文本；`describe` / `tryDecompressText` / `isZstd` / `sanitize` |
| `sqlcipher.SqlCipherDecryptor` | SQLCipher 4 纯 JDK 直解：`isPlainSqlite` / `keyProbe` / `isKeyValid` / `detect` / `decrypt` / `decryptToTemp` / `parseHexKey`；密钥支持 64 位 hex 或 `x'<64hex><32hex salt>'` 形态（取前 64 位） |
| `sqlcipher.SqlCipherProfile` | 加密参数档案（record），内置候选 `wechat-4`（微信 4.x 实测口径）与 `sqlcipher-4`（同族 1024 页变体） |
| `jdbc.WechatJdbcExporter` | 明文 SQLite → CSV / SQL / Excel；表级过滤 `table.whitelist` / `table.blacklist` / `limit` |
| `keyscan.WechatMemoryKeyScanner` | 密钥内存扫描器：`isSupported` / `selfTest` / `weixinProcesses` / `scan(...)`，返回 `ScanResult`（`found` / `keyHex` / `address` 等） |
| `memory.WechatMemoryAccess` | 基于 FFM 只读调用 kernel32（`OpenProcess` / `VirtualQueryEx` / `ReadProcessMemory`），纯只读、不触碰 `PAGE_GUARD` |
| `memory.WechatMemoryExtractor` | 从全部 `Weixin.exe` 进程的私有内存提取 SQLCipher 留在 pager cache 里的明文页并解析成记录 |
| `memory.WechatMemoryPageParser` | SQLite b-tree 叶子页（`0x0D`）解析为记录，含页级 / 记录级严格校验，过滤伪页 |
| `memory.WechatMemoryAccumulator` | 跨次扫描累积器（`memory_accumulated.tsv`，SOH 分隔），按 `rowid + 列值` 去重，逐进程落盘做检查点 |
| `memory.WechatMemoryRebuilder` | 把提取结果重建成明文 SQLite 库 `wechat_memory.db`（列统一 TEXT），另写 `schema.sql` / `summary.md` |
| `memory.WechatMemoryMessages` | 可读消息视图：`messages.csv`（时间/发送者/正文）、`id_map.csv`（sender_id → 用户名映射）、`report.html`（自包含查看器） |
| `nativebridge.WcdbNativeBridge` | FFM 绑定微信自带 `wcdb_api.dll`（对齐 Wechat-Export 的 C 接口）；`isSupported()`；`wcdb_init` 宿主校验拒绝码 `-1006` |
| `nativebridge.WxKeyNativeBridge` | FFM 绑定 `wx_key.dll` 捕获数据库密钥（Hook 注入 + 轮询），`captureKey(...)`，需管理员并重启微信 |
| `nativebridge.WechatNativeLauncher` | Electron 宿主自举启动器：把 `java.exe` 复制为 `electron.exe` 再重启 JVM 以通过 `wcdb_init` 的进程名校验（系统属性 `wechat.native.host.dir` 可覆盖宿主目录，默认 `${user.home}/.chua-wechat`） |
| `nativebridge.WechatNativeExporter` | `native` 路径导出管线：遍历会话、分页拉消息、解压 ZSTD、解析显示名，按格式输出 |

---

## 快速开始

### 环境要求

- JDK 25+
- Windows + 微信 4.x（`native` / `memory` 路径仅支持 Windows；`memory` 需微信正在运行且建议管理员权限）

### 方式一：Java API 一键还原（最短示例）

```java
// 源是目录（账号目录 / db_storage / xwechat_files 都行），零配置即可跑：
// auto 模式会依次尝试 native → sqlcipher → memory → tool，
// 拿不到密钥时自动落到「内存明文页」路径——不需要密钥、不需要重启微信。
// 目录源缺省输出到 <源目录>/wechat-restore-out
DataRestoreResult result = DataRestore.create("wechat")
        .restore(new File("E:/微信/xwechat_files"));
```

### 方式二：指定输出格式、目录与模式

```java
Map<String, Object> options = new HashMap<>(2);
options.put("mode", "memory");                    // 显式走内存明文页路径

DataRestoreConfig config = DataRestoreConfig.builder()
        .format(ExportFormat.EXCEL)               // CSV / SQL / EXCEL
        .outputDir(new File("out"))
        .options(options)
        .build();

DataRestoreResult result = DataRestore.create("wechat", config)
        .restore(new File("E:/微信/xwechat_files"));
```

### 方式三：命令行

> 注意：`WechatRestoreCli.main(String[])` 目前是预留的空方法，直接
> `java -cp <classpath> com.chua.wechat.support.restore.WechatRestoreCli ...` 不会执行任何逻辑。
> 实际可执行入口是静态方法 `run(String[] args, PrintStream out, PrintStream err)`（返回退出码 0/1/2），
> 请先用一个几行的启动类包装再运行：

```java
public class RunWechatRestore {
    public static void main(String[] args) {
        int exitCode = WechatRestoreCli.run(args, System.out, System.err);
        System.exit(exitCode);
    }
}
```

```text
# 1) 先看一眼有哪些库、各自是明文还是加密（不需要密钥）
java -cp <classpath> RunWechatRestore E:/微信/xwechat_files --list

# 2) 一键还原（auto 模式，输出到 ./wechat-restore-out）
java -cp <classpath> RunWechatRestore E:/微信/xwechat_files

# 3) 带密钥直解并导出 CSV
java -cp <classpath> RunWechatRestore E:/微信/xwechat_files --key <64位hex> --out out/csv

# 4) 导出 SQL（含建表语句）并保留明文库
java -cp <classpath> RunWechatRestore E:/微信/... --key-file key.txt --format sql --decrypt-dir out/plain
```

### memory 模式的输出

| 文件 | 说明 |
|---|---|
| `report.html` | 自包含的聊天记录查看器（双击即可看，支持搜索与按发送者筛选） |
| `messages.csv` | 可直接阅读的聊天记录（时间 / 发送者 / 正文） |
| `id_map.csv` | 各库簇的 sender_id → 用户名映射 |
| `wechat_memory.db` | 重建出的明文 SQLite 库，可用任意客户端查询 |
| `wechat_memory__*.csv` | 各表原始导出（另含 `schema.sql` / `summary.md`） |
| `memory_accumulated.tsv` | 跨次扫描的累积文件（反复扫描结果不断合并、逼近全量） |

---

## 配置项

配置通过 `DataRestoreConfig.getOptions()` 传入（键为 `String`，常量定义在 `WechatDataRestore` / `WechatJdbcExporter`）：

| options 键 | 说明 | 缺省 |
|---|---|---|
| `mode` | `auto` / `native` / `sqlcipher` / `memory` / `tool` | `auto`（native → sqlcipher（有密钥时）→ memory → tool 依次降级） |
| `runtime.dir` | native 路径的原生库目录（含 `WCDB.dll`、`SDL2.dll`、`wcdb_api.dll`、`wx_key.dll`，即 Wechat-Export 的 runtime 目录）；已配置 `tool.path` 时按相邻目录自动推导 | 无（native 路径必需） |
| `key` | 数据库密钥：64 位 hex（编排层与 CLI 均按 64 位校验；带 salt 的 96 位形态仅 `SqlCipherDecryptor.parseHexKey` 能解析，传入本项前需自行截取前 64 位） | 无 |
| `key.file` | 密钥文件路径（兼容整行 hex 与 `key=...` 形式） | `<输出目录>/key.txt` |
| `auto.key` | 密钥缺失时是否自动引导重启微信并经 FFM 捕获（需管理员权限） | `false` |
| `tool.path` | tool 路径的 Wechat-Export `export.py` 脚本路径（或工具根目录） | 无 |
| `data.dir` | 微信数据目录（`xwechat_files/wxid_xxx`） | 源是目录取其本身；源是文件取其所在目录 |
| `decrypt.dir` | SQLCipher 解密后的明文库保留目录 | `<输出目录>/decrypted` |
| `limit` | 每个会话 / 每张表最多导出条数 | `0`（全部） |
| `whitelist` / `blacklist` | 会话（native/tool 路径）或表（经 `table.whitelist` / `table.blacklist` 透传给 JDBC 导出器）的白 / 黑名单，逗号分隔 | 无 |
| `skip.groups` | native 路径是否跳过群聊 | `false` |
| `decode.blob` | memory 路径是否解压 ZSTD 压缩的消息体 | `true` |
| `memory.enabled` | auto 模式是否允许降级到 memory 路径 | `true` |
| `memory.accumulate` | memory 路径是否把本次扫描结果合并进累积文件（`false` 则每次都是干净单次快照） | `true` |
| `table.whitelist` / `table.blacklist` | JDBC 导出器的表名白 / 黑名单（逗号分隔，非空白名单时仅导出命中表） | 无 |

### 对应的命令行选项

```text
--data-dir <dir>       微信数据目录（等价于位置参数）
--key <hex>            SQLCipher 密钥：64 位 hex（96 位带 salt 形态不被接受，需先截取前 64 位）
--key-file <file>      密钥文件；缺省读取 <输出目录>/key.txt
--scan-key             内存扫描 Weixin.exe 取密钥（免重启微信，需管理员权限）
--scan-pid <pid>       指定要扫描的微信进程（缺省自动选工作集最大的）
--scan-max-mb <n>      内存扫描字节上限（MB），用于快速试跑；缺省不限
--auto-key             允许走原生库自动捕获密钥（需管理员并重启微信）
--out <dir>            输出目录，默认 ./wechat-restore-out
--format <fmt>         csv | sql | excel | json，默认 csv
--mode <mode>          auto | memory | native | sqlcipher | tool，默认 auto
--decrypt-dir <dir>    保留解密后的明文数据库到此目录
--runtime-dir <dir>    native 模式的原生库目录
--tool-path <path>     tool 模式 Wechat-Export 脚本路径
--limit <n>            每张表最多导出行数
--whitelist <a,b>      仅导出这些表
--blacklist <a,b>      排除这些表
--skip-groups          跳过群聊相关表
--list                 仅列出数据库并探测加密形态，不做还原
--probe <hex>          仅用给定密钥探测各数据库是否可解
-h, --help             显示帮助
```

---

## SPI 扩展点

- **本模块实现的 SPI**：`com.chua.common.support.task.restore.DataRestore`（由 `utils-support-common-starter` 提供），实现类 `WechatDataRestore`，SPI 名 **`wechat`**（别名 `wechat-export`）。
- **注册文件**：`META-INF/extensions/com.chua.common.support.task.restore.DataRestore`，内容 `wechat=com.chua.wechat.support.restore.WechatDataRestore`。
- **目录源入口**：微信数据源天然是目录，`WechatDataRestore` 重写了 `restore(File, DataRestoreConfig)` 为目录源另开等价入口（基类契约只收单个文件）。
- **外部扩展接口**：本模块未提供新的供外部实现的 SPI 接口——内部工具类均为 `final` 且构造器私有；扩展方式是按 `mode` 选用内置路径，或直接组合使用 `SqlCipherDecryptor` / `WechatJdbcExporter` / `WechatMemoryKeyScanner` 等公开静态方法。
- **输出格式**：JDBC 导出路径支持 CSV / SQL / EXCEL；JSON 会抛 `UnsupportedOperationException`（JSON 仅作为 tool 路径生成 SQL 的中间格式）。

---

## 构建与依赖

### Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-wechat-restore-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 依赖清单（来自模块 `pom.xml`）

| 依赖 | 用途 |
|---|---|
| `com.chua:utils-support-common-starter` | SPI 契约（`DataRestore` / `DataRestoreConfig` / `DataRestoreResult` / `ExportFormat`）、命令执行、JSON 等基础能力 |
| `com.chua:utils-support-excel-starter` | Excel 输出 |
| `com.github.luben:zstd-jni`（`${zstd-jni.version}`） | 微信 4.x 压缩消息体（ZSTD 魔数 `0xFD2FB528`）解压 |
| `org.xerial:sqlite-jdbc` | 重建出的明文库 / SQLCipher 解密结果走 JDBC 导出（`SQLiteConfig` 调 PRAGMA） |

### 构建

```bash
mvn -o -q compile -DskipTests          # 编译验证
mvn clean package -DskipTests -Dpmd.skip=true -Dmaven.test.skip=true   # 打包
```

---

## 边界（这些能力不在本模块）

- **微信在线能力**：登录、收发消息、机器人、公众号等不属于本模块（本模块只做本机存量数据的离线还原）。
- **不写入微信目录**：全程只读；还原产物统一落到输出目录（缺省 `<源目录>/wechat-restore-out`），该目录在收集数据源时会被自动排除，避免把上次产物当成数据源。
- **非 Windows 平台**：`native` / `memory` 路径仅支持 Windows，不满足时抛 `IllegalStateException` 并提示改用 `mode=tool`。
- **JSON 输出**：JDBC 导出路径不支持 JSON（仅 CSV / SQL / EXCEL）；tool 路径中 JSON 只作为转 SQL 的中间格式。
- **密钥保证**：内存扫描（`--scan-key`）与 Hook 捕获（`--auto-key`，需管理员并重启微信）都不保证成功——密钥可能由服务端包裹或不在可读内存中；最终仍以 `--key` / `--key-file` 显式提供最可靠。
- **memory 路径的天然限制**：只能看到微信**已经缓存过**的页；要让更多记录进入缓存，需在微信里打开/滚动对应聊天后重新扫描，并依赖累积文件逐步逼近全量。
- **native 路径的版本限制**：微信 4.1.13.x 已把 WCDB 静态链接进 `Weixin.dll`，安装目录下没有 `wcdb_api.dll`，该路径在这些版本上不可用；且 `wcdb_init()` 存在宿主进程名校验（非 `electron.exe` 返回 `-1006`），需经 `WechatNativeLauncher` 自举启动。
- **图形界面**：无；仅有命令行 `WechatRestoreCli` 与 Java API。
