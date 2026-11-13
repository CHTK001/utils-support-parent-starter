# utils-support-common-starter

> utils 体系的**契约层 + 基础实现层**：全部 SPI 接口、通用工具类、文件系统抽象、网络/任务/AI/存储等子系统的公共门面都在这里；具体协议、驱动、平台能力由同仓各 `*-starter` 模块通过 SPI 扩展。
>
> 版本：`4.0.0.50`｜JDK：`25`（`maven.compiler.release=25`，含 `--enable-preview`）｜包名：`com.chua.common.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 包地图](#2-包地图)
- [3. 核心机制](#3-核心机制)
  - [3.1 SPI 服务发现](#31-spi-服务发现)
  - [3.2 类型转换 Converter](#32-类型转换-converter)
  - [3.3 序列化](#33-序列化)
  - [3.4 轻量 IoC 容器 ObjectContext](#34-轻量-ioc-容器-objectcontext)
  - [3.5 配置解析与属性源](#35-配置解析与属性源)
- [4. 基础工具（utils 包）](#4-基础工具utils-包)
- [5. 集合与并发](#5-集合与并发)
- [6. 文件、归档与存储](#6-文件归档与存储)
- [7. 网络](#7-网络)
- [8. 任务与流程](#8-任务与流程)
- [9. AI](#9-ai)
- [10. 数据、WAL 与向量](#10-数据wal-与向量)
- [11. 导出、文档与图像](#11-导出文档与图像)
- [12. 平台与运维](#12-平台与运维)
- [13. 其它子系统](#13-其它子系统)
- [14. SPI 扩展点总表](#14-spi-扩展点总表)
- [15. 配置项与系统属性](#15-配置项与系统属性)
- [16. 扩展开发](#16-扩展开发)
- [17. 构建与依赖](#17-构建与依赖)
- [18. 边界：这些能力不在本模块](#18-边界这些能力不在本模块)
- [19. 文档索引](#19-文档索引)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>4.0.0.50</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 抽象 | 定义 SPI 接口与统一门面（`FileSystem`、`Server`、`ChatClient`、`VectorStorage`、`WalLog`、`FileStorage` 等） | 不绑定具体厂商 |
| 实现 | 默认实现（JDK 文件系统、JDK HttpServer、内存向量库、JSON 序列化等纯 JDK 能力） | 不追求覆盖所有平台 |
| 依赖 | 只依赖 guava / jackson / reactor / eclipse-collections / slf4j / lombok | 不依赖 Spring、不依赖数据库驱动、不依赖 AI SDK |

**判断口径**：只与 JDK 有关的通用能力放本模块；需要第三方协议栈、驱动或平台账号的，放对应 `*-starter` 并用 SPI 注册。

### 1.3 规模

- Java 源文件约 **1880** 个，顶层包 **51** 个。
- SPI 扩展声明文件 **50+** 个（`src/main/resources/META-INF/extensions/`）。
- 同时内置 GraalVM `native-image` 的 `reflect-config.json` / `proxy-config.json` / `resource-config.json`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发 HTTP 请求 | `network.client.HttpClientFactory` | 链式 + 同步/异步（`Mono`）+ cURL 互转 |
| 换 HTTP 底层 | `HttpClientFactory.getClient("okhttp")` | 执行器 SPI：okhttp / httpclient5 / jdk |
| 起 HTTP/TCP 服务 | `network.server.ServerBuilder` | IP、端口、路由、过滤器一步配 |
| 文件读写/压缩 | `file.FileSystem` | csv/json/xml/txt/zip/tar，按扩展名自动推断 |
| 下载大文件 | `network.download.Downloader` | 多线程、限速、断点、MD5、自动解压 |
| 对象存储 | `storage.FileStorage` | S3/OSS/COS/MinIO 统一模型 |
| AI 对话 | `ai.chat.ChatClient` | 同步/流式/异步、工具、图片、视频 |
| 知识库/RAG | `ai.rag.RagClient` | 文档导入、切分、检索 |
| 定时任务 | `task.scheduler.TimeScheduler` | cron / 固定频率 / 固定延迟 |
| 任务分发 | `taskdistribution.manager.TaskManager` | 状态机、重试、持久化、恢复 |
| 轻量 IoC | `objects.DefaultObjectContext` | `createWithInitize()` 拿到即用 |
| 类型转换 | `converter.Converter` | 标量/集合/枚举/日期/Bean |
| SPI 扩展 | `spi.ServiceProvider` | `getExtension("name")`、优先级、降级代理 |
| 配置读取 | `config.parser.ConfigParser` + `PropertySource` | properties/xml/json/jsonl/环境变量 |
| 数据大小 | `math.unit.size.SizeValue` | `SizeValue.parse("12MB")` |
| 目录监听 | `lang.directory.DirectoryWatcher` | 新增/修改/删除事件 |
| 二维码 | `image.QrCode` + `lang.qr.*` | 接口与样式；生成实现在 zxing-starter |
| 命令执行 | `lang.cmd.Cmd` / `CliTool` | 外部命令、tshark/aria2/nvidia-smi |
| 主机指纹 | `utils.SystemUtils` / `host.HostIdentity` | 不可变信息 + 相似度校验 |
| 数据库连接池 | `concurrent.pool.ConnectionPool` | 自带池化、校验、空闲回收 |

---

## 2. 包地图

按源文件数量排序，括号内为 `.java` 文件数。

| 包 | 数量 | 职责 | 代表类型 |
| --- | --- | --- | --- |
| `lang` | 341 | 语言层：算法、AST/表达式、Bean 路径、基准、CLI、数据源引擎、日期、目录轮询、文档、JSON、占位符、进程、二维码、脚本、模板、视图解析 | `lang.json.Json`、`lang.datasource.engine.Engine`、`lang.process.ProgressBar`、`lang.view.ViewParser` |
| `network` | 319 | HTTP 客户端、服务端、协议、RPC、发现、下载、SSE、SIP、隧道、TLS、同步 | `network.client.HttpClientFactory`、`network.server.Server`、`network.discovery.ServiceDiscovery` |
| `ai` | 252 | 对话、Agent、RAG、Embedding、图像/音频/视频生成、MCP、技能、规则、校准、探测 | `ai.chat.ChatClient`、`ai.agent.*`、`ai.rag.RagClient` |
| `task` | 137 | 调度、流程、管道、重试、冷却、去重、备份恢复、脚本 | `task.scheduler.TimeScheduler`、`task.flow.Flow`、`task.pipeline.*` |
| `concurrent` | 91 | 锁、限流、熔断、舱壁、批量合并、派发、队列、线程流、结构化并发 | `concurrent.lock.LockProvider`、`concurrent.rate.RateLimiter` |
| `objects` | 75 | 轻量 IoC：BeanDefinition、注册器、注入器、生命周期、事件、环境 | `objects.ObjectContext`、`objects.DefaultObjectContext` |
| `file` | 51 | 文件系统 SPI、读写构建器、归档、资源、文本抽取 | `file.FileSystem`、`file.impl.ZipFileSystem` |
| `converter` | 52 | 类型转换器定义与统一入口 | `converter.Converter` |
| `collection` | 42 | 增强集合：多值 Map、环形结构、位图、堆外存储、大小写不敏感 | `collection.MultiValueMap`、`collection.BitList` |
| `utils` | 41 | 静态工具类 | `utils.StringUtils`、`utils.MapUtils`、`utils.DigestUtils` |
| `export` | 30 | 数据导出与模板填充 SPI | `export.Documents`、`export.spi.DocumentGenerator` |
| `spi` | 30 | SPI 注解、条件、解析器、自动注入 | `spi.ServiceProvider`、`spi.annotations.Spi` |
| `scatter` | 25 | 无中心化对等网格：发现、节点、帧协议、TCP/UDP 传输 | `scatter.Scatter`、`scatter.ScatterBuilder` |
| `apm` | 25 | Otnl 可观测引擎 SPI 与 SkyWalking 对齐的模型 | `apm.OtnlEngine`、`apm.model.*` |
| `wal` | 21 | 预写日志：单文件/分片、checkpoint、回放、链式事务 | `wal.WalLog`、`wal.WalFactory` |
| `taskdistribution` | 24 | 分布式任务：manager/store/dispatcher/node/strategy | `taskdistribution.manager.TaskManager` |
| `storage` | 18 | 对象存储 SPI 与请求/结果模型、分片上传 | `storage.FileStorage`、`storage.MultipartStorage` |
| `vector` | 17 | 向量存储 SPI、距离算法、JUMP 加速 | `vector.VectorStorage`、`vector.VectorStorageBuilder` |
| `datasource` | 14 | 数据源 JOIN 策略、WAL 存储引擎 | `datasource.join.JoinStrategy`、`datasource.wal.WalStoreSystem` |
| `proxy` | 15 | 动态代理、方法拦截、注解切面 | `proxy.ProxyFactory`、`proxy.intercept.MethodIntercept` |
| `config` | 26 | 配置中心、加载器、解析器、配置源 | `config.parser.ConfigParser`、`config.source.*` |
| `constant` | 12 | 常量 | — |
| `exception` | 11 | 异常体系 | — |
| `function` | 11 | 安全函数式接口、`SafeOptional`、命名线程工厂 | `function.SafeOptional` |
| `codec` | 10 | 编解码 SPI（图像/视频） | `codec.Decoder`、`codec.VideoEncoder` |
| `serialize` | 9 | 序列化 SPI 与反序列化白名单策略 | `serialize.Serializer`、`serialize.DeserializationPolicy` |
| `base` | 8 | 校验分组、序列化/转换基础接口 | `base.Options`、`base.Serialization` |
| `host` | 8 | 主机指纹与不可变信息采集 | `host.HostIdentity`、`host.HostInfoProvider` |
| `image` | 7 | 图像处理管线与图像处理器 SPI | `image.ImageProcessors`、`image.ImageProcessor` |
| `sync` | 7 | 旧版同步模型（Input/Output/Sink）；新同步在 `datasync-starter` | `sync.Input`、`sync.Sink` |
| `tree` | 7 | B/B+ 树与二叉树互转 | `tree.TreeEngine`、`tree.BPlusTree` |
| `browser` | 7 | 浏览器引擎 SPI（CDP 实现） | `browser.BrowserEngine`、`browser.BrowserPage` |
| `language` | 6 | 拼音、分词 | `language.Pinyin`、`language.tokenizer.Tokenizer` |
| `service` | 6 | 本地/远程服务进程管理 | `service.ServiceBuilder`、`service.ServiceManager` |
| `value` | 6 | 值包装（懒加载、过期、数字） | `value.Value`、`value.LazyValue` |
| `io` | 6 | 归档/压缩流接口 | `io.ArchiveInputStream`、`io.CompressInputStream` |
| `annotation` | 5 | 通用注解 | — |
| `document` | 3 | 文档生成请求/结果 + Provider | `document.DocumentProvider` |
| `shmqueue` | 3 | 共享内存队列 | `shmqueue.ShmQueue` |
| `matcher` | 3 | Ant 风格路径匹配 | `matcher.MatcherUtils` |
| `osgi` | 8 | OSGi 启动器与 Bundle 抽象 | `osgi.OsgiLauncher` |
| `probability` | 2 | 概率碰撞（布隆/计数） | `probability.ProbabilityCollision` |
| `pool` | 2 | 可池化对象 | `pool.PooledObjectClient` |
| `math` | 2 | 数据大小单位与解析 | `math.unit.size.SizeValue`、`math.unit.size.SizeUnit` |
| `mock` | 2 | Mock 环境与字符串生成 | `mock.MockString` |
| `env` | 2 | CUDA 环境安装 | `env.CudaEnvLoader` |
| `content` | 2 | 通用内容上下文 | `content.ObjectContent` |
| `setting` | 1 | 全局设置注解 | `setting.GlobalSettingGroup` |
| `modules` | 1 | JPMS 模块导出 | `modules.ModuleLoader` |
| `expression` | 3 | 表达式解析 SPI | `expression.ExpressionResolver` |
| `reflection` | 2 | 反射工具 | `reflection.ReflectUtils` |
| `stream` | 1 | 流压缩 SPI | `stream.StreamCompressor` |
| `enums` | 1 | 业务动作枚举 | `enums.Action` |
| `application` | 1 | 应用入口辅助 | — |

---

## 3. 核心机制

### 3.1 SPI 服务发现

SPI 是整个模块的主干：**接口在本模块，实现在各 starter，按名字装配**。

#### 3.1.1 声明扩展

```java
package com.example;

import com.chua.common.support.spi.annotations.Extension;
import com.chua.common.support.spi.annotations.SpiOrder;

@Extension("mysql")
@SpiOrder(100)
public class MyFileSystem implements FileSystem {
    @Override public String getType() { return "mysql"; }
    @Override public ReadBuilder read(File file) { /* ... */ }
    @Override public WriteBuilder write(File file) { /* ... */ }
}
```

并在 `src/main/resources/META-INF/extensions/<接口全限定名>` 中登记：

```
mysql=com.example.MyFileSystem
```

> `@Spi` 也可直接标注在实现类上（`value` 为名称/别名数组，`order` 为优先级）；`@Extension` 是 4.0.0.42 之后更轻量的写法。`@SpiDefault`、`@SpiDescribe`、`@SpiParam`、`@ConditionalOnClass`、`@ConditionalOnProperty` 用于默认实现、描述、参数与条件加载。

#### 3.1.2 获取实现

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.file.FileSystem;

ServiceProvider<FileSystem> provider = ServiceProvider.of(FileSystem.class);

FileSystem zip = provider.getExtension("zip");          // 按名字
FileSystem def = provider.getDefault();                 // 默认实现
List<FileSystem> all = provider.collect();              // 全部实例
Map<String, FileSystem> map = provider.list();          // 名称 → 实例
Set<String> names = provider.getExtensions();           // 全部名称
boolean has = provider.isSupport("zip");                // 是否存在

provider.register("my-impl", new MyFileSystem());       // 运行期注册
provider.forEach((name, fs) -> System.out.println(name));
```

`ServiceProvider` 的关键能力（均为接口上已验证的方法）：

| 方法 | 说明 |
| --- | --- |
| `of(Class)` / `of(Class, ClassLoader)` | 获取并提供缓存（弱引用缓存，避免类加载器泄漏） |
| `getExtension(String)` | 取单个实例，找不到返回 `null` |
| `getNewExtension(String, Object...)` / `getNewExtensions` | 每次新建（构造参数透传） |
| `getKeepExtension(uid, name, args)` / `closeKeepExtension(uid)` | 按 uid 复用并持有实例 |
| `collect()` / `collect(args)` | 收集所有实现 |
| `list()` / `mapping()` / `listType()` | 名称 → 实例 / 名称 → 类型 |
| `getExtensionFactory(name)` | 生成**按优先级自动降级**的代理：某个实现抛异常会切到下一个 |
| `getPriority()` / `getPriorityServiceDefinitions()` | 按 `@SpiOrder` 取最高优先级 |
| `register(...)` / `unregister(...)` | 运行期注册实现、定义或解析器 |
| `monitor(boolean)` | 开启调用监控 |

`FileSystem` 自带三个静态入口：

```java
FileSystem fs1 = FileSystem.create("zip");                       // 按 SPI 名
FileSystem fs2 = FileSystem.auto(new File("data.xlsx"));          // 按扩展名推断
ZipFileSystem fs3 = FileSystem.create("zip", ZipFileSystem.class); // 带类型，免强转
```

### 3.2 类型转换 Converter

统一入口 `com.chua.common.support.converter.Converter`，覆盖标量、集合、数组、枚举、日期、Bean、图片：

```java
import com.chua.common.support.converter.Converter;

Integer n   = Converter.convertIfNecessary("123", Integer.class);
int n2      = Converter.intOf("123", 0);
BigDecimal d= Converter.toBigDecimal("3.14");
LocalDate ld= Converter.parseLocalDate("2024-01-01", "yyyy-MM-dd");
Status st   = Converter.toEnum("ACTIVE", Status.class);

List<User> users = Converter.convertList(rows, User.class);
List<String> list = Converter.convertIfListNecessary(value, String.class);
Map<String, Object> map = Converter.convertIfMapNecessary(value, String.class, Object.class);

Converter.registerConverter(Money.class, v -> Money.of(v.toString()));  // 自定义
boolean ok = Converter.canConvert(String.class, Integer.class);
```

### 3.3 序列化

```java
import com.chua.common.support.serialize.DefaultSerializerProvider;
import com.chua.common.support.serialize.Serializer;
import com.chua.common.support.serialize.SerializerProvider;

SerializerProvider provider = new DefaultSerializerProvider();   // 默认走 JSON
Serializer<User> serializer = provider.getSerializer(User.class);

byte[] data = serializer.serialize(user);
User back = serializer.deserialize(data);
```

> `Serializer<T>` 的泛型上界是 `Serializable`，被序列化的类型需实现该接口。

安全侧：`DeserializationPolicy` 可插拔，内置 `DenyListDeserializationPolicy`（SPI key `denylist`），由 `DeserializationGuard` 在反序列化前做类名校验。

### 3.4 轻量 IoC 容器 ObjectContext

`com.chua.common.support.objects.ObjectContext` 对齐 Spring 的使用习惯，但不依赖 Spring。

```java
import com.chua.common.support.objects.DefaultObjectContext;
import com.chua.common.support.objects.ObjectContextConfig;

// createWithInitize() = 创建 + init()，拿到即可用
try (DefaultObjectContext context = DefaultObjectContext.createWithInitize()) {

    context.registerBean("myService", new MyService());          // 实例
    context.registerBean(MyService.class);                       // 类型（自动生成 BeanDefinition）
    context.scan("com.example.service");                         // 包扫描

    MyService s1 = context.getBean("myService", MyService.class);
    MyService s2 = context.getBeanOfType(MyService.class);
    Collection<MyService> all = context.getBeanOfTypeCollection(MyService.class);
    Map<String, Object> withAnno = context.getBeansWithAnnotation(Deprecated.class);

    context.autowire(existingObject);                            // 对已存在对象注入
    context.publish(new MyEvent());                              // 事件
}
```

创建容器有三个等价入口：

| 方法 | 行为 |
| --- | --- |
| `new DefaultObjectContext()` | 只创建，不初始化；需要时再 `init()` |
| `DefaultObjectContext.create()` | 创建后立即 `init()`（默认配置） |
| `DefaultObjectContext.createWithInitize()` | 与 `create()` 相同，方法名显式表达「创建即可用」 |
| `DefaultObjectContext.createWithInitize(config)` | 创建后立即用指定配置 `init(config)` |

> `init()` 会创建注册中心、执行包扫描（`ObjectContextConfig.shouldScan()`）并给所有 BeanDefinition 挂上容器回调；重复调用没有额外收益。

体系结构（与源码注释一致）：

```
Class 来源（SPI / 注解扫描 / registerBean / scan）
      → BeanDefinitionGenerator → BeanDefinition
      → BeanDefinitionRegister（name→register、type→names 缓存）
      → createInstance()（ServiceInjector / ConfigInjector / Lifecycle）
      → getBean() / getBeanOfType()
```

扩展点（均可 SPI 替换）：`BeanDefinitionGenerator`、`BeanDefinitionRegister`、`BeanConstructorResolver`、`BeanDefinitionServiceInjector`、`BeanDefinitionConfigInjector`、`BeanDefinitionMethodInjector`、`BeanDefinitionLifecycle`、`BeanScopeDetector`、`ConfigValueExpressionResolver`。

### 3.5 配置解析与属性源

`ConfigParser` 把 properties / xml / json / jsonl 解析成统一的 `PropertySource`；`PropertySource` 则负责**键名归一化**与**类型化读取**。

```java
import com.chua.common.support.config.parser.ConfigParser;
import com.chua.common.support.config.source.PropertySource;
import com.chua.common.support.spi.ServiceProvider;

ConfigParser parser = ServiceProvider.of(ConfigParser.class).getExtension("properties");
try (InputStream in = Files.newInputStream(Path.of("app.properties"))) {
    PropertySource cfg = parser.parse("app.properties", in);

    String host = cfg.getString("server.host", "0.0.0.0");
    Integer port = cfg.getInteger("server.port", 8080);
    Boolean ssl  = cfg.getBoolean("server.ssl", false);
    List<String> hosts = cfg.getList("cluster.hosts");
    Map<String, Object> all = cfg.toMap();
}
```

| 能力 | 说明 |
| --- | --- |
| 键名变体 | `server.port` / `SERVER_PORT` / `server-port` / `serverPort` / `list[0]` 均可命中 |
| 类型读取 | `getString` / `getInteger` / `getLong` / `getDouble` / `getBoolean` / `getBigDecimal` / `getBigInteger` / `getList` |
| 默认值 | 每个读取方法都有带 `defaultValue` 的重载 |
| 多源合并 | `MutiPropertySource`、`PropertiesMutiPropertySource` |
| 内置源 | `MapPropertySource`、`PropertiesPropertySource`、`SystemPropertySource`、`SystemEnvironmentPropertySource` |
| 优先级 | `getPriority()`，多源合并时按优先级取值 |
| 保存/绑定 | `config.loader.ConfigSaveOrLoader`、`ConfigValueBinding` |
| 配置中心 | `config.center.ConfigCenter` + `ConfigListener`（Nacos/Apollo 等由对应 starter 实现） |

---

## 4. 基础工具（utils 包）

> 全部位于 `com.chua.common.support.utils`。以下方法名均与源码一致。

### 4.1 StringUtils

```java
import com.chua.common.support.utils.StringUtils;

StringUtils.isEmpty(null);              // true
StringUtils.isBlank("  ");              // true
StringUtils.isNotBlank("x");            // true

StringUtils.sub("hello world", 0, 5);   // "hello"（左闭右开）
StringUtils.subPre("a/b/c", 3);         // "a/b"
StringUtils.subSuf("a/b/c", 2);         // "/b/c"
StringUtils.subBefore("a.b.c", ".", true);   // "a.b"（最后一个分隔符之前）
StringUtils.subAfter("a.b.c", ".", false);   // "b.c"

StringUtils.leftPad("123", 5, '0');     // "00123"
StringUtils.rightPad("123", 5, '0');    // "12300"
StringUtils.repeat('*', 5);             // "*****"
StringUtils.repeat("ab", "-", 3);       // "ab-ab-ab"

StringUtils.format("你好 {}", "世界");   // 支持 {} 与格式化说明符
StringUtils.defaultString(null, "x");   // "x"
StringUtils.removePrefix("prefix-a", "prefix-");  // "a"
StringUtils.tokenizeToStringArray("a,b;c", ",;"); // [a, b, c]
StringUtils.escapeHtml("<a>");          // "&lt;a&gt;"
```

常用还有：`trimToEmpty` / `trimToNull` / `trimWhitespace` / `trimAllWhitespace`、`startWith` / `endWith`（含 `IgnoreCase` 版本）、`indexOf`、`padding`、`utf8Str`、`str(byte[], Charset)`。

### 4.2 MapUtils

```java
import com.chua.common.support.utils.MapUtils;

Map<String, Object> flat = MapUtils.flattenMap(nested);   // {"user.name": "张三"}
String name  = MapUtils.getString(map, "name", "默认值");
int count    = MapUtils.getIntValue(map, "count", 0);
boolean on   = MapUtils.getBoolean(map, "enabled", false);
List<X> list = MapUtils.getList(map, "items");
Map<K,V> sub = MapUtils.getMap(map, "config");
T typed      = MapUtils.getType(map, "payload", T.class);
Properties p = MapUtils.asProp(map);
```

另有 `merge(Map...)`、`putAll(Map...)`、`getFirstValue`、`getComputeIfAbsent`、`asMap("a=1,b=2", ',', '=')`。

### 4.3 CollectionUtils

```java
import com.chua.common.support.utils.CollectionUtils;

CollectionUtils.isEmpty(null);              // true
CollectionUtils.isEmpty(map);               // 支持 Map / 数组 / Iterable
CollectionUtils.size(list);

List<List<T>> batches = CollectionUtils.averageAssign(largeList, 100);  // 均分 N 份
CollectionUtils.page(list, 100, batch -> handle(batch));                // 按页消费
List<T> page = CollectionUtils.page(1, 20, list);                       // 第 1 页，每页 20

List<T> diff = CollectionUtils.subtractToList(a, b);   // 差集
T first = CollectionUtils.findFirst(list, null);
T last  = CollectionUtils.findLast(list);
List<T> sub = CollectionUtils.sub(list, 0, 10);
Map<R, T>   byId    = CollectionUtils.convertMap(list, User::getId);
Map<R, List<T>> group = CollectionUtils.convertMaps(list, User::getDept);
List<T> descartes = CollectionUtils.descartes(a, b);   // 笛卡尔积
```

> 注意：本模块**没有** `union` / `intersection` 方法，集合运算请用 `subtractToList`（差集）或 JDK `Stream`。

### 4.4 ArrayUtils

```java
import com.chua.common.support.utils.ArrayUtils;

ArrayUtils.isEmpty(arr);  ArrayUtils.isNotEmpty(arr);  ArrayUtils.hasNull(a, b);
ArrayUtils.contains(arr, "a");  ArrayUtils.containsIgnoreCase(arr, "A");
String[] merged = ArrayUtils.merge(arr1, "c");
String[] unique = ArrayUtils.mergeOfDistince(arr1, "c");
String[] added  = ArrayUtils.addLastElement(arr, "z");
String[] asStr  = ArrayUtils.toArrayString(list, String::valueOf);
byte[]   bytes  = ArrayUtils.transToByteArray(list);
```

### 4.5 ClassUtils

```java
import com.chua.common.support.utils.ClassUtils;

ClassUtils.isPresent("com.example.Foo");
Class<?> c = ClassUtils.forName("com.example.Foo");
User u = ClassUtils.forObject(User.class, "张三", 25);   // 按构造参数匹配

List<Field>  fields  = ClassUtils.getFields(User.class);
List<Method> methods = ClassUtils.getMethods(user);
Field f = ClassUtils.findField(User.class, "id");

ClassUtils.isPrimitive(int.class);
ClassUtils.isJavaType(String.class);
ClassUtils.getClassPath();
ClassUtils.registerClassLoader(loader);   // 显式托管，避免泄漏
```

构造失败时可通过 `consumeLastInstantiationFailure()` 取最近一次失败原因（`NewInstanceFailure`）。

### 4.6 FileUtils / IoUtils

```java
import com.chua.common.support.utils.FileUtils;
import com.chua.common.support.utils.IoUtils;

FileUtils.getExtension("a.tar.gz");      // "gz"
FileUtils.getBaseName("/x/a.txt");       // "a"
FileUtils.removeExtension("a.txt");      // "a"
FileUtils.readableFileSize(1048576);     // "1 MB"
FileUtils.normalize("a", "b", "c.txt");  // "a/b/c.txt"
FileUtils.mkParentDirs(file);
FileUtils.deleteQuietly(dir);

byte[] bytes = IoUtils.asBytes(file);            // 也支持 URL / Path / InputStream
String text  = IoUtils.asString(inputStream, StandardCharsets.UTF_8);
IoUtils.copy(input, output);
IoUtils.copyLarge(reader, writer);
IoUtils.closeQuietly(stream);
```

> 本模块的 `FileUtils` **只做路径/命名/大小/删除**这类轻量操作，没有 `readFileToString` / `writeStringToFile`；读写请用 `IoUtils` 或 `FileSystem`。

### 4.7 DigestUtils

```java
import com.chua.common.support.utils.DigestUtils;

DigestUtils.md5("abc");
DigestUtils.md5Hex("abc");
DigestUtils.sha256("abc");
DigestUtils.sha512("abc");
DigestUtils.sm3("abc");
DigestUtils.hmacSha256("data", "secret");
byte[] raw = DigestUtils.hash("SHA-256", "abc");

DigestUtils.hash(byte[] data);       // 默认 SHA-256
DigestUtils.md2 / sha1 / sha224 / sha384 ...
```

算法常量齐全：MD2/MD5、SHA-1/224/256/384/512、SHA3、SM2/SM3/SM4、AES/DES/3DES、CRC32/Adler32、HmacMD5/HmacSHA1/HmacSHA256/HmacSHA512、PBKDF2WithHmacSHA256。

### 4.8 IdUtils

```java
import com.chua.common.support.utils.IdUtils;

IdUtils.uuid();             // 标准 UUID
IdUtils.uuidv7();           // UUID v7（时间有序）
IdUtils.simpleUuid();       // 无连字符
IdUtils.fastUuid();         // 无连字符、更快
IdUtils.timeId();
IdUtils.createSnowflakeId();
IdUtils.createDailySequenceId("ORD");   // ORD202601010001
IdUtils.createDeviceCode("CAM");
IdUtils.createDataFinger();             // 内容指纹
IdUtils.isSameData(a, b);
```

### 4.9 BufferedImageUtils

`java.awt` 图像处理（不是第三方图像库）：

```java
import com.chua.common.support.utils.BufferedImageUtils;

BufferedImage img = BufferedImageUtils.toBufferedImage(file);   // 也支持 byte[]/URL/Path/Stream
BufferedImage small = BufferedImageUtils.scaleImage(img, 200, 150);
BufferedImage crop  = BufferedImageUtils.cropImage(options);    // CropImageOptions
BufferedImage rot   = BufferedImageUtils.rotate(img, 90);
BufferedImage gray  = BufferedImageUtils.grayImage(img);
BufferedImage blur  = BufferedImageUtils.blurImage(img, 3f);
BufferedImage mark  = BufferedImageUtils.addTextWatermark(img, "© chua");
BufferedImage[] gif  = BufferedImageUtils.readGifFrames(file);
BufferedImageUtils.writeGif(frames, 100, out);
BufferedImageUtils.writeToFile(img, "png", file);
```

### 4.10 其它工具速查

| 类 | 能力 |
| --- | --- |
| `NumberUtils` | 加减乘除（自动 BigDecimal）、四舍五入、`isNumber`/`isInteger`、中文数字、方差/标准差 |
| `DateUtils` | `Date`/`LocalDate*`/`Instant`/`ZonedDateTime` 互转，`format`/`parseDateSafe`、周/月首末天 |
| `BeanUtils` | `copyProperties`（含忽略字段、Map↔Bean）、`copyPropertiesList`、`objectToMap` |
| `TreeUtils` | `build` 列表转树、`findNode`/`findById`、`getParents`/`getDepth`、`flatten` |
| `Validator` | 手机号、邮箱、身份证、IP、URL、纯数字/字母、含中文 |
| `UrlUtils` | URL 编解码、路径规范化、`SimpleUrlEncoder` |
| `SystemUtils` | 主机指纹：`uniqueId()`、`verifyId()`、`hostFacts()` |
| `RandomUtils` / `ThreadUtils` / `MathUtils` / `ByteUtils` / `CharUtils` / `BitUtils` | 随机、线程池命名与等待、数学、字节/字符/位操作 |
| `PrivacyUtils` / `SignUtils` / `PageUtils` / `MediaTypeUtils` | 脱敏、签名、分页、MIME 推断 |
| `GzipBombUtils` | GZIP 炸弹检测 |

---

## 5. 集合与并发

### 5.1 集合增强（`collection`）

| 类型 | 说明 |
| --- | --- |
| `MultiValueMap` / `MultiLinkedValueMap` | 一键多值 |
| `CaseInsensitiveMap`（`lang.bean` 侧）/ `FlatMap` / `FlatHashMap` | 大小写不敏感、扁平化 |
| `ConcurrentReferenceHashMap` | 软/弱引用，防内存泄漏（SPI 缓存即用此实现） |
| `CircularArrayList` / `CircularQueue` / `CircularLinkedSet` | 环形结构 |
| `BitList` / `IntList` / `LongList` | 原始类型列表 |
| `SortedArrayList` / `SortedList` | 有序列表 |
| `BoundedCollection` / `OverflowPolicy` | 有界集合与溢出策略 |
| `DataStore` / `OnHeapDataStore` / `OffHeapDataStore` | 堆内/堆外数据存储 |
| `Multiset` | 计数集合 |
| `collection.wrap.Wraps` | 统一集合创建入口，底层可切 Eclipse Collections（`CollectionBackend` SPI） |

```java
import com.chua.common.support.collection.MultiValueMap;
import com.chua.common.support.collection.MultiLinkedValueMap;
import com.chua.common.support.collection.ConcurrentReferenceHashMap;
import com.chua.common.support.collection.wrap.Wraps;

// 一键多值
MultiValueMap<String, String> tags = new MultiLinkedValueMap<>();
tags.add("tag", "a");
tags.add("tag", "b");
tags.get("tag");          // [a, b]
tags.getFirst("tag");     // a
tags.toSingleValueMap();  // 取每个 key 的第一个值

// 弱引用缓存（SPI 缓存同款）
Map<String, Object> cache = new ConcurrentReferenceHashMap<>(256);

// 统一创建入口（后端可切换）
List<String> list = Wraps.list();
Map<String, Object> map = Wraps.map();
```

### 5.2 并发（`concurrent`）

| 子包 | 能力 |
| --- | --- |
| `lock` | `LockProvider` SPI：`semaphore`、`circular`，另有文件锁 / 对象锁 |
| `rate` | `RateLimiter`，Guava 实现（SPI `guava`） |
| `circuitbreaker` / `circuit` | 熔断器与判定器，内存实现 |
| `bulkhead` | 舱壁隔离 |
| `collapse` | 请求合并 |
| `dispatcher` | 统一缓冲派发（内存实现 + Jackson 序列化） |
| `queue` | 并发队列封装 |
| `threadflow` / `structured` | 线程流与结构化并发 |
| `timeout` / `backoff` | 超时与退避策略 |
| `offset` | `OffsetStore`（文件实现），用于断点消费 |
| `pool` | `pool.PooledObjectClient`、`concurrent.pool` 连接池 |

```java
import com.chua.common.support.concurrent.lock.LockProvider;
import com.chua.common.support.spi.ServiceProvider;

LockProvider lock = ServiceProvider.of(LockProvider.class).getExtension("semaphore");
```

限流与熔断：

```java
import com.chua.common.support.concurrent.rate.RateLimiterFlow;
import com.chua.common.support.concurrent.circuit.CircuitBreaker;
import java.util.concurrent.TimeUnit;

// 限流：100 QPS，拿不到许可时走降级
RateLimiterFlow.of("api", 100.0)
        .timeout(500, TimeUnit.MILLISECONDS)
        .fallback(() -> "busy")
        .execute(() -> doSomething());

// 表达式熔断/规则判定
boolean allow = CircuitBreaker.evaluate("age > 18 AND status == 'active'",
        Map.of("age", 25, "status", "active"));
```

| 组件 | 说明 |
| --- | --- |
| `RateLimiterFlow` | 限流门面：`tryAcquire`、`timeout`、`fallback`、`execute` |
| `CircuitBreaker` | 表达式驱动的断路器（B-Tree 遍历 + 短路 + 异常保守断路） |
| `circuitbreaker.*` | 统计型断路器 Provider（失败率/慢调用） |
| `BulkheadFlow` | 舱壁隔离（信号量） |
| `CollapseFlow` | 请求合并（同一 key 的并发请求合并为一次） |
| `StructuredConcurrencyFlow` | `StructuredTaskScope` 封装 |

### 5.3 对象池与连接池（`concurrent.pool`）

```java
import com.chua.common.support.concurrent.pool.ConnectionPool;

try (ConnectionPool pool = ConnectionPool.builder()
        .url("jdbc:mysql://127.0.0.1:3306/demo")
        .username("root")
        .password("123456")
        .maxTotal(20)
        .maxIdle(8)
        .minIdle(2)
        .borrowTimeoutMillis(3_000)
        .validationQuery("SELECT 1")
        .testOnBorrow(true)
        .build()) {

    try (Connection conn = pool.borrow()) {
        // 使用连接；关闭时归还
    }
    pool.getStats();

    // 或使用守卫自动归还任意对象
    try (var guard = pool.guard()) {
        Connection c = guard.get();
    }
}
```

`GenericObjectPool` + `ObjectPoolConfig` + `ObjectFactory` 提供通用对象池：`borrow` / `returnObject` / `invalidateObject` / `guard`，含空闲回收、耗尽/超时异常（`PoolExhaustedException`、`PoolTimeoutException`）。

---

## 6. 文件、归档与存储

### 6.1 文件系统 SPI（`file`）

内置实现（`META-INF/extensions/...FileSystem`）：

| key | 实现 | 说明 |
| --- | --- | --- |
| `csv` | `CsvFileSystem` | CSV/TSV 读写 |
| `json` | `JsonFileSystem` | JSON / JSON5 |
| `xml` | `XmlFileSystem` | XML |
| `txt` | `TxtFileSystem` | 纯文本 |
| `zip` / `archive` | `ZipFileSystem` | ZIP，支持分卷 |
| `tar` | `TarFileSystem` | TAR，支持 gz 与分卷 |

外部模块补充：`zip4j`（密码+分卷）、`7z`、`yaml`、`excel`、`pdf`、`word` 等，见 `utils-support-filesystem-starter`。

```java
FileSystem zip = FileSystem.create("zip");

zip.write(new File("out.zip"))
   .addFile("a.txt", bytes)
   .addDirectory(new File("./assets"), "")
   .finish();

zip.read(new File("out.zip"))
   .extractAll(new File("./out"));
```

`ReadBuilder` / `WriteBuilder` 是统一读写 DSL；`FileSystem.auto(file)` 按扩展名推断实现。

### 6.2 归档与压缩流（`io`）

`ArchiveInputStream` / `ArchiveEntry` / `CompressInputStream` / `CompressArchiveOutputStream` 等接口由各格式实现，供下载解压与文件系统复用；`stream.StreamCompressor` 提供流式压缩 SPI。

### 6.3 对象存储（`storage`）

```java
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.request.*;
import com.chua.common.support.storage.setting.BucketSetting;

BucketSetting setting = new BucketSetting();
FileStorage storage = FileStorage.createStorage("s3", setting);

storage.putObject(PutObjectRequest.of(new File("a.png"), "dir/a.png"));
GetObjectResult got = storage.getObject("dir/a.png");
boolean exists = storage.existObject(
        ExistObjectRequest.builder().fileName("a.png").filePath("dir").build()).isExists();
ListObjectResult list = storage.listObject(
        ListObjectRequest.builder().filePath("dir").limit(100).build());
storage.deleteObject("dir/a.png");
```

`supportsRange()` 表示实现是否原生支持 `offset/length`；`createMultipartStorage()` 返回分片上传能力。

### 6.4 WAL（`wal`）

预写日志，三种分片形态共用 `WalLog` 契约：

```java
import com.chua.common.support.wal.*;

try (WalLog wal = WalFactory.open(WalConfig.builder()
        .walDir(Path.of("./wal"))
        .namespace("orders")
        .impl(WalConfig.WalImpl.SEGMENT)
        .syncOnWrite(true)
        .maxSegmentBytes(64L * 1024 * 1024)
        .build())) {

    long lsn = wal.append((byte) 1, payload);           // byte op + payload
    wal.appendChain(chain -> {                          // 链式事务：回调内追加，返回后统一刷盘
        chain.add((byte) 1, payloadA);
        chain.add((byte) 2, payloadB);
    });

    WalReplayResult result = wal.replay((lsn0, op, bytes) -> {
        restore(lsn0, op, bytes);
        return true;                                    // false = 中止回放
    });

    wal.markCheckpoint(result.lastLsn());               // 业务持久化完成后推进
    wal.purgeCheckpointed(1);                            // 清理旧分片
}
```

| 能力 | 说明 |
| --- | --- |
| `append(byte op, byte[] payload)` | 追加，返回 LSN |
| `appendChain(WalChainHandler)` | 链式事务，回调内 `chain.add(op, payload)` |
| `replay(handler)` | 从 checkpoint 之后回放，成功后自动推进 checkpoint |
| `replay(long fromLsn, handler)` / `replay(long, long, handler)` | 指定范围回放 |
| `loadCheckpoint` / `markCheckpoint` / `resetCheckpoint` / `forceCheckpoint` | checkpoint 管理 |
| `findByLsn` / `listSegments` / `currentSegment` | 定位与分片信息 |
| `purgeCheckpointed(keep)` | 删除已 checkpoint 的旧分片 |
| 实现 | `SimpleWalLog`（单文件）、`SegmentWalLog`（分片+索引）、`CHRONICLE`/`KAFKA`（由外部 starter 提供） |

存储侧另有 `datasource.wal.WalStoreSystem`（`wal-kv` / `wal-ts` / `wal-vec` / `wal-filesearch`），把 WAL 与分片 B+Tree 索引组合成 KV/时序/向量/文件检索存储。

### 6.5 目录监听（`lang.directory`）

```java
import com.chua.common.support.lang.directory.*;

DirectoryWatcher watcher = new DirectoryWatcher("./watch")
        .addListener(new SimplePolledListener() {
            @Override
            public void onCreate(WatcherEvent event, EventObserver observer) {
                System.out.println("新增: " + observer.getFullPath());
            }

            @Override
            public void onModify(WatcherEvent event, EventObserver observer) {
                System.out.println("修改: " + observer.getFullPath());
            }

            @Override
            public void onDelete(WatcherEvent event, EventObserver observer) {
                System.out.println("删除: " + observer.getFullPath());
            }
        });

watcher.start();   // 后台线程消费 WatchService
// ...
watcher.stop();
```

`PolledListener` 的 5 个回调全部是 `default`：`onCreate` / `onModify` / `onDelete` / `onOverflow` / `onError`，按需覆写。`DiffPolledDirectory` 与 `PolledDirectory`（SPI `filesystem`）提供「快照 + 差分」的轮询式实现；`EventObserver` 携带路径、文件名、时间戳。

---

## 7. 网络

### 7.1 HTTP 客户端（`network.client`）

同步返回 `ClientResponse`，异步返回 **Reactor `Mono`**（不是 `CompletableFuture`）。

```java
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.client.ClientResponse;

ClientResponse resp = HttpClientFactory.of("https://api.example.com/users")
        .header("Authorization", "Bearer token")
        .query("page", "1")
        .readTimeout(10_000)
        .get();

if (resp.isSuccess()) {
    System.out.println(resp.getBodyString());
    String trace = resp.getHeader("X-Trace-Id");
}
```

```java
// POST JSON
HttpClientFactory.of("https://api.example.com/users")
        .json()
        .body("{\"name\":\"张三\"}")
        .post();

// 表单 / 文件上传
HttpClientFactory.of("https://api.example.com/upload")
        .formData("file", bytes, "image/png", "photo.png")
        .formData("desc", "hello")
        .post();

// 鉴权
HttpClientFactory.of(url).auth(token).get();          // Bearer
HttpClientFactory.of(url).authBasic("admin", "123").get();

// 异步（Mono）
HttpClientFactory.of(url)
        .getAsync()
        .map(ClientResponse::getBodyString)
        .subscribe(System.out::println);

// 回调
HttpClientFactory.of(url).getAsync(r -> System.out.println(r.getBodyString()));

// cURL 互转
ClientResponse r = HttpClientFactory.curl("curl -X POST https://x -d 'a=1'");
String curl = HttpClientFactory.of(url).header("A", "b").toCurl();
```

#### 底层执行器 SPI：实现可以换

HTTP 客户端是**两层**结构，日常用的 `HttpClient` 不绑定具体库：

```
HttpClient（统一接口，返回 ClientResponse / Mono）
   └── DefaultHttpClient（拦截器链 + 委托）
         └── HttpClientExecutor（SPI：okhttp / httpclient5 / httpclient / jdk）
```

| SPI 名称 | 实现 | 提供模块 | 说明 |
| --- | --- | --- | --- |
| `okhttp` | `com.chua.okhttp.support.executor.OkHttpExecutor` | `utils-support-okhttp-starter` | 需 okhttp3 依赖 |
| `httpclient5` | `HttpClient5Executor` | `utils-support-httpclient-starter` | Apache HttpClient5 |
| `httpclient` | 预留名称（HttpClient4） | 无内置实现 | 未注册时被跳过 |
| `jdk` | `JdkHttpClientExecutor` | 本模块内置 | 无外部依赖，始终可用；异步走 `sendAsync()` |

选择规则：

1. `HttpClientFactory.getClient()` 按 **okhttp → httpclient5 → httpclient → jdk** 顺序找第一个 `isAvailable() == true` 的执行器；
2. 全部不可用（例如没引入 okhttp/httpclient5）时回退 JDK；
3. 想强制指定，用 `getClient("okhttp")`；指定名称不可用时同样回退 JDK，不会抛错。

```java
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.network.http.HttpMethod;

HttpClient okhttp = HttpClientFactory.getClient("okhttp");       // 强制 OkHttp
HttpClient hc5    = HttpClientFactory.getClient("httpclient5");  // 强制 HttpClient5
HttpClient jdk    = HttpClientFactory.getClient("jdk");          // 强制 JDK
HttpClient fresh  = HttpClientFactory.newClient();               // 不缓存的新实例
HttpClient global = HttpClientFactory.getClient();               // 全局单例（自动选）

// 强制实现 + 链式请求（HttpClientFactory.of(...) 用的是全局单例）
ClientResponse resp = okhttp.request("https://api.example.com/users", HttpMethod.GET)
        .header("Authorization", "Bearer token")
        .query("page", "1")
        .execute();
```

自定义执行器（例如接入 Netty 或公司内部 HTTP 栈）：

```java
@Spi("my-http")
public class MyExecutor implements HttpClientExecutor {
    @Override public String getName() { return "my-http"; }
    @Override public boolean isAvailable() { return true; }
    @Override public int getOrder() { return 50; }   // 越小越优先
    @Override public ClientResponse execute(ClientRequest request) throws Exception {
        // 发请求并转换成 ClientResponse
    }
}
```

```text
src/main/resources/META-INF/extensions/com.chua.common.support.network.client.spi.HttpClientExecutor
my-http=com.example.MyExecutor
```

注册后即可 `HttpClientFactory.getClient("my-http")`；`DefaultHttpClient` 会自动套上应用层/网络层拦截器链。

其它能力：`connectTimeout` / `readTimeout` / `keepAliveTimeout`、`proxy(host, port)`、`interceptor(...)`、`onRedirect(...)`、`path(...)`、`params(Map)`、`method(HttpMethod)`、`CollapseHttpClient`（请求合并）、`TtlResponseCache`（TTL 缓存）、`HttpApiFactory`（接口式声明）。

### 7.2 服务端（`network.server`）

`Server` 是协议无关接口（`start/stop/isRunning/getPort/getServerUrl`），实现按 SPI 名称装配：

| 类别 | SPI 名称 | 实现 |
| --- | --- | --- |
| HTTP | `jdk` / `jdk-http`、`nio` / `nio-http`、`aio` / `aio-http` / `iocp` | `JdkHttpServer`、`NioHttpServer`、`AioHttpServer` |
| TCP | `jdk-tcp` / `tcp`、`aio-tcp` / `tcp-aio` | `JdkTcpServer`、`AioTcpServer` |
| UDP | `jdk-udp` | `JdkUdpServer` |
| Telnet | `jdk-telnet` | `JdkTelnetServer` |
| WebSocket | `jdk-websocket` | `JdkWebSocketServer` |
| FTP | `ftp` / `ftps` | `FtpServer` / `FtpsServer` |
| SIP | `sip` | `SipServer` |
| 协议嗅探 | `sniff-tcp` | `ProtocolSniffingTcpServer` |
| 代理 | `http-proxy`、`socks5-proxy`、`tcp-proxy`、`aio-http-proxy`、`aio-tcp-proxy` | 对应 `*ProxyServer` |

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.network.http.HttpMethod;

Server server = ServerBuilder.create()
        .type("jdk")                 // jdk / nio / aio
        .host("0.0.0.0")
        .port(8080)
        .mapping("/hello", HttpMethod.GET, (req, resp) -> resp.setResult("Hello"))
        .mapping("/api/users", HttpMethod.POST, userHandler)
        .build();

server.start();
System.out.println(server.getServerUrl());   // http://localhost:8080/
server.isRunning();
server.stop();

// 或：Server server = Server.builder().port(8080).build();
```

- `ServerSetting`：线程数、`maxConnections`、`backlog`、`tcpNoDelay`、空闲超时、缓冲区、CORS、安全头、SSL、HTTP/2、Reactor 模式等。
- 共享容器：`ServerBuilder.objectContext(ctx)`，多个 Server 复用同一套 Bean。
- 过滤器 SPI：`ServerFilter`（`ip`、`firewall`、`rate-limit`、`routing`、`request-id`、`request-timeout`、`ipc`、`response-rewrite`、`gzip`、`cors`、`ws-proxy`、`url-mapping`、`invoker_inject`、`ai_token`）。
- 注解驱动：`ServerHandlerAnnotationParser`（`request-method`、`ipc-method`）、`HandlerMethodArgumentResolver`、`ListenerParser`（`javax` / `custom` / `objectcontext`）。

### 7.3 同步（`network.sync`）

`SyncServer` / `SyncClient` 四套传输：`http`、`websocket`、`tcp`、`udp`。旧版 `com.chua.common.support.sync`（Input/Output/Sink）仍保留，但新项目请用 `utils-support-datasync-starter`。

### 7.4 发现与对等网格（`network.discovery`、`scatter`）

- `ServiceDiscovery` SPI：`peermesh` 实现，自带节点表与故障剔除。
- `scatter`：无中心化集群，帧协议最小化，TCP/UDP 两种传输，seed 引导 + 自动发现。

```java
import com.chua.common.support.scatter.Scatter;
import com.chua.common.support.scatter.ScatterBuilder;

try (Scatter scatter = ScatterBuilder.tcp()
        .nodeId("node-1")
        .groupId("order")
        .port(19001)
        .seeds("192.168.1.10:19001")
        .build()) {
    scatter.start();
}
```

### 7.5 下载（`network.download`）

```java
import com.chua.common.support.network.download.Downloader;
import com.chua.common.support.network.download.DownloadResult;

DownloadResult r = Downloader.create()
        .url("https://example.com/tool.zip")
        .target(Path.of("./downloads"))
        .filename("tool.zip")
        .concurrency(4)
        .maxSpeed(20L * 1024 * 1024)     // 20MB/s
        .expectedMd5("...")              // 校验；配合 skipMd5Check(false)
        .autoExtract(true)
        .extractTo(Path.of("./tools"))
        .showProgress(true)
        .connectTimeout(10_000)
        .readTimeout(60_000)
        .header("User-Agent", "chua")
        .execute();
```

| 能力 | 说明 |
| --- | --- |
| 多线程 | `concurrency(n)` |
| 限速 | `maxSpeed(bytesPerSecond)` |
| 断点/续传 | `resumeOffset`（`DownloadConfig`） |
| 校验 | `expectedMd5` + `skipMd5Check` |
| 自动解压 | `autoExtract` + `extractTo`，内置 `zip` / `tar` / `tar.gz` / `tgz` / `gz` 解压器 |
| 代理 | `proxy(Proxy)` |
| 实现 | `DownloadService` SPI：`default`、`aria2`（需 aria2c） |

### 7.6 其它网络能力

| 子包 | 能力 |
| --- | --- |
| `network.sse` | `SseClient` / `JdkSseClient`、`SseConnection`、`SseEvent`、`SseListener`，SSE 长连接与事件解析 |
| `network.dns` | `DnsProvider`、`DnsConfig`、`DnsRecord`（阿里/腾讯/Cloudflare 等由 starter 实现） |
| `network.ip` | `IpLocation`、`IpPosition`、`GeoCity`、`GeoSetting`（IP 归属地） |
| `network.ssl` | `JdkCertificateProvider`、`TlsKeyLogDecoder`（TLS 解密调试） |
| `network.ftp` | `FtpConfig`、FTP/FTPS 服务端与客户端 |
| `network.sip` | `SipServer`、SIP 压缩器 |
| `network.rpc` | `RpcClient` / `RpcServer`（`native`）、`RpcInvoker` |
| `network.invoker` | HTTP / IPC / RPC 统一调用器 |
| `network.tunnel` | 内网穿透隧道抽象 |
| `network.filepush` | 文件推送 |
| `network.voice` | 语音通道 |
| `network.bench` | `HttpBench` HTTP 压测 |

---

## 8. 任务与流程

### 8.1 调度（`task.scheduler`）

```java
import com.chua.common.support.task.scheduler.TimeScheduler;
import java.util.concurrent.TimeUnit;

TimeScheduler scheduler = TimeScheduler.of();

ScheduledTask t1 = scheduler.scheduleCron("nightly", () -> sync(), "0 0 2 * * ?");
ScheduledTask t2 = scheduler.scheduleFixedRate("poll", () -> poll(), 10, TimeUnit.SECONDS);

scheduler.isRunning("poll");
scheduler.cancel("poll");
scheduler.shutdown();
```

底层 `SchedulerProvider`（JDK 实现）+ `Trigger`（`Cron` / `Fixed` / `Simple`），`CronExpression` 内置解析。

### 8.2 流程（`task.flow`）

本模块只定义流程契约与节点模型，**执行实现位于 `utils-support-flow-starter`**：

- `Flow`：注册节点、`createGraph()`、`exportJson()` / `importJson()`（与前端 ReFlow 画布格式一致）。
- `FlowGraph` / `FlowInstance` / `FlowContext` / `FlowTrace`。
- 内置节点：`StartNode`、`EndNode`、`ConditionNode`、`TransformNode`、`LogNode`、`HttpCallNode`、`SpiderNode`。
- `FlowJson`、`FlowDefinition`、`FlowProps`、`FlowNodeRegistry` 负责序列化与节点登记。

> 旧版 `com.chua.common.support.orchestrator.Orchestrator` 已移除，请改用 flow-starter。

### 8.3 管道与重试（`task.pipeline`、`task.retry`、`task.cooldown` 等）

| 子包 | 能力 |
| --- | --- |
| `pipeline` | 管道编排（30 个类型） |
| `retry` | 重试策略与退避 |
| `cooldown` | 冷却/节流 |
| `deduplicate` | 去重（内存实现） |
| `backup` / `restore` | 备份恢复 |
| `script` | 脚本任务（Java、Python 提供者） |
| `classifier` | 任务分类 |
| `message` | 任务消息 |
| `taskrunner` | 结构化执行器 |
| `async` / `timer` / `branch` / `loader` | 异步、定时器、分支、加载器 |

### 8.4 分布式任务（`taskdistribution`）

`TaskManager`（内存/持久存储）+ `TaskStore`（`FileTaskStore` / `InMemoryTaskStore`）+ `DispatcherProvider` + `TaskExecutor` SPI。

```java
import com.chua.common.support.taskdistribution.manager.TaskManager;
import com.chua.common.support.taskdistribution.store.FileTaskStore;
import com.chua.common.support.taskdistribution.task.Task;

TaskManager manager = new TaskManager();
manager.setStore(new FileTaskStore("./tasks"));
manager.addStateListener(new TaskStateListener() {
    @Override
    public void onStateChanged(String id, TaskStatus from, TaskStatus to) {
        log.info("{}: {} -> {}", id, from, to);
    }
});

Task<String> task = Task.<String>builder()
        .taskId("t-1")
        .taskType("demo")
        .payload("hello")
        .build();

manager.addTask(task, result -> onDone(result));
manager.pause("t-1");
manager.resume("t-1");
manager.cancel("t-1");
manager.getAllTasks();      // List<TaskView>
manager.recover();          // 重启后恢复
manager.cleanCompleted();
```

### 8.5 流水线（`task.pipeline`）

与 `task.flow`（图编排、可导出 JSON）不同，`Pipeline` 是**任务级**的顺序/分支/并行执行器，自带 WAL 与恢复。

```java
import com.chua.common.support.task.pipeline.builder.PipelineBuilder;
import com.chua.common.support.task.pipeline.core.Pipeline;
import com.chua.common.support.task.pipeline.core.PipelineContext;

Pipeline pipeline = PipelineBuilder.newBuilder("order")
        .task("validate", ctx -> { validate(ctx.getCurrentData()); return null; })
        .taskEnd()
        .decision("check", ctx -> ctx.getCurrentData() != null ? "process" : "error")
        .task("process", ctx -> { process(ctx.getCurrentData()); return null; })
        .taskEnd()
        .task("error", ctx -> { log.warn("invalid"); return null; })
        .taskEnd()
        .onStart(ctx -> log.info("start"))
        .onComplete(ctx -> log.info("done"))
        .onError((ctx, e) -> "error")     // 返回恢复节点；null = 终止
        .build();

PipelineContext<String> ctx = pipeline.execute("payload");
ctx.getHistory();
```

| 能力 | 说明 |
| --- | --- |
| 节点 | `StartNode`、`EndNode`、`TaskNode`、`DecisionNode`、`ForkNode`、`ParallelNode`、`SubPipelineNode` |
| 动态路由 | `task` 返回节点 id 即跳转；`RouteStrategy` 控制目标不存在时的行为 |
| 并行/分叉 | `Parent`/`Fork` 定义 + `ForkErrorStrategy` |
| 异步 | `pipeline.executeAsync(input)` 返回 `Mono<PipelineContext<T>>` |
| 事件 | `PipelineListener`、`LoggingListener` |
| 持久化 | `PipelineWal` 记录执行进度，支持恢复 |
| JSON | `PipelineJsonParser`、`PipelineBuilder.fromJson(json)` |

---

## 9. AI

### 9.1 ChatClient

`ai.chat.ChatClient` 是统一对话门面，provider 由 SPI 决定（`openai`、`claude`、`gemini`、`deepseek`、`zhipu`、`qwen` 等由 `cloud-parent` 各模块注册）。

```java
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;

ChatClient client = ChatClient.create("openai", "sk-xxx");

String answer = client
        .model("gpt-4o-mini")
        .system("你是一名严谨的工程师")
        .temperature(0.7)
        .maxTokens(2048)
        .chatSync("解释一下 WAL");

// 带用量
ChatSyncResponse r = client.chatSyncWithResponse("你好");
r.getText();
r.getUsage();

// 流式
client.chat("讲个故事", resp -> {
    switch (resp.getState()) {
        case START -> System.out.println("[start]");
        case STREAMING -> System.out.print(resp.getContent());
        case STOP -> System.out.println("\n[done]");
        case ERROR -> System.err.println(resp.getErrorMessage());
    }
});

// 异步
client.chatAsync("hi").thenAccept(resp -> System.out.println(resp.getText()));

client.close();
```

链式能力（均已验证）：`provider`、`model`、`system`、`temperature`、`maxTokens`、`topP`、`stop`、`seed`、`responseFormat`、`thinking` / `thinkingEffort`、`smartSearch`、`tools` / `tool` / `toolChoice`、`skill` / `skillPrompt`、`addImage`、`addAttachment` / `addAttachmentUrl`、`addUserHistory` / `addAssistantHistory` / `history`、`session` / `newChat`、`extraBody` / `extraHeaders`、`authScheme`。

扩展能力：`generateImage(...)` / `generateImage()`（`ImageGenerationSpec`）、`generateVideo(...)` / `generateVideo()`（`VideoGenerationSpec`）、`models()` / `listModels()` / `modelPricing()`、`indicator()`（模型指标端点）、`probe()`（中转站真伪探测）。

**兜底规则**：provider 未注册且设置了 `baseUrl` 时，自动按 OpenAI 兼容协议走 `openai` 扩展。

配套 SPI：`ChatClient`（`memory` 内置）、`EmbeddingClient`（`memory`）、`RagClient`（`memory` + `local` 上传）、`TextSplitterProvider`（`sentence`）、`RuleBuilder`（`native`）、`ProgressEstimator`（5 种进度估算）。

### 9.2 其它 AI 子包

| 包 | 能力 |
| --- | --- |
| `ai.agent` | Agent 定义、工具调用、执行循环 |
| `ai.rag` | 检索增强、切分、上传 |
| `ai.embedding` | 向量化 |
| `ai.mcp` | MCP 协议客户端/服务端模型 |
| `ai.skill` | 技能（提示词注入 + SkillManager） |
| `ai.decision` | 决策问答协议 |
| `ai.feature` / `ai.probe` / `ai.calibration` / `ai.branch` | 特征、探测、校准、分支 |
| `ai.audio` / `ai.image` / `ai.video` / `ai.generation` | 音频、图像、视频生成 |
| `ai.memory` / `ai.context` / `ai.runtime` | 记忆、上下文、运行时 |
| `ai.rule` / `ai.translate` / `ai.bot` | 规则、翻译、机器人 |

### 9.3 RAG（`ai.rag`）

```java
import com.chua.common.support.ai.rag.RagClient;
import com.chua.common.support.ai.rag.RagClientSetting;
import com.chua.common.support.ai.rag.RagResponse;

RagClientSetting setting = RagClientSetting.builder().build();

RagClient rag = RagClient.create("memory", setting)
        .topK(10)
        .similarityThreshold(0.5)
        .system("你是一名技术文档助手，只依据给定文档回答");

rag.uploadDocument("guide.pdf", pdfBytes);      // 导入
RagResponse resp = rag.query("如何配置数据源？");
resp.answer();
resp.sources();                                  // 命中文档片段（documentId/content/score）

rag.queryStream("如何配置数据源？", s -> System.out.print(s));
rag.close();
```

| 类型 | 说明 |
| --- | --- |
| `RagClient` | 统一门面：导入、查询、流式查询 |
| `RagPipeline` | 切分 → 向量化 → 存储 → 检索 → 生成 |
| `VectorService` | 向量写入/检索适配 |
| `TextSplitter` / `TextSplitterProvider` | 文本切分（内置 `sentence`） |
| `EmbeddingClient` | 向量化（内置 `memory`） |
| `KnowledgeClient` | 知识库管理 |
| `LocalFileUploadProvider` | 本地文件导入（SPI `local`） |

---

## 10. 数据、WAL 与向量

### 10.1 数据源引擎（`lang.datasource.engine`）

`Engine` 提供 ORM/查询能力，`DdlProvider`、`DslManager`、`EngineInterceptor` 可 SPI 扩展；`lang.datasource.flyway` 提供 `Flyway` / `ScriptConverter` 迁移；`lang.datasource.series` 提供 `SeriesEngine`（`datasource` 实现）。

### 10.2 JOIN 策略（`datasource.join`）

`JoinStrategy`：`none`、`inner`，用于多数据源内存 JOIN。

### 10.3 向量存储（`vector`）

```java
import com.chua.common.support.vector.VectorStorage;
import com.chua.common.support.vector.VectorStorageBuilder;
import com.chua.common.support.vector.Vector;

try (VectorStorage storage = VectorStorageBuilder.newBuilder()
        .type("memory")
        .dimension(768)
        .algorithm("COSINE")
        .build()) {

    storage.add(new Vector("doc-1", new float[]{ /* ... */ }));
    List<Vector> top = storage.search(query, 10);
    storage.remove("doc-1");
}
```

实现：`MemoryVectorStorage`（测试/小规模）、`DefaultVectorStorage`（冷热混合 + B+ 树索引 + mmap 零拷贝）。`VectorMath` 基于 `jdk.incubator.vector` 做 SIMD 距离计算；`RuntimeDetector` 为 SPI（内置 CUDA / cuVS 检测）。

### 10.4 树（`tree`）

`BTree` / `BPlusTree` 统一由 `TreeEngine` 抽象，`BinaryTreeConverter` 支持 B/B+ 树与二叉树互转，`TreeNode` 为转换节点。

---

## 11. 导出、文档与图像

### 11.1 导出（`export`）

两个入口，均在 `Documents`：

```java
import com.chua.common.support.export.Documents;
import com.chua.common.support.export.ExportResult;

// 接口一：List → 文档
ExportResult r1 = Documents.of(rows)
        .format("xlsx")
        .output(new File("out.xlsx"))
        .export();

// 接口二：模板替换
ExportResult r2 = Documents.ofTemplate(new File("template.docx"))
        .put("name", "张三")
        .put("items", items)
        .output(new File("out.docx"))
        .export();
```

SPI：`DocumentGenerator`（数据直出）、`TemplateFiller`（模板填充）、`MetaResolver`（列元数据：`javabean` / `third-party` / `export`）、`ValueConverter`。`Engines` 负责把「格式 + 引擎别名」解析成实现；输出目标由 `Outputs` 统一处理。

### 11.2 文档生成（`document`、`lang.document`）

`DocumentProvider`（`html` / `markdown` / `benchmark-html`）、`OpenApiDocumentProvider`（`html`）、`DocumentParser`、`DocumentTemplate`、`HtmlRenderer`、`MarkdownRenderer`。

### 11.3 图像（`image`、`codec.image`）

```java
import com.chua.common.support.image.ImageProcessors;

byte[] out = ImageProcessors.from(bytes)
        .resize(800, 600)
        .grayscale()
        .rotate(90)
        .toBytes("png");
```

`ImageProcessor` SPI：`jdk`、`rust`（由 rust 模块提供）；`ImagePipeline` 负责管线；`QrCode` 为二维码/条形码编解码接口。

### 11.4 视频编解码（`codec.video`）

`VideoEncoder` / `VideoDecoder` / `VideoFrame` / `VideoPacket` / `PixelFormat`，为 ffmpeg 等外部实现提供中性帧表示。

---

## 12. 平台与运维

| 包 | 能力 |
| --- | --- |
| `host` | 主机指纹：`HostAnchor` 锚点、`HostFacts` 快照、`HostIdentity.generate/verify/match`，支持 75% 相似度与最少匹配数 |
| `apm` | `OtnlEngine` SPI + SkyWalking 对齐模型（Service/Instance/Endpoint/Trace/Span/Topology/Alarm/Profile/Log/Metric）与 ORM 通道 |
| `service` | `ServiceBuilder`：本地/远程（SSH）启停、安装、卸载、状态；`PidFileProcessTracker` |
| `browser` | `BrowserEngine` SPI（`cdp` 实现）、`BrowserPage`、截图 |
| `osgi` | `OsgiLauncher`、`BundleContext`、`BundleLifecycleListener` |
| `modules` | `ModuleLoader`（JPMS 模块导出） |
| `env` | CUDA 环境下载与安装（`CudaEnvLoader` / `CudaEnvironmentInstaller`） |
| `setting` | `@GlobalSettingGroup` 全局设置分组 |

```java
import com.chua.common.support.service.ServiceBuilder;

ServiceBuilder.local()
        .withJar("./app.jar")
        .withServiceName("demo")
        .withPidFile("./demo.pid")
        .toLocation()
        .start();

ServiceBuilder.remote()
        .withJar("./app.jar")
        .withServiceName("demo")
        .toRemoteKey("10.0.0.8", 22, "root", "/root/.ssh/id_rsa")
        .connect()
        .start();
```

---

## 13. 其它子系统

| 包 | 说明 |
| --- | --- |
| `shmqueue` | 共享内存队列：`ShmQueue.create(name, capacity, slotSize, mode)` / `attach(name)`，`send` / `recv` / `recvTimeout`，带错误码常量 |
| `probability` | `ProbabilityCollision`（SPI `simple`），用于布隆/计数碰撞 |
| `value` | `Value` / `LazyValue` / `ExpireValue` / `NumberValue` |
| `tree` | B/B+ 树（见 10.4） |
| `matcher` | `MatcherUtils.matchPath` / `matchStart` / `extractTemplateVariables` / `getMatchingPattern` |
| `mock` | `MockEnvironment`、`MockString` |
| `math` | `math.unit.size.SizeValue.parse("12MB")`、`SizeUnit` |
| `language` | 拼音（`Pinyin`、`PinyinFactory`、`PinyinConverter`）、分词（`Tokenizer`、`Word`） |
| `sync` | 旧版同步模型（Input/Output/Sink/SyncFlow） |
| `proxy` | `ProxyFactory`、`ProxyUtils`、方法拦截（`Around`、`DelegateMethodIntercept`、`VoidMethodIntercept`、`BridgingMethodIntercept`） |
| `serialize` | `Serializer` / `SerializerProvider` / 反序列化策略 |
| `function` | `SafeOptional`、`SafeConsumer` / `SafeFunction` / `SafeSupplier` / `SafePredicate`、`NamedThreadFactory` |
| `base` | `Options`（字符串选项）、`Serialization`、`StringToConfigConverter`、校验分组 |
| `config` | 配置中心、加载器、`ConfigParser`（json / jsonl / properties / xml）、配置源 |
| `expression` | `ExpressionResolver`（`default`） |
| `document` | 文档生成请求/结果 |
| `enums` | `Action` 业务动作 |
| `stream` | `StreamCompressor` |
| `reflection` | `ReflectUtils`、`FieldStation` |
| `pool` | `PooledObjectClient` |
| `content` | `ObjectContent`（Map 底座） |

### 13.1 终端视图与表格渲染（`lang.view`）

```java
import com.chua.common.support.lang.view.ViewFormatter;

String box   = ViewFormatter.drawBoxedTable(ViewFormatter.extractRows(rows), 1);
String plain = ViewFormatter.drawBorderlessTable(ViewFormatter.extractRows(rows), 1);
String md    = ViewFormatter.drawMarkdownTable(ViewFormatter.extractRows(rows), 1);
```

`extractRows` 自动识别四种数据形态：

| 入参 | 渲染结果 |
| --- | --- |
| `Map` | 两列 `Key` / `Value` |
| `List<Map>` | 所有 key 的并集作为表头 |
| `List<POJO>` | 反射字段作为列（含父类） |
| `List<简单类型>` | 单列 `#` |

`ViewParser` SPI 提供更高层封装：`list`、`table`、`kv`、`card`、`tree`、`md`、`plain`、`json`、`barchart`。

### 13.2 模板抽取（`lang.template`、`lang.json`）

用「带占位符的模板」从另一份文本中反向取值：

```java
import com.chua.common.support.lang.json.JsonTemplateExtractor;
import com.chua.common.support.lang.template.TemplateExtractor;

TemplateExtractor extractor = new JsonTemplateExtractor();

Map<String, Object> vars = extractor.extract(
        "{\"id\":\"{id}\",\"name\":\"{name}\"}",     // 模板
        "{\"name\":\"张三\",\"id\":123}");            // 输入（顺序可不同）

boolean full = extractor.matches(template, input);
var result = extractor.extractResult(template, input);  // 含 missing()
var ordered = extractor.extractVars(template, input);   // 带路径，按模板顺序
```

`{变量名}` 支持整值占位与内嵌（`前缀{变量}后缀`），值保留原始类型。

### 13.3 表达式与脚本（`lang.ast`、`task.script`）

```java
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.spi.ServiceProvider;

ExpressionParser sql = ServiceProvider.of(ExpressionParser.class).getExtension("sql");
BTreeNode tree = sql.parse("age > 18 AND status = 'active'");
String back = sql.generate(tree);
```

| 能力 | 入口 |
| --- | --- |
| 表达式 AST | `ExpressionParser`：`sql`、`cypher`、`lucene`、`expr`（正反向解析） |
| 表达式求值 | `expression.ExpressionResolver`（SPI `default`） |
| 脚本执行 | `task.script.ScriptFlow.of("java")` / `of("python")`，`execute(Path, context)` / `executeBatch(Path, context)` |
| 脚本提供者 | `ScriptProvider`：`java`、`python`；`ScriptProviders.engines()` 查询可用引擎 |
| CLI 工具 | `lang.cmd.CliTool`：`tshark`、`aria2c`、`nvidia-smi`；`Cmd` / `Commands` 注册自定义命令 |

### 13.4 二维码与条码

`image.QrCode` 是编解码接口（`encode` / `decode`，支持 `QR_CODE`、`BARCODE`）；`lang.qr` 提供样式配置（前景/背景、码眼、圆点、Logo、容错级别）。

具体生成实现在 `utils-support-zxing-starter`：

```java
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.zxing.support.qr.DefaultQrCode;

DefaultQrCode qr = new DefaultQrCode(new QrSetting());
qr.out("https://example.com", outputStream);
```

### 13.5 树、值包装、代理、共享内存、概率

```java
// B+ 树
import com.chua.common.support.tree.BPlusTree;
BPlusTree<String, String> tree = new BPlusTree<>(64);
tree.put("k", "v");
tree.get("k");                 // Optional<String>
tree.range("a", "z");
tree.toBinaryTree();

// 值包装
import com.chua.common.support.value.LazyValue;
import com.chua.common.support.value.Value;
Value<String> lazy = LazyValue.of(() -> load());
lazy.getValue();

// 动态代理
import com.chua.common.support.proxy.ProxyUtils;
MyService proxy = ProxyUtils.proxy(MyService.class, MyService.class.getClassLoader(), handler);

// 共享内存队列（SPIN / BLOCK / HYBRID）
import com.chua.common.support.shmqueue.ShmQueue;
try (ShmQueue q = ShmQueue.create("/demo", 1024, 256, ShmQueue.Mode.HYBRID)) {
    q.send(1, bytes);
    ShmQueue.Message msg = q.recvTimeout(1_000_000_000L);
}

// 概率碰撞
import com.chua.common.support.probability.ProbabilityCollision;
ProbabilityCollision pc = ServiceProvider.of(ProbabilityCollision.class).getExtension("simple");
pc.hit(0.1);
pc.hitPercent(5);
```

### 13.6 SQL 格式化与高亮（`lang.format`）

```java
import com.chua.common.support.lang.format.Formatter;
import com.chua.common.support.lang.format.HighlightConfig;
import com.chua.common.support.lang.format.SqlFormatterFactory;
import com.chua.common.support.lang.format.SqlHighlighter;

Formatter formatter = SqlFormatterFactory.getFormatter(sql);   // 自动识别 DDL/DML
String pretty = formatter.format(sql);

String highlighted = SqlHighlighter.highlight(sql, new HighlightConfig());
```

### 13.7 主机指纹（`host`）

```java
import com.chua.common.support.host.HostIdentity;
import com.chua.common.support.utils.SystemUtils;

String id = SystemUtils.uniqueId();                 // 由不可变锚点生成
boolean same = SystemUtils.verifyUniqueId(id);       // 同机校验
HostIdentity.Match m = SystemUtils.verifyId(id);     // 相似度明细（matched/ratio/mismatchedFields）
```

锚点由 `HostAnchor` 声明（主板/CPU/磁盘/网卡等），`HostFacts` 是一次采集的快照；`HostIdentity` 支持 75% 相似度 + 最少 2 项匹配的容错判定。

### 13.8 统一返回、分页与版本（`lang.code`、`lang.version`）

```java
import com.chua.common.support.lang.code.ReturnResult;
import com.chua.common.support.lang.code.PageResult;
import com.chua.common.support.lang.version.Version;

ReturnResult<User> ok = ReturnResult.ok(user);
ReturnResult<Void> err = ReturnResult.error("500", "服务器错误");

ok.isSuccess();
ok.map(User::getName);                 // 映射不影响成败
ok.ifOk(u -> log.info("{}", u)).ifError(msg -> log.warn(msg));
ok.orElseGet(() -> fallback).getData();

PageResult<User> page = PageResult.<User>builder()
        .pageNo(1)
        .pageSize(20)
        .total(100)
        .totalPages(5)
        .data(list)
        .build();

Version v = Version.of("2.0.0-beta");
v.isHigherThan("1.9.9");
v.isAtLeast("2.0.0");
v.getMajor();                                   // 2
Version.parseFromMaven("spring-core-5.3.21.jar"); // 5.3.21
```

> `ReturnResult` 的 `code` 是 `String`（不是 int），便于和业务错误码体系对齐。

### 13.9 进度条（`lang.process`）

```java
import com.chua.common.support.lang.process.ProgressBar;

try (ProgressBar pb = new ProgressBar("下载", 100)) {
    for (int i = 0; i <= 100; i++) {
        pb.stepTo(i);
    }
    pb.setExtraMessage("校验中");
}

// 直接包装集合/流，自动按元素推进
ProgressBar.wrap(list, "处理").forEach(this::handle);
ProgressBar.wrap(inputStream, "读取");
```

内置样式：`COLORFUL_UNICODE_BLOCK`、`UNICODE_BLOCK`、`ASCII`、`PYTHON_DOWNLOAD`、`PYTHON_LOADING`、`RAINBOW`、`MATRIX`、`FIRE`、`OCEAN`、`NEON`、`SUNSET` 等；`ProgressBar.builder()` 可定制。

### 13.10 日期与占位符（`lang.date`、`lang.placeholder`）

```java
import com.chua.common.support.lang.date.DateTime;

DateTime.now();
DateTime.parse("2024-01-01 12:00:00");
DateTime.format(DateTime.now(), "yyyy-MM-dd");
DateTime.betweenDays(start, end);
DateTime.isToday(DateTime.now());
```

```java
import com.chua.common.support.lang.placeholder.*;
import java.util.Map;

PropertyResolver resolver = CompositePropertyResolver.createDefault(
        new MapMixSystemPlaceholderResolver(Map.of("app.name", "demo")));

String text = resolver.resolvePlaceholders("${app.name} 启动于 ${app.port:8080}");
```

支持 `${key}`、`${key:默认值}`、函数调用、三目与数组下标；解析源可叠加 Map、Properties、系统属性、环境变量。

---

## 14. SPI 扩展点总表

下表来自 `src/main/resources/META-INF/extensions/`，key 为调用 `getExtension(key)` 时使用的名字。

| 接口 | key → 实现 |
| --- | --- |
| `file.FileSystem` | `csv`、`json`、`xml`、`txt`、`zip`、`archive`、`tar` |
| `network.client.spi.HttpClientExecutor` | `okhttp`、`httpclient5`；`jdk` 内置（不写扩展文件）；`httpclient` 为预留名 |
| `network.server.Server` | `jdk` / `jdk-http`、`nio` / `nio-http`、`aio` / `aio-http` / `iocp`、`jdk-tcp` / `tcp`、`aio-tcp` / `tcp-aio`、`jdk-udp`、`jdk-telnet`、`jdk-websocket`、`ftp`、`ftps`、`sip`、`sniff-tcp`、`http-proxy`、`socks5-proxy`、`tcp-proxy`、`aio-http-proxy`、`aio-tcp-proxy` |
| `network.server.SyncServer` | `http`、`websocket`、`tcp`、`udp` |
| `network.sync.SyncClient` | `http`、`websocket`、`tcp`、`udp` |
| `network.server.filter.ServerFilter` | `ip`、`firewall`、`rate-limit`、`routing`、`request-id`、`request-timeout`、`ipc`、`response-rewrite`、`gzip`、`cors`、`ws-proxy`、`url-mapping`、`shell-url-mapping`、`invoker_inject`、`ai_token` |
| `network.server.filter.rate.RateLimitProvider` | `guava` |
| `network.server.parser.ServerHandlerAnnotationParser` | `request-method`、`ipc-method` |
| `network.server.resolver.HandlerMethodArgumentResolver` | `default` |
| `network.annotations.ListenerParser` | `javax`、`custom`、`objectcontext` |
| `network.discovery.ServiceDiscovery` | `peermesh` |
| `network.download.DownloadService` | `default`、`aria2` |
| `network.download.extractor.Extractor` | `zip`、`tar`、`tar.gz`、`tgz`、`gz` |
| `network.rpc.RpcClient` / `RpcServer` | `native` |
| `scatter.ScatterRemoteClient` | `tcp`、`udp` |
| `ai.chat.ChatClient` | `memory`（+ 各 cloud starter） |
| `ai.embedding.EmbeddingClient` | `memory` |
| `ai.rag.RagClient` | `memory` |
| `ai.rag.RagClient$UploadProvider` | `local` |
| `ai.splitter.TextSplitterProvider` | `sentence` |
| `ai.rule.RuleBuilder` | `native` |
| `browser.BrowserEngine` | `cdp` |
| `codec.Decoder` | `keylog` |
| `config.parser.ConfigParser` | `properties`、`xml`、`json` / `json5`、`jsonl`（注解注册，无扩展文件） |
| `collection.wrap.CollectionBackend` | JDK / Eclipse Collections |
| `concurrent.lock.LockProvider` | `semaphore`、`circular` |
| `concurrent.offset.OffsetStore` | 文件实现 |
| `concurrent.rate.RateLimiter`（provider） | `guava` |
| `datasource.join.JoinStrategy` | `none`、`inner` |
| `document.DocumentProvider` | `html`、`markdown`、`benchmark-html` |
| `export.spi.MetaResolver` | `javabean`、`third-party`、`export` |
| `image.ImageProcessor` | `jdk`、`rust` |
| `lang.benchmark.Benchmark` | `k6`、`java` |
| `lang.cmd.CliTool` | `tshark`、`aria2c`、`nvidia-smi` |
| `lang.datasource.flyway.Flyway` | `flyway` |
| `lang.datasource.flyway.ScriptConverter` | `default` |
| `lang.datasource.series.SeriesEngine` | `datasource` |
| `lang.directory.PolledDirectory` | `filesystem` |
| `lang.document.OpenApiDocumentProvider` | `html` |
| `lang.view.ViewParser` | `list`、`table`、`kv`、`card`、`tree`、`md`、`plain`、`json`、`barchart` |
| `objects.creator.Quick` | `quick` |
| `objects.environment.ConfigValueExpressionResolver` | `default` |
| `objects.inject.BeanDefinitionConfigInjector` | `default` |
| `objects.inject.BeanDefinitionMethodInjector` | `default`、`jsr` |
| `objects.inject.BeanDefinitionServiceInjector` | `default` |
| `objects.register.BeanDefinitionRegister` | `default`、`annotation`、`spi` |
| `objects.resolver.BeanConstructorResolver` | `default` |
| `probability.ProbabilityCollision` | `simple` |
| `serialize.DeserializationPolicy` | `denylist` |
| `storage.FileStorage` | 各对象存储 starter 注册 |
| `vector.VectorStorageProvider` | `memory` |
| `vector.RuntimeDetector` | `wal-store-detector` |
| `wal.WalFileSystem` | `wal-kv`、`wal-ts`、`wal-vec`、`wal-filesearch` |

> 另有 `META-INF/services/` 下的标准 `ServiceLoader` 登记：`Serialization`、`CollectionBackend`、`ImageProcessor`、`ServiceDiscovery`、`Server`、`ProbabilityCollision`。

---

## 15. 配置项与系统属性

| 项 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `chua.host.id.salt` | 系统属性 | 空 | 主机指纹盐值（`HostIdentity.SALT_PROPERTY`） |
| `ServerSetting.workerThreads` | int | CPU×2 | 服务端工作线程 |
| `ServerSetting.bossThreads` | int | CPU | Boss 线程 |
| `ServerSetting.maxConnections` | int | 1000 | 最大连接数 |
| `ServerSetting.backlog` | int | 128 | 连接队列 |
| `ServerSetting.tcpNoDelay` | boolean | true | 禁用 Nagle |
| `ServerSetting.idleTimeout` | int | 5000 | 空闲超时（ms） |
| `WalConfig.syncOnWrite` | boolean | true | 每次写入 fsync |
| `WalConfig.fsyncBatchSize` | int | 100 | 批量 fsync 条数 |
| `WalConfig.fsyncBatchIntervalMs` | long | 50 | 批量 fsync 间隔 |
| `WalConfig.maxSegmentBytes` | long | 64MB | 分片大小 |
| `WalConfig.keepCheckpointedSegments` | int | 1 | 保留 checkpoint 分片数 |
| `HttpClientBuilder.connectTimeout` / `readTimeout` | long | 实现默认 | 连接/读取超时（ms） |

---

## 16. 扩展开发

新增一个扩展只需三步（以新增文件系统为例）：

1. 实现接口（可加 `@Extension` / `@SpiOrder`）。
2. 建 `src/main/resources/META-INF/extensions/com.chua.common.support.file.FileSystem`。
3. 写一行 `mytype=com.example.MyFileSystem`。

```text
src/main/resources/META-INF/extensions/com.chua.common.support.file.FileSystem
mytype=com.example.MyFileSystem
```

调用方无需改代码：

```java
FileSystem fs = FileSystem.create("mytype");
```

**条件加载**：实现类上加 `@ConditionalOnClass`（依赖类存在才加载）、`@ConditionalOnProperty`（配置开关）、`@ConditionalOnMissingClass`，避免可选依赖缺失时启动失败。

**优先级**：`@SpiOrder(n)` 或 `@Spi(order = n)`，数值越大越优先；`getExtensionFactory(name)` 会按此顺序做故障降级。

---

## 17. 构建与依赖

### 17.1 编译要求

- JDK 25（`<release>25</release>`）。
- 编译器显式开启 `--enable-preview`、`--add-modules=jdk.incubator.vector`，并为 `jdk.compiler` 打开 `--add-exports/--add-opens`（注解处理与 AST 能力需要）。
- 源码 UTF-8、无 BOM；Lombok 作为注解处理器参与编译。

```bash
mvn -pl utils-support-core-parent/utils-support-common-starter -am clean package
```

### 17.2 运行期依赖

| 依赖 | 用途 | 是否可排除 |
| --- | --- | --- |
| `slf4j-api` | 日志 | 否 |
| `guava` | 集合/缓存/限流 | 否 |
| `jackson-databind` + `jackson-datatype-jsr310` | JSON / 时间类型 | 否（可换 `gson` / `fastjson` starter） |
| `reactor-core` | HTTP 异步返回 `Mono` | 否 |
| `eclipse-collections` | `collection.wrap` 默认后端 | 可排除，自动回退 JDK |
| `lombok` | 编译期 | `provided` |

---

## 18. 边界：这些能力不在本模块

旧版 README 中曾出现、但当前代码已**移除或迁出**的能力，请勿在本模块中查找：

| 能力 | 现状 |
| --- | --- |
| 加密编解码 `CodecBuilder` / `CryptoType` | 迁至 `utils-support-crypto-starter` |
| 死信队列 `DeadLetterQueue` | 已移除 |
| 缓存下载器 `CachedDownloader` | 已移除；下载用 `network.download.DownloadService` |
| `TextChat` / `ImageChat` 门面 | 已移除；统一用 `ai.chat.ChatClient` 的 `generateImage` / `generateVideo` |
| `Orchestrator` 任务编排 | 已移除；用 `utils-support-flow-starter` 的 `Flow` |
| ACME 证书 | 迁至 `utils-support-acme-starter` |
| 进度条 | 在 `lang.process.ProgressBar`（不是 `printer` 包） |
| 7z / zip4j / yaml / excel / pdf / word 文件系统 | 在 `utils-support-filesystem-starter` |
| 数据库驱动与连接池 | 在 `utils-support-datasource-parent` 各模块 |
| AI 厂商协议 | 在 `utils-support-cloud-parent` 各模块（`openai`、`claude`、`zhipu` 等） |

---

## 19. 文档索引

| 文档 | 路径 |
| --- | --- |
| 父模块说明 | [../README.md](../README.md) |
| utils 仓库总览 | [../../README.md](../../README.md) |
| 文件系统实现 | [../utils-support-filesystem-starter/README.md](../utils-support-filesystem-starter/README.md) |
| 网络实现 | [../utils-support-network-starter/README.md](../utils-support-network-starter/README.md) |
| 流程执行 | [../utils-support-flow-starter/README.md](../utils-support-flow-starter/README.md) |
| 数据同步 | [../../utils-support-datatask-parent/utils-support-datasync-starter/README.md](../../utils-support-datatask-parent/utils-support-datasync-starter/README.md) |
| 任务分发说明 | [src/main/java/com/chua/common/support/taskdistribution/README.md](src/main/java/com/chua/common/support/taskdistribution/README.md) |

---

## 许可证

Apache License 2.0
