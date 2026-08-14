# Utils Support AST Processor Starter — AST 编译时注解处理器

基于 javac Tree API 的编译时注解处理器集合，在编译期对源代码进行 AST 变换。

## 架构

```
┌──────────────────────────────────────────────────────────────┐
│                AST 编译时注解处理器模块                        │
├──────────────────────────────────────────────────────────────┤
│  注解                                                         │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐ ┌──────────────┐  │
│  │@Default  │ │@CleanNull│ │ @PadTrun   │ │ @Trim        │  │
│  │ Value    │ │          │ │            │ │              │  │
│  ├──────────┤ ├──────────┤ ├────────────┤ ├──────────────┤  │
│  │@Timed    │ │ @Retry   │ │ @Trace     │ │ @AutoClose   │  │
│  ├──────────┤ ├──────────┤ ├────────────┤ ├──────────────┤  │
│  │@Virtual  │ │          │ │            │ │              │  │
│  │          │ │          │ │            │ │              │  │
│  └──────────┘ └──────────┘ └────────────┘ └──────────────┘  │
│                                                               │
│  处理器 (extends AbstractAstProcessor)                        │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐ ┌──────────────┐  │
│  │Default   │ │CleanNull │ │PadTruncate │ │ Trim         │  │
│  │Value     │ │          │ │            │ │              │  │
│  ├──────────┤ ├──────────┤ ├────────────┤ ├──────────────┤  │
│  │Timed     │ │ Retry    │ │ Trace      │ │ AutoClose    │  │
│  ├──────────┤ ├──────────┤ ├────────────┤ ├──────────────┤  │
│  │Virtual   │ │          │ │            │ │              │  │
│  └──────────┘ └──────────┘ └────────────┘ └──────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

## 特性

- `@DefaultValue` — 为字段设置默认值
- `@CleanNull` — 清洗污染字符串，将 "null"、"N/A" 等转为 null/0
- `@PadTruncate` — 字符串填充和截断
- `@Trim` — 自动调用 String.trim()
- `@Retry` — 方法重试，支持固定间隔和指数退避
- `@Timed` — 方法耗时统计
- `@Trace` — 方法追踪日志
- `@AutoClose` — 自动 close 资源
- `@Virtual` — 虚拟线程支持
- `@AutoSpi` — 编译期自动生成 `META-INF/extensions/` SPI 索引文件，已存在则追加去重，免去手动维护

## 使用示例

### @CleanNull — 清洗污染字符串

```java
// 默认清洗 "null"、"N/A"、"undefined"、"-"、"--"
public void process(@CleanNull String name) {
    // 编译后：
    // if ("null".equals(name) || "N/A".equals(name) || ...) { name = null; }
}

// 自定义关键词
public void process(@CleanNull({"unknown", "-"}) String name) { ... }
```

### @PadTruncate — 字符串填充和截断

```java
// 最小长度2，最大长度10
public void process(@PadTruncate(start=2, end=10) String name) {
    // 编译后：
    // if (name != null) {
    //     if (name.length() < 2) { name = String.format("%2s", name); }
    //     if (name.length() > 10) { name = name.substring(0, 10); }
    // }
}

// 自定义填充字符
public void process(@PadTruncate(start=2, end=10, padChar='0') String name) { ... }
```

### @Retry — 方法重试

```java
// 固定间隔重试：最多3次，每次100ms
@Retry(times = 3, delay = 100)
public void process() { ... }

// 指数退避重试：最多3次，初始100ms，最大5000ms
@Retry(times = 3, delay = 100, maxDelay = 5000, strategy = Retry.RetryStrategy.EXPONENTIAL)
public void process() { ... }
```

## 编译器配置

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>--add-exports</arg>
            <arg>jdk.compiler/com.sun.source.util=ALL-UNNAMED</arg>
            <!-- ... 其他 exports/opens 参数 -->
        </compilerArgs>
    </configuration>
</plugin>
```

### @AutoSpi — 自动生成 SPI 索引

```java
// 显式指定 SPI 接口与别名
@AutoSpi(value = "com.chua.common.support.ai.embedding.EmbeddingClient", name = "minilm")
public class MiniLMEmbeddingClient implements EmbeddingClient { ... }

// 省略接口与别名：自动推导实现接口，别名取自 @Spi/@Extension 或类名去掉接口名
@AutoSpi
public class BgeEmbeddingClient implements EmbeddingClient { ... }
```

编译后自动生成（与运行时 `CustomServiceResolver` 解析格式一致）：

```
META-INF/extensions/com.chua.common.support.ai.embedding.EmbeddingClient
  minilm=com.chua.deeplearning.support.onnx.embedding.minilm.MiniLMEmbeddingClient
  bge=com.chua.deeplearning.support.onnx.embedding.bge.BgeEmbeddingClient
```

规则：
- 接口：优先 `value` 显式指定；缺省时递归收集实现类及其父类实现的所有非 JDK 接口，每个接口各生成一份索引
- 别名：优先 `name`；其次读取实现类上的 `@Spi` / `@Extension` 注解 value；最后按「类名去掉接口名」推导
- 若索引文件已存在（手动维护或历史生成），读取已有内容并追加新条目，自动去重（相同行只保留一份），不覆盖已有配置

与 `@Spi` / `@Extension` 共存：
- 运行时 `ServiceDefinitionUtils` 优先读取类上的 `@Spi`/`@Extension` 注解生成名称，索引行别名仅在类无注解时生效
- 因此实现类带 `@Spi`/`@Extension` 时，每个接口只生成一条「裸类名」发现行（不再为每个别名各写一行），避免运行时 N×M 重复注册
- 自动清理历史构建遗留的冗余 `别名=类名` 行（别名属于该类当前 `@Spi`/`@Extension` 声明值时）
- 若此时仍显式指定 `name`，会给出编译告警（该名称运行时被忽略，注解名优先）

## Maven

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ast-processor-starter</artifactId>
    <version>${project.version}</version>
    <scope>provided</scope>
</dependency>
```
