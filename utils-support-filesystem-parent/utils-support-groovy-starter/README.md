# utils-support-groovy-starter

> utils 体系脚本子系统的 **Groovy 引擎实现模块**：把 `utils-support-common-starter` 定义的 4 个脚本 SPI（`Compiler` / `ScriptProvider` / `ScriptMarker` / `ScriptDefinition`）落地为 `"groovy"` 实现，提供 Groovy 源码纯内存编译、脚本文件/URL 的预编译执行与热重载能力。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜Groovy：`4.0.21`｜包名：`com.chua.groovy.support`

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-groovy-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | common 层 4 个脚本 SPI 的 `"groovy"` 实现：`Compiler`、`ScriptProvider`、`ScriptMarker`、`ScriptDefinition` | 不定义 SPI 接口本身（接口在 `utils-support-common-starter`） |
| 能力 | Groovy 源码纯内存编译；脚本预编译缓存与执行；`Map` 变量绑定与 `ScriptHost` 装配；文件/URL 热重载；类加载器缓存清理 | 不提供脚本管理后台、脚本存储、鉴权或 Web API |
| 依赖 | Groovy `4.0.21`（`groovy-all` 聚合包，已排除 `groovy-test`）+ `utils-support-common-starter` | 不依赖 Spring Boot 自动配置、不依赖数据库 |

### 1.3 规模与包结构

- Java 源文件 **4** 个，无 `.groovy` 源文件。
- SPI 登记文件 **5** 个：`META-INF/extensions/` 下 3 个、`META-INF/services/` 下 1 个、`spring.factories` 1 个。
- 内置 GraalVM `native-image` 配置：`META-INF/native-image/com.chua/utils-support-groovy-starter/` 下的 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

| 包 | 文件数 | 内容 |
| --- | --- | --- |
| `com.chua.groovy.support.compile` | 1 | `GroovyCompiler` |
| `com.chua.groovy.support.script` | 3 | `GroovyScriptProvider`、`GroovyScriptMarker`、`GroovyScriptDefinition` |

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 把 Groovy 源码编译成 `Class` | `GroovyCompiler#doCompile(name, source)` | SPI：`Compiler` / `"groovy"`；纯内存、UTF-8 |
| 加载脚本文件（预编译缓存） | `GroovyScriptProvider#loadScript(Path)` | SPI：`ScriptProvider` / `"groovy"`；按 `Path` 缓存编译产物 |
| 执行脚本并绑定变量 | `GroovyScriptProvider#executeScript(path, context)` | `context` 为 `Map` 时逐条注入绑定变量 |
| 卸载脚本、释放 Metaspace | `GroovyScriptProvider#unloadScript(Path)` | 同步清理 Shell 内部 `GroovyClassLoader` 缓存 |
| 查询脚本是否已加载 | `GroovyScriptProvider#isLoaded(Path)` | — |
| 由脚本源码创建对象实例 | `GroovyScriptMarker#createObject(listener, classLoader, args)` | SPI：`ScriptMarker` / `"groovy"`；支持自定义 `CompilerConfiguration` |
| 文件/URL 脚本热重载定义 | `GroovyScriptDefinition.of(name, path)` / `of(name, url, periodMillis)` | SPI：`ScriptDefinition` / `"groovy"` |
| 热重载后清理旧类加载器 | `GroovyScriptMarker#cleanupPreviousClassLoader()` | 释放旧 `GroovyClassLoader` 的内部缓存 |

### 2.2 GroovyCompiler —— 动态编译器

`com.chua.groovy.support.compile.GroovyCompiler`，`@Spi("groovy")`，实现 `com.chua.common.support.lang.compile.Compiler`。

```java
public Class<?> doCompile(String name, String source) throws Throwable
```

- 通过 `GroovyClassLoader` 在内存中把 Groovy 源码编译为 `Class`，不产生磁盘文件；源码编码固定 UTF-8。
- 每次编译使用独立的 `GroovyClassLoader`（父加载器为当前线程上下文类加载器），编译结束（含失败）后 `clearCache()`，不污染应用类加载器。
- 编译失败抛出 `CompilationFailedException` 或其子类。
- 注意：每次调用都会创建新的 ClassLoader，频繁编译请自行留意类加载器泄漏。

### 2.3 GroovyScriptProvider —— 脚本执行提供器

`com.chua.groovy.support.script.GroovyScriptProvider`，`@Spi("groovy")`，实现 `com.chua.common.support.task.script.ScriptProvider`。

| 方法 | 说明 |
| --- | --- |
| `String engineName()` | 返回 `"groovy"` |
| `boolean loadScript(Path scriptPath)` | 读取源码并用复用的 `GroovyShell` 预编译为脚本类，按 `Path` 缓存；已加载直接命中缓存。路径为 `null` 抛 `IllegalArgumentException`；源码为空抛 `IllegalStateException`；编译失败原样透出运行时异常 |
| `Object executeScript(Path scriptPath, Object context)` | 复用缓存的脚本类，每次执行新建 `Script` 实例与独立 `Binding`；未加载时先自动 `loadScript` |
| `void unloadScript(Path scriptPath)` | 移除监听器与类缓存，并清理 Shell 内部 `GroovyClassLoader` 缓存 |
| `boolean isLoaded(Path scriptPath)` | 是否已加载 |

变量绑定与宿主装配规则（源码行为）：

- `context` 整体作为绑定变量 `context` 注入；
- `context` 为 `Map` 时，key 为 `String` 的条目逐个暴露为 Groovy 绑定变量；
- `context` 中 key 为 `"scriptHost"` 且值为 `ScriptHost` 时，脚本实例创建后立即做一次字段装配，使脚本类上声明的 `@Autowired` 生效（源码注释：该键约定与 `JavaScriptProvider` 共用同一套上下文约定）。脚本内部 `new` 出来的对象不会自动装配，需要在脚本里显式调用 `scriptHost.inject(对象)` 或用 `scriptHost.getBean(类型)` 取 Bean；`executeScript` 对装配调用不做异常捕获，装配失败是否抛出取决于 `ScriptHost` 实现。

并发说明：编译只做一次（`Path -> Class` 缓存），每次执行新建 `Script` 实例与独立 `Binding`，不复用带执行期状态的实例，保证并发安全。

### 2.4 GroovyScriptMarker —— 脚本标记器（源码 → 对象实例）

`com.chua.groovy.support.script.GroovyScriptMarker`，`@Spi("groovy")`，继承 `com.chua.common.support.lang.script.marker.AbstractScriptMarker`。

| 成员 | 说明 |
| --- | --- |
| `GroovyScriptMarker()` | 使用默认编译配置（UTF-8） |
| `GroovyScriptMarker(CompilerConfiguration config)` | 自定义编译配置；`null` 时用默认；每个实例持有独立配置副本 |
| `Object createObject(Listener listener, ClassLoader classLoader, Object[] args)` | 从 `Listener` 取源码，用 `GroovyClassLoader` 编译并按 `args` 创建实例；`synchronized`。`listener == null` 返回 `null`；`classLoader` 为 `GroovyClassLoader` 时复用且不关闭，否则新建、失败时自动关闭；编译/处理失败记录错误日志并返回 `null` |
| `Class<?> getType()` | 最后一次编译生成的类 |
| `ClassLoader getScriptClassLoader()` | 最后一次编译使用的类加载器 |
| `CompilerConfiguration getCompilerConfiguration()` | 当前编译器配置 |
| `void cleanupPreviousClassLoader()` | 热重载后清理旧 `GroovyClassLoader` 的内部缓存（sourceCache 与类信息反射缓存），辅助 Metaspace 回收 |

### 2.5 GroovyScriptDefinition —— 脚本定义（热重载入口）

`com.chua.groovy.support.script.GroovyScriptDefinition`，`@Spi("groovy")`，继承 `com.chua.common.support.objects.definition.AbstractScriptDefinition`。

```java
// 文件监听
GroovyScriptDefinition def = GroovyScriptDefinition.of("myScript", Path.of("transform.groovy"));

// URL 轮询（periodMillis 为轮询周期，毫秒）
GroovyScriptDefinition def2 = GroovyScriptDefinition.of("remoteScript", new URL("http://host/script.groovy"), 5000);
```

- 文件方式内部组合 `FileScriptListener`，URL 方式组合 `UrlScriptListener`，均搭配 `GroovyScriptMarker`。
- 热重载时旧类加载器的销毁由父类 `AbstractScriptDefinition` 负责；旧加载器的 Groovy 缓存可通过 `cleanupPreviousClassLoader()` 主动清理。

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-groovy-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例：执行一个脚本文件

`hello.groovy`（放在工作目录）：

```groovy
// 变量 name 来自 executeScript 的 context
return "Hello, " + name
```

Java 侧：

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.script.ScriptProvider;

import java.nio.file.Path;
import java.util.Map;

public class GroovyQuickStart {
    public static void main(String[] args) {
        // SPI 名称固定为 "groovy"
        ScriptProvider groovy = ServiceProvider.of(ScriptProvider.class).getExtension("groovy");

        Path script = Path.of("hello.groovy");
        groovy.loadScript(script);                                   // 预编译并缓存
        Object result = groovy.executeScript(script, Map.of("name", "Groovy"));
        System.out.println(result);                                  // Hello, Groovy

        groovy.unloadScript(script);                                 // 卸载并清理缓存
    }
}
```

### 3.3 动态编译一段 Groovy 源码

```java
import com.chua.common.support.lang.compile.Compiler;
import com.chua.groovy.support.compile.GroovyCompiler;

Compiler compiler = new GroovyCompiler();
Class<?> clazz = compiler.doCompile("Hello",
        "class Hello { String greet(String name) { 'Hello, ' + name } }");

Object hello = clazz.getDeclaredConstructor().newInstance();
```

### 3.4 文件热重载定义

```java
import com.chua.groovy.support.script.GroovyScriptDefinition;

import java.nio.file.Path;

GroovyScriptDefinition def = GroovyScriptDefinition.of("rule", Path.of("rule.groovy"));
```

---

## 4. 配置项

**无。** 依据：

- `src/main/resources` 下没有任何 `application*.properties` / `*.yml`，源码中也没有 `@ConfigurationProperties` 配置类；
- 模块的全部登记文件都是 SPI 声明（`META-INF/extensions/`、`META-INF/services/`、`spring.factories`）与 GraalVM native-image 配置，不是可配置键；
- 引入依赖即可通过 SPI 名称 `"groovy"` 使用，零配置。

如需微调编译行为，可用 `GroovyScriptMarker(CompilerConfiguration)` 传入 Groovy 原生的 `CompilerConfiguration`；这是编程式参数，不是配置文件项。

---

## 5. SPI 扩展点

本模块是**实现方**：实现 common 层脚本 SPI，并以 `"groovy"` 名称注册。登记明细：

| 契约接口（utils-support-common-starter） | 登记文件 | 实现类 |
| --- | --- | --- |
| `com.chua.common.support.lang.compile.Compiler` | `META-INF/extensions/com.chua.common.support.lang.compile.Compiler` | `GroovyCompiler` |
| `com.chua.common.support.task.script.ScriptProvider` | `META-INF/services/com.chua.common.support.task.script.ScriptProvider` 与 `META-INF/spring.factories` | `GroovyScriptProvider` |
| `com.chua.common.support.lang.script.marker.ScriptMarker` | `META-INF/extensions/com.chua.common.support.lang.script.marker.ScriptMarker` | `GroovyScriptMarker` |
| `com.chua.common.support.objects.definition.ScriptDefinition` | `META-INF/extensions/com.chua.common.support.objects.definition.ScriptDefinition` | `GroovyScriptDefinition` |

四个实现类的 `@Spi` 值均为 `"groovy"`；`GroovyScriptProvider#engineName()` 同样返回 `"groovy"`。

扩展方向：本模块只实现 Groovy 一种脚本引擎；需要新的脚本语言（如 JavaScript、Python 等）应在对应 `*-starter` 中实现同一批 SPI，而不是修改本模块。

---

## 6. 构建与依赖

依赖树（`pom.xml`）：

```
utils-support-groovy-starter (com.chua:4.0.0.51)
├── org.apache.groovy:groovy-all:4.0.21 (pom 聚合，已排除 groovy-test)
└── com.chua:utils-support-common-starter:4.0.0.51
```

- JDK：`maven.compiler.source/target = 25`。
- `groovy-all` 是 pom 聚合包，会把 `groovy-test` 以 compile 作用域带进主代码编译类路径，并传递引入 JUnit 4；本模块只做 Groovy 脚本编译与运行，不需要测试件，故显式排除 `groovy-test`。
- 构建插件：`gmavenplus-plugin 5.1.0`，执行 `addSources / addTestSources / generateStubs / compile / generateTestStubs / compileTests / removeStubs / removeTestStubs`，编译 `src/main/java` 与 `src/main/groovy` 下的 `*.groovy` 源码（当前仓库中无 `.groovy` 源文件），`skipBytecodeCheck=true`。
- Groovy 版本策略：`4.0.21`；Groovy 6 为高风险跨 major（聚合组成、测试件与 JUnit 传递依赖规则变化），暂不升级。
- 内置 GraalVM native-image 配置：`META-INF/native-image/com.chua/utils-support-groovy-starter/` 下的 `reflect-config.json`、`proxy-config.json`、`resource-config.json`。

构建命令：

```bash
mvn -o -q compile -DskipTests
```

---

## 7. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| `Compiler` / `ScriptProvider` / `ScriptMarker` / `ScriptDefinition` 等脚本 SPI 接口与 SPI 框架本身 | `utils-support-common-starter` |
| 其它脚本语言的实现（如 JavaScript、Python 的 `ScriptProvider`） | 对应的其它 `*-starter` 模块 |
| 脚本管理后台、脚本存储、鉴权、Web API | 无（本模块不提供） |
| Spring Boot 自动配置与配置项 | 无（`spring.factories` 仅登记 `ScriptProvider` 的 SPI 实现，不是自动配置属性） |
| Groovy 编译器的深度定制（AST 转换、安全沙箱等） | 本模块仅暴露 `CompilerConfiguration` 传入口，引擎能力以 Groovy `4.0.21` 为准 |
