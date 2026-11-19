# utils-support-asm-starter

> utils 体系的**字节码实现模块**：为 `utils-support-common-starter` 定义的三份契约 —— `BeanCopier`（Bean 属性拷贝）、`Compiler`（Java 源码动态编译）、`ProxyFactory`（动态代理）—— 提供基于 **ASM** 与 **Javassist** 的高性能实现，SPI 名称 `asm` / `javassist`，引入依赖即被 `ServiceProvider` 发现，无需任何配置。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.common.support`（子包 `bean`、`lang.compile`、`proxy.asm`、`proxy.javassist`）

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
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
    <artifactId>utils-support-asm-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

父链：`utils-support-parent-starter`（4.0.0.51）→ `utils-support-extra-parent` → `utils-support-asm-starter`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | 全仓字节码生成的**统一收口点**：Bean 拷贝、动态编译、代理工厂的字节码实现都放这里 | 不是业务模块，无 Controller / 持久层 / 业务规则 |
| 实现 | 4 个 Java 实现类：`AsmBeanCopier`、`AsmCompiler`、`AsmProxyFactory`、`JavassistProxyFactory` | 不定义 SPI 接口（接口全部在 `utils-support-common-starter`） |
| 依赖 | 仅依赖 `utils-support-common-starter` + `org.ow2.asm:asm` / `asm-util` + `org.javassist:javassist` | 不依赖 Spring；无 `spring.factories` / 自动配置类，名为 starter，实为纯 SPI 实现包 |
| 版本 | ASM 版本由根 pom `asm.version=9.10.1` 统一管理，模块内禁止自定义，避免同一进程出现两个 ASM 版本 | 不负责第三方字节码库的版本仲裁（由根 pom 统一管理） |

**判断口径**：任何模块需要「生成字节码」能力（拷贝器、编译器、代理），统一引本模块，不得自带 ASM / Javassist 依赖另起一份实现。

---

## 2. 核心 API 与速查表

模块只有 4 个实现类，全部通过 `@Spi` 注解声明名称：

| 类 | SPI 名称 | 实现契约 | 说明 |
| --- | --- | --- | --- |
| `com.chua.common.support.bean.AsmBeanCopier` | `asm` | `BeanCopier` | 按 (源类, 目标类) 生成字节码拷贝类并缓存复用，直接调 getter/setter |
| `com.chua.common.support.lang.compile.AsmCompiler` | `asm` | `Compiler` | JDK 编译器 API 内存编译 + ASM 二次处理补全 stack map 帧 |
| `com.chua.common.support.proxy.asm.AsmProxyFactory` | `asm` | `ProxyFactory` | 目标是接口 → 退回 JDK 动态代理；目标是类 → 生成 `$AsmProxy` 字节码子类 |
| `com.chua.common.support.proxy.javassist.JavassistProxyFactory` | `javassist` | `ProxyFactory` | Javassist 生成目标类子类，挂 `MethodHandler` |

常见能力速查：

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| Bean 属性拷贝 | `ServiceProvider.of(BeanCopier.class).getExtension("asm")` | 全量拷贝「源可读 + 目标可写」的同名属性 |
| 拷贝时忽略字段 | `copier.copyProperties(src, target, "id", "createTime")` | 忽略名单在生成的字节码里判断 |
| Map ↔ Bean | `copyProperties(map, bean)` / `copyProperties(bean, map)` | 这两个重载固定走 `JdkBeanCopier` |
| 动态编译 Java 源码 | `ServiceProvider.of(Compiler.class).getExtension("asm")` 后 `compiler(code)` | 自动从源码提取包名/类名，类已存在则直接命中 |
| 创建类代理（ASM） | `new AsmProxyFactory<>().createProxy(...)` 或 `AsmProxyFactory.INSTANCE` | 拦截器存放在生成类的静态 `INTERCEPT` 字段 |
| 创建类代理（Javassist） | `new JavassistProxyFactory<>().createProxy(...)` 或 `JavassistProxyFactory.INSTANCE` | `before → invoke → after`，异常走 `handleException` |
| 方法拦截器 | 实现 `MethodIntercept<T>` | `before` / `invoke` / `after` / `handleException` / `defaultInvoke` |

---

## 3. 快速开始

### 3.1 Bean 属性拷贝（ASM）

```java
import com.chua.common.support.lang.bean.BeanCopier;
import com.chua.common.support.spi.ServiceProvider;

BeanCopier copier = ServiceProvider.of(BeanCopier.class).getExtension("asm");
copier.copyProperties(source, target);                      // 全量拷贝
copier.copyProperties(source, target, "id", "createTime");  // 忽略指定属性
```

行为要点（与 `AsmBeanCopier` 源码一致）：

- 每组 `(sourceClass, targetClass)` 只生成一次拷贝类，之后走缓存复用；
- 只匹配「源可读 + 目标可写且同名」的属性；生成的字节码直接互调 getter/setter，若因类型对不上导致生成失败，会整体降级为 `JdkBeanCopier`（反射实现）；
- `copyProperties(Map, Object)` 与 `copyProperties(Object, Map)` 两个重载固定走 `JdkBeanCopier`；
- 拷贝类为 `GeneratedCopier_<源类>_<目标类>_<哈希>`，字节码版本 V17，以线程上下文类加载器为 parent 加载。

### 3.2 动态编译 Java 源码（ASM 后处理）

```java
import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.spi.ServiceProvider;

Compiler compiler = ServiceProvider.of(Compiler.class).getExtension("asm");

String code = "public class Hello { public String say() { return \"hi\"; } }";
Class<?> clazz = compiler.compiler(code);     // 自动提取类名；类已加载则直接返回
Object hello = clazz.getDeclaredConstructor().newInstance();
```

行为要点：

- 编译全程在内存完成：`StringSource`（源码）→ `InMemoryFileManager` → `InMemoryByteCode` → `InMemoryClassLoader`；
- 编译出的字节码再用 ASM `ClassReader` / `ClassWriter(COMPUTE_FRAMES)` 重建 stack map 帧，提升运行时类加载兼容性；
- 编译失败抛 `IllegalStateException`，附带全部错误诊断；
- 需要运行环境提供系统编译器（`ToolProvider.getSystemJavaCompiler()`），纯 JRE 会直接抛 `IllegalStateException`；
- 与 SPI 名 `jdkcompiler` 的实现相比，本实现多了 ASM 字节码后处理环节（源码 javadoc 原文口径）。

### 3.3 创建代理

```java
import com.chua.common.support.proxy.ProxyFactory;
import com.chua.common.support.proxy.asm.AsmProxyFactory;
import com.chua.common.support.proxy.javassist.JavassistProxyFactory;
import com.chua.common.support.proxy.intercept.MethodIntercept;
import java.lang.reflect.Method;

public class UserService {
    public String findUser(String id) { return "user-" + id; }
}

MethodIntercept<UserService> intercept = new MethodIntercept<UserService>() {
    @Override
    public Object invoke(Object obj, Method method, Object[] args, UserService proxy) {
        return "拦截到 " + method.getName();   // 自定义逻辑
    }
};

// ASM：为 UserService 生成字节码子类 UserService$AsmProxy
ProxyFactory<UserService> asm = new AsmProxyFactory<>();
UserService p1 = asm.createProxy(UserService.class, new Class<?>[0],
        Thread.currentThread().getContextClassLoader(), intercept);

// Javassist：生成 UserService 子类并挂 MethodHandler
ProxyFactory<UserService> javassist = new JavassistProxyFactory<>();
UserService p2 = javassist.createProxy(UserService.class, new Class<?>[0],
        Thread.currentThread().getContextClassLoader(), intercept);
```

行为要点与注意：

- `createProxy(target, interfaces, classLoader, intercept)` 四参签名来自 `ProxyFactory` 契约；
- `AsmProxyFactory` 遇到**接口**目标会退回 JDK 动态代理；遇到**类**目标生成 `$AsmProxy` 子类，要求目标类有可访问的无参构造方法（生成的构造方法直接调用目标类无参构造）；
- Javassist 实现按「`before` → `invoke` → `handleException`（返回非 null 则吞掉异常）→ `after`（finally）」的顺序回调；ASM 实现只回调 `invoke`；
- 代理类已经重写原方法，**没有可直接回掉的「原方法」**：如需执行原始逻辑，请在拦截器里自行持有目标对象并转发，不要对代理对象调 `defaultInvoke`（会再次进入拦截器）；
- `common-starter` 的 `DefaultProxyProvider` 会自动选择工厂：接口 → JDK 动态代理（`JdkProxyFactory`），类 → Javassist 代理。

---

## 4. 配置项

**无。**

依据：本模块源码与 `pom.xml` 中不存在任何配置键、`@ConfigurationProperties`、系统属性读取或 Spring 自动配置文件（无 `spring.factories` / `AutoConfiguration.imports`）。唯一的外部化文件是 SPI 注册文件与 GraalVM native-image 配置（见下文），均随 jar 内置，无需在应用侧配置。

---

## 5. SPI 扩展点

### 5.1 本模块注册的 SPI 实现

`src/main/resources/META-INF/extensions/` 下共 2 个注册文件：

| 注册文件（接口全限定名） | 内容 |
| --- | --- |
| `com.chua.common.support.lang.compile.Compiler` | `asm=com.chua.common.support.lang.compile.AsmCompiler` |
| `com.chua.common.support.lang.bean.BeanCopier` | `asm=com.chua.common.support.bean.AsmBeanCopier` |

两个代理工厂通过类上的 `@Spi("asm")` / `@Spi("javassist")` 注解声明名称；本模块**没有**为 `ProxyFactory` 提供 `META-INF/extensions` 注册文件。

### 5.2 本模块没有新的扩展点

模块内只有 4 个实现类，未定义任何新的 SPI 接口。可扩展的是 `utils-support-common-starter` 的三份契约：

```text
1. 实现 com.chua.common.support.lang.bean.BeanCopier /
       com.chua.common.support.lang.compile.Compiler /
       com.chua.common.support.proxy.ProxyFactory
2. 实现类上加 @Spi("你的名字")
3. 在自己模块的 src/main/resources/META-INF/extensions/<接口全限定名> 中登记：
   你的名字=实现类全限定名
```

---

## 6. 构建与依赖

```text
utils-support-parent-starter (4.0.0.51)
└── utils-support-extra-parent
    └── utils-support-asm-starter
        ├── utils-support-common-starter   (${project.version})
        ├── org.ow2.asm:asm                (${asm.version} = 9.10.1，根 pom 统一管理)
        ├── org.ow2.asm:asm-util           (${asm.version} = 9.10.1)
        └── org.javassist:javassist        (3.33.0-GA，模块属性 javassist.version)
```

- ASM 版本由根 pom 的 `asm.version` 属性统一管理；模块 pom 注释明确**不得**在本模块自定义 `asm.version`，否则会覆盖父级版本，导致同一进程出现两个 ASM 版本。
- `javassist.version=3.33.0-GA` 在本模块 pom 中声明，与根 pom `dependencyManagement` 的托管值一致。
- 根 pom 另为所有模块提供 `lombok`（provided）、`org.jetbrains:annotations`、`com.google.code.findbugs:jsr305`（provided）。
- 编译（仓库约定跳过测试）：

```bash
mvn -o -q compile -DskipTests
```

（在本模块目录或仓库根目录执行均可；根 pom 默认 `maven.test.skip=true`，本模块无 `src/test` 源码。）

- 内置 GraalVM native-image 配置（`META-INF/native-image/com.chua/utils-support-asm-starter/`）：
  - `reflect-config.json`：注册 `AsmCompiler` 的全部构造器 / 方法 / 字段；
  - `proxy-config.json`：为 `Compiler` 接口生成动态代理；
  - `resource-config.json`：打包 `META-INF/extensions/.*`。
- 源码编码 UTF-8 无 BOM（`project.build.sourceEncoding=UTF-8`）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 位置 |
| --- | --- |
| `BeanCopier` / `Compiler` / `ProxyFactory` / `MethodIntercept` 接口定义 | `utils-support-common-starter` |
| JDK 动态代理实现 `JdkProxyFactory`、自动选择器 `DefaultProxyProvider`（接口 → JDK、类 → Javassist） | `utils-support-common-starter` |
| 反射拷贝实现 `JdkBeanCopier`（ASM 失败时的降级实现） | `utils-support-common-starter` |
| ASM / Javassist 版本仲裁 | `utils-support-parent-starter` 根 pom |
| CGLIB、Spring AOP、注解切面、事务、业务编排 | 不在本模块，本模块只做字节码生成 |
| Spring 自动装配 | 不在本模块：无 `spring.factories` / `AutoConfiguration.imports`，引入即用，不依赖 Spring 环境 |
