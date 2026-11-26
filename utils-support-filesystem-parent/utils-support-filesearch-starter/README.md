# utils-support-filesearch-starter

> **一句话定位**：文件搜索引擎模块（pom 描述：*文件搜索引擎模块，基于 Rust 原生库 + Java 25 FFM*）。通过 `FileSearchService` 门面提供「目录树 / 按名称 / 按大小 / 按路径」四类文件搜索：优先走 Rust 原生库（WizTree 能力，经 `FileSearchSpi` SPI 接入），原生库缺失或结果为空时自动降级 JDK NIO 遍历；另提供 `filesystem:` / `filesystem*:` 协议资源查找器。
>
> - **坐标**：`com.chua:utils-support-filesearch-starter:4.0.0.51`（jar，父模块 `utils-support-filesystem-parent:4.0.0.51`）
> - **JDK**：25（父 POM `maven.compiler.release=25` / `java.version=25`）
> - **包名**：`com.chua.filesearch.support`（子包 `service` / `helper` / `nativeaccess` / `resource` / `spi.impl`）

## 目录

1. 它是什么 / 不是什么
2. 核心 API 与速查表
3. 快速开始
4. 配置项
5. SPI 扩展点
6. 构建与依赖
7. 边界（这些能力不在本模块）

## 它是什么 / 不是什么

**它是什么：**

| 能力 | 说明 |
|---|---|
| 文件搜索门面 | `FileSearchService` 全局单例，统一入口，异常安全（失败返回空列表） |
| Rust 原生搜索引擎接入 | 经 `RustFileSearchHelper` → `RustFileSearchBridge` → `FileSearchSpi`（由 `utils-support-native-filesearch` 通过 `META-INF/services` 注册）调用原生库 |
| 四类查询 | 按名称通配符（`*` / `?`）、按大小区间、按路径模式、目录树遍历 |
| 目录体积聚合 | `postProcessTree` 递归汇总每个目录的文件数 / 子目录数 / 总体积，并计算占父目录体积百分比 |
| JDK 降级路径 | 原生结果为空时回退 `Files.walk`（`searchByJdk`），按名称 glob 过滤普通文件 |
| `FileSearchProvider` 的 native 实现 | `NativeFileSearchProvider`，经 `META-INF/extensions` 注册，SPI 键为 `native` |
| `filesystem:` 协议资源查找器 | `FileSystemResourceFinder`（继承 `AbstractResourceFinder`），按路径模式查找并产出 `Resource` |
| GraalVM Native Image 元数据 | 内置 reflect / resource / proxy 配置，支持 AOT 场景 |

**它不是什么：**

| 不是 | 依据 |
|---|---|
| 不是文件内容 / 全文搜索引擎 | 仅支持名称、大小、路径、目录树四类条件，源码未提供内容检索入口 |
| 不是 Spring Boot Starter | 无自动配置类、无 `spring.factories` / `AutoConfiguration.imports`、无 HTTP 端点、无定时任务 |
| 不含 Rust 原生库二进制 | 原生库本体与绑定在 `com.chua:utils-support-native-filesearch`（`utils-support-native-parent` 仓），本模块经 SPI 可选依赖它 |
| 不做文件 IO / CRUD | 不提供文件读写、复制、删除、重命名等操作，也不持久化搜索结果 |

## 核心 API 与速查表

别人最常查的入口（均属本模块源码，签名以源码为准）：

| # | 入口 | 用途 |
|---|---|---|
| 1 | `FileSearchService.getInstance()` | 获取全局单例门面（`final` 类，私有构造，双检锁单例）；配合 `isAvailable()` 判断是否拿到了可用的 `FileSearchProvider` |
| 2 | `FileSearchService#search(FileSearchCriteria)` | 通用条件搜索，返回 `List<FileInfo>`；内部异常全部吞掉并返回空列表 |
| 3 | `FileSearchService#searchByName(String rootPath, String pattern)` | 按文件名通配符搜索，如 `*.txt`、`report?.pdf` |
| 4 | `FileSearchService#searchBySize(String rootPath, long minSize, long maxSize)` | 按大小区间搜索（字节；上限传 `-1` 表示不限） |
| 5 | `FileSearchService#getTree(String rootPath)` | 遍历目录树（返回文件+目录，含聚合后的目录体积与占比） |
| 6 | `RustFileSearchHelper#searchStream(FileSearchCriteria, Consumer<FileInfo>)` | 流式搜索：结果按 128 条一批回调消费者，返回实际匹配数（负错误码归零） |
| 7 | `RustFileSearchHelper#cancel()` / `#getVersion()` | 取消进行中的原生搜索 / 获取原生引擎版本串 |
| 8 | `FileSystemResourceFinder#find(String name)` | `filesystem:` / `filesystem*:` 协议资源查找，返回 `Set<Resource>` |

### 搜索条件：`FileSearchCriteria`

定义于依赖模块 `utils-support-filesystem-starter`（包 `com.chua.filesystem.support.filesearch.model`），record + 链式 `Builder`：

| Builder 方法 | 默认值 | 说明 |
|---|---|---|
| `rootPath(String)` | `null` | 根目录；**空则回退 `System.getProperty("user.dir")`** |
| `namePattern(String)` | `null` | 文件名通配符；非空时走「按名称」查询 |
| `minSize(long)` / `maxSize(long)` | `-1` / `-1` | 大小区间（字节）；`>0` 才参与判定，`-1` 不限 |
| `maxResults(int)` | `1000`（`DEFAULT_MAX_RESULTS`） | 最大返回数；规范构造器中 `<0` 会被重置为 1000 |
| `maxDepth(int)` | `0`（`DEFAULT_MAX_DEPTH`） | 目录树最大深度，`0` 不限（仅目录树查询使用） |
| `excludeDirs(String...)` / `excludePatterns(String...)` | `null` | 排除目录名 / 排除文件名模式（构造时防御性拷贝） |
| `followLinks(boolean)` | `false` | 是否跟踪符号链接 |
| `includeHidden(boolean)` | `false` | 是否包含隐藏文件 |
| `sortBy(String)` | `"size"`（`SORT_BY_SIZE`） | 可选 `SORT_BY_NAME` / `SORT_BY_SIZE` / `SORT_BY_PATH` / `SORT_BY_MODIFIED` |
| `order(String)` | `"desc"`（`ORDER_DESC`） | `ORDER_ASC` / `ORDER_DESC` |

> 生效范围说明：`namePattern`、`minSize/maxSize`、`maxDepth`、`maxResults`、`rootPath` 在 Java 侧分发逻辑中直接生效；`excludeDirs` / `excludePatterns` / `sortBy` / `order` / `followLinks` / `includeHidden` 属于契约字段，JDK 降级路径（`searchByJdk`）未实现这些过滤与排序，原生侧是否生效以 `utils-support-native-filesearch` 的实现为准。

### 搜索结果：`FileInfo`

定义于 `utils-support-filesystem-starter`（同包 `model`），15 字段 record：

| 字段 | 说明 |
|---|---|
| `name` / `path` | 文件名（含扩展名）/ 完整路径 |
| `size` / `sizeFormatted` | 字节数 / 人类可读大小（如 `1.5 GB`，由 `formatSize` 按 B/KB/MB/GB/TB 格式化） |
| `parentDir` | 父目录路径 |
| `percentage` | 占父目录体积百分比，`0` 表示未计算（目录树查询后处理时填充） |
| `lastModified` | 最后修改时间戳（毫秒） |
| `isDirectory` | 是否目录 |
| `extension` | 扩展名（不含点；无扩展名为空串。JDK 降级路径统一转小写，原生侧以返回数据为准） |
| `attributes` | 文件属性位掩码；普通文件默认置 `0x20`（归档位） |
| `usnRecordId` / `parentFileId` | Windows NTFS 的 USN 记录号 / 父目录文件号（非 Windows 为 0） |
| `fileCount` / `dirCount` | 目录聚合的文件数 / 子目录数（仅目录有效，否则 0） |
| `allocatedSize` | 磁盘占用空间（字节） |

## 快速开始

**第 1 步：引入依赖**（见[构建与依赖](#构建与依赖)），可选引入 `utils-support-native-filesearch` 启用 Rust 原生引擎。

**第 2 步：三行起步**

```java
FileSearchService service = FileSearchService.getInstance();
List<FileInfo> files = service.searchByName("D:/data", "*.pdf");
files.forEach(f -> System.out.println(f.path() + "  " + f.sizeFormatted()));
```

**最短可运行示例**（覆盖四类查询 + 组合条件）：

```java
import com.chua.filesearch.support.service.FileSearchService;
import com.chua.filesystem.support.filesearch.model.FileInfo;
import com.chua.filesystem.support.filesearch.model.FileSearchCriteria;

import java.util.List;

public class FileSearchDemo {

    public static void main(String[] args) {
        FileSearchService service = FileSearchService.getInstance();
        if (!service.isAvailable()) {
            System.out.println("没有可用的 FileSearchProvider");
            return;
        }

        // 1) 按名称搜索
        List<FileInfo> txt = service.searchByName("D:/data", "*.txt");

        // 2) 按大小搜索：>= 100MB（上限 -1 表示不限）
        List<FileInfo> big = service.searchBySize("D:/data", 100L * 1024 * 1024, -1);

        // 3) 目录树（目录会带 fileCount/dirCount/聚合体积/占比）
        List<FileInfo> tree = service.getTree("D:/data");
        tree.stream().filter(FileInfo::isDirectory)
                .forEach(d -> System.out.println(d.path() + "  " + d.fileCount() + " 个文件"));

        // 4) 组合条件：最多 100 条、深度 3 层
        List<FileInfo> reports = service.search(FileSearchCriteria.builder()
                .rootPath("D:/data")
                .namePattern("report?.pdf")
                .maxResults(100)
                .maxDepth(3)
                .build());

        System.out.println(txt.size() + " / " + big.size() + " / " + tree.size() + " / " + reports.size());
    }
}
```

**流式搜索与取消**（大目录建议流式消费，避免一次性持有全部结果）：

```java
import com.chua.filesearch.support.helper.RustFileSearchHelper;

long count = RustFileSearchHelper.searchStream(criteria, fileInfo -> {
    // 每 128 条一批回调到这里
    System.out.println(fileInfo.path());
});
String version = RustFileSearchHelper.getVersion(); // 原生引擎版本串
RustFileSearchHelper.cancel();                      // 取消进行中的原生搜索
```

**行为要点**（源码事实）：

- 分发规则：`namePattern` 非空 → 原生 `searchByName`；否则 `minSize>0 || maxSize>0` → 原生 `searchBySize`（`maxSize<=0` 按 `-1` 不限处理）；否则 → 原生 `getTree(maxDepth, maxResults)`。
- `RustFileSearchHelper` 静态块即调用 `RustFileSearchBridge.loadLibrary()` 加载原生库；原生库未加载时 `search0` 直接返回空，`search` 随即降级 JDK `Files.walk`（日志 warn：`Native search returned empty results, falling back to JDK walkdir`）。
- JDK 降级只收集**普通文件**（`Files::isRegularFile`），glob 转 regex 规则：`.`→`\.`、`*`→`.*`、`?`→`.`；`maxResults<=0` 视为不限。
- 原生返回负数错误码时仅记录 warn 日志；`searchStream` 把负数归零后作为匹配数返回。
- `rootPath` 为空白时统一回退 `System.getProperty("user.dir")`。

## 配置项

**无。**

依据：本模块 `src/main/resources` 下只有两类文件——`META-INF/extensions/com.chua.filesystem.support.filesearch.spi.FileSearchProvider`（SPI 注册）与 `META-INF/native-image/...`（GraalVM 元数据）；没有 `application.yml` / `*.properties` / `spring.factories`，源码中也没有任何 `@ConfigurationProperties` 或配置键读取。唯一的"默认值"逻辑都是 API 参数级默认（`rootPath` 空回退 `user.dir`、`maxResults=1000`、`sortBy=size`、`order=desc`），不来自外部配置。

## SPI 扩展点

本模块涉及三层 SPI/扩展契约：

1. **业务 SPI：`com.chua.filesystem.support.filesearch.spi.FileSearchProvider`**（接口定义在 `utils-support-filesystem-starter`，唯一方法 `List<FileInfo> searchFiles(FileSearchCriteria)`）。
   本模块在 `META-INF/extensions/com.chua.filesystem.support.filesearch.spi.FileSearchProvider` 中注册了一行：
   `native=com.chua.filesearch.support.spi.impl.NativeFileSearchProvider`
   `FileSearchService` 构造时先经 `ServiceProvider.of(FileSearchProvider.class).getDefault()` 加载默认实现；失败则直接 `new NativeFileSearchProvider()` 兜底；两者都失败则 `provider=null`，`isAvailable()=false`，`search` 返回空列表。
   **替换/扩展方式**：按同一 SPI 注册自己的 `FileSearchProvider` 实现即可接管 `FileSearchService`。

2. **原生库 SPI：`com.chua.common.support.nativeapi.FileSearchSpi`**（定义在 `utils-support-common-starter`）。
   `RustFileSearchBridge` 通过 `NativeServices.get(FileSearchSpi.class)` 取实现（由 `utils-support-native-filesearch` 经 `META-INF/services` 注册），方法集：`loadLibrary` / `isLoaded` / `available`（default，未加载时先加载再复查）/ `getLoadError` / `getVersion` / `cancel` / `searchByName` / `getTree` / `searchBySize` / `searchByPath`。原生模块缺失时 `getLoadError()` 返回 `IllegalStateException("请自行添加依赖 com.chua:utils-support-native-filesearch")`。

3. **资源查找器扩展：`FileSystemResourceFinder extends AbstractResourceFinder`**（基类在 `utils-support-common-starter` 的 `com.chua.common.support.file.resource` 包）。
   处理 `filesystem:` 与 `filesystem*:` 协议，支持 Ant 风格通配符 `*`（单层任意）、`**`（多层任意）、`?`（单字符）；模式在第一个 `/` 或 `\` 处切分为「根目录 + 路径模式」，无分隔符时整串视为根目录、模式取 `*`；根目录为空回退 `user.dir`，根目录不存在返回空集；结果上限固定 1000 条；`ResourceConfiguration#isParallel()` 为真时使用并发集合收集；每命中一个资源会回调已注册的 `consumer`。

## 构建与依赖

**坐标**：`com.chua:utils-support-filesearch-starter:4.0.0.51`，父模块 `com.chua:utils-support-filesystem-parent:4.0.0.51`，顶层父 POM `com.chua:utils-support-parent-starter:4.0.0.51`，JDK 25。

**pom 声明的依赖**：

| 依赖 | 说明 |
|---|---|
| `com.chua:utils-support-common-starter` | 底层工具与 `FileSearchSpi` / `NativeServices` / `ServiceProvider` / 资源查找基类 |
| `com.chua:utils-support-filesystem-starter` | 提供契约：`FileSearchProvider` / `FileSearchCriteria` / `FileInfo` |
| `org.projectlombok:lombok` | `provided`，`@Slf4j` 日志 |
| `org.slf4j:slf4j-api` | 日志门面 |

**运行期可选依赖**：`com.chua:utils-support-native-filesearch`（Rust 原生库绑定，位于 `utils-support-native-parent` 仓，pom 描述：*文件搜索 Rust native 库（WizTree 能力，跨平台）*，按 release 25 编译）。本模块 pom **未**直接声明它——按 `RustFileSearchBridge` javadoc，它是可选依赖：构件存在时经 `META-INF/services` 委托其实现，缺失时首次调用给出警告并降级 JDK 遍历。

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-filesearch-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
<!-- 可选：启用 Rust 原生搜索引擎（版本以 utils-support-native-parent 发布为准） -->
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-native-filesearch</artifactId>
    <version>4.0.0.43</version>
</dependency>
```

**GraalVM Native Image**：本模块在 `src/main/resources/META-INF/native-image/com.chua/utils-support-filesearch-starter/` 内置三份元数据——`reflect-config.json`（`NativeFileSearchProvider` 全量反射注册）、`resource-config.json`（包含 `META-INF/services/.*`）、`proxy-config.json`；父 POM 另提供 `native` profile（`org.graalvm.buildtools:native-maven-plugin:1.1.14`）。

**构建命令**（仓库通用口径）：

```bash
mvn -o -q compile -DskipTests
```

## 边界（这些能力不在本模块）

- **无 Spring Boot 自动配置**：没有配置类、没有 HTTP 端点、没有定时任务、没有健康检查。
- **无文件内容/全文检索**：只能按名称通配符、大小区间、路径模式、目录树四种维度查；查文件内容请用别的模块。
- **契约模型不在本模块定义**：`FileSearchProvider`、`FileSearchCriteria`、`FileInfo` 都定义在 `utils-support-filesystem-starter`；本模块只提供 native 实现与门面。
- **原生库本体不在本模块**：Rust 源码与 `.dll` / `.so` 产物属于 `utils-support-native-filesearch`；本模块不含任何原生二进制。
- **无文件 IO 能力**：不读文件内容、不写文件、不删除、不复制、不监听文件变化。
- **无结果持久化/缓存**：每次搜索即时执行，不落库、不缓存。
- **条件字段生效有限制**：`excludeDirs` / `excludePatterns` / `sortBy` / `order` / `followLinks` / `includeHidden` 在本模块 JDK 降级路径中未实现，原生侧生效情况以 `utils-support-native-filesearch` 实现为准。
