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

## Maven

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-ast-processor-starter</artifactId>
    <version>${project.version}</version>
    <scope>provided</scope>
</dependency>
```
