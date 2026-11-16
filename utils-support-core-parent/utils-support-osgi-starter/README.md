# utils-support-osgi-starter

> utils 体系的 **OSGi 实现层**：把 `utils-support-common-starter` 中的纯 JDK OSGi 抽象（`com.chua.common.support.osgi`）落到 Apache Felix 上，并提供 bundle 目录热部署、源码热重载 SPI 扩展点、OSGi 服务桥接进核心对象上下文三类配套能力。
>
> 版本：`4.0.0.51`（`<parent>com.chua:utils-support-core-parent:4.0.0.51`）｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.osgi.support`

---

## 目录

- [1. 模块定位](#1-模块定位)
  - [1.1 Maven 坐标](#11-maven-坐标)
  - [1.2 它是什么 / 不是什么](#12-它是什么--不是什么)
  - [1.3 常用能力速查](#13-常用能力速查)
- [2. 核心 API](#2-核心-api)
  - [2.1 FelixOsgiLauncher：框架启动器](#21-felixosgilauncher框架启动器)
  - [2.2 FelixOsgiBundle：Bundle 包装](#22-felixosgibundlebundle-包装)
  - [2.3 FelixBundleContext：BundleContext 包装](#23-felixbundlecontextbundlecontext-包装)
- [3. 快速开始](#3-快速开始)
- [4. 目录热部署（BundleDeployWatcher）](#4-目录热部署bundledeploywatcher)
- [5. 源码热重载扩展点（source 子包）](#5-源码热重载扩展点source-子包)
- [6. OSGi 服务桥接进容器（OsgiBeanDefinitionRegister）](#6-osgi-服务桥接进容器osgibeandefinitionregister)
- [7. 配置项](#7-配置项)
- [8. SPI 扩展点](#8-spi-扩展点)
- [9. 构建与依赖](#9-构建与依赖)
- [10. 边界：这些能力不在本模块](#10-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-osgi-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块描述（pom.xml）：`Utils Support OSGI Starter - Apache Felix Framework`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `com.chua.common.support.osgi` 抽象（`OsgiLauncher` / `OsgiBundle` / `BundleContext` / `BundleStateQuery` / `BundleApplication` / `BundleLifecycleListener` / `OsgiServiceDescriptor`）在 **Apache Felix** 上的实现 | 不是新的 OSGi 抽象层，抽象由 `utils-support-common-starter` 定义 |
| 框架 | 启动/停止 Felix Framework（`org.apache.felix.framework` 7.0.5），生命周期事件由框架级 `BundleListener` 驱动 | 不绑定 Spring；Spring 环境的属性绑定由 `spring-support-osgi-starter` 负责（源码未提供） |
| 配套 | bundle 目录热部署、源码热重载 SPI 扩展点、OSGi 服务只读桥接进容器 | 不提供 bundle 打包/签名工具，不提供远程管理控制台交互界面 |
| 依赖 | `utils-support-common-starter`、Felix、Gogo shell 套件、Apache SSHD、classgraph | 生产依赖**不**引入 groovy（引擎缺失时自动降级） |

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 启动/停止 Felix 框架 | `FelixOsgiLauncher.start(Map)` / `stop()` | 幂等、串行化；启动后注册为全局唯一实例（`OsgiLauncherHolder`） |
| 安装 bundle | `installBundle(String url)` | 按 `autoStartInstalledBundles` 自动启动；失败不回滚安装，记入 `getFrameworkStats()` |
| 热升级 bundle | `updateBundle(String symbolicName, String url)` | 走框架原生 `Bundle#update`，保留 bundle 标识与已解析依赖；`url` 为 `null` 时沿用原位置 |
| 卸载 bundle | `uninstallBundle(String symbolicName)` | 返回前等待 `UNINSTALLED` 事件回调完毕 |
| 查询 bundle | `getBundles()` / `getBundle(String)` / `getBundle(long)` / `getBundlesByState(String)` | 另有 `getBundleCount()` / `getActiveBundles()` / `getBundlesByLocation(String)` |
| 获取 OSGi 服务 | `getServices(Class)` / `getService(Class)` / `getServiceDescriptors()` | 结果按 `service.ranking` 降序、同值按 `service.id` 升序；取到的引用由启动器持有 |
| 释放服务引用 | `releaseServices(Class)` / `releaseAllServices()` | 不释放则实例在框架回收前始终有效 |
| bundle 目录热部署 | `BundleDeployWatcher` | 周期扫描目录：新 jar 安装、内容变化原地升级、删除按配置卸载 |
| 源码热重载扩展点 | `SourceExtensionScheduler` + `@SourceSpi` | 源码目录 `.java` / `.groovy` 变化即重编译并重新注册 SPI |
| OSGi 服务桥接容器 | `OsgiBeanDefinitionRegister` | 只读桥接，`isWritable()` 恒为 `false`，ranking 贯通容器优先级 |
| 生命周期监听 | `addListener(BundleLifecycleListener)` / `removeListener(...)` | 单个监听器异常被隔离，不影响其余监听器 |

---

## 2. 核心 API

### 2.1 FelixOsgiLauncher：框架启动器

`com.chua.osgi.support.FelixOsgiLauncher implements OsgiLauncher, BundleStateQuery`（`@since 4.0.0.42`）。

- **幂等的启停**：框架已活动时 `start` 直接返回（同一 JVM 只允许一个活动 `Framework`）；已 `init` 未 `start` 时复用同一实例，本次传入的配置不再生效并打印提示。`stop` 依次注销框架监听器 → 回调 `BundleApplication#onBundleStop` → 释放服务引用 → 停止框架 → 清空缓存与状态并清除全局单例。
- **事件由框架驱动，不由客户端方法触发**：启动器注册同步 `BundleListener`，`installBundle` / `uninstallBundle` / `updateBundle` 不再手工 fire 事件；通过任何途径引发的状态变化都能被感知，且每个事件只投递一次。

| 框架事件 | 投递的回调 |
| --- | --- |
| `INSTALLED` | `onBundleInstalled` |
| `STARTED` | `onBundleStarted` |
| `STOPPED` | `onBundleStopped` |
| `UPDATED` | `onBundleUpdated`（带新版本号） |
| `UNRESOLVED` | `onBundleStateChanged` + `onBundleResolveFailed` |
| `UNINSTALLED` | 先释放该 bundle 名下被持有的服务引用，再 `onBundleStateChanged` + `onBundleRemoved`（含卸载前位置与版本） |
| 其余（`STARTING` / `STOPPING` / `RESOLVED` 等） | `onBundleStateChanged`（旧状态取自内部状态快照） |

> Felix 7 在 DispatchQueue 线程**异步**投递 bundle 事件，且规范提供的同步注册重载在 Felix 7 内嵌 API 中不存在。因此 `installBundle` / `uninstallBundle` / `FelixOsgiBundle#stop` / `#uninstall` 在返回前通过事件屏障（超时 3000ms、轮询 2ms）等待目标事件处理完毕，避免调用方读到滞后状态。

- **服务引用由启动器持有**：`getServices` / `getService` / `getServiceDescriptors` 取到的实例连同 `ServiceReference` 一并持有（引用计数 +1），在 `releaseServices(type)` / `releaseAllServices()` / `stop()` 前始终有效；bundle 卸载时按 `referenceOwners` 定向释放其名下引用，避免已下线服务继续被桥接进容器。跨类加载器的同名类型会被跳过（类身份不同，无法强转）。
- **统计信息**：`getFrameworkStats()` 返回 `active` / `frameworkState` / `totalBundles` / `activeBundles` / `registeredApplications` / `listeners` / `registeredServiceTypes` / `heldServiceReferences` / `autoStartFailures` / `lastAutoStartFailure` / `bundleStates`。
- **自动启动**：`isAutoStartInstalledBundles()` / `setAutoStartInstalledBundles(boolean)`，默认开启；自动启动失败不使安装回滚，失败原因累计进统计并通过 `onBundleResolveFailed` 通知。
- **原始上下文**：`getFrameworkBundleContext()` 返回原生 `org.osgi.framework.BundleContext`，供框架外组件按名查找 OSGi 服务。

### 2.2 FelixOsgiBundle：Bundle 包装

`com.chua.osgi.support.FelixOsgiBundle implements OsgiBundle`（`@since 4.0.0.42`）。

包装对象**不缓存状态**，所有查询实时委派给底层 `org.osgi.framework.Bundle`，可长期复用而不会读到过期数据：

| 能力 | 方法 | 说明 |
| --- | --- | --- |
| 身份/元信息 | `getBundleId` / `getSymbolicName` / `getVersion` / `getLocation` / `getHeaders` | 缺失时返回空串/空 Map，不抛异常 |
| 状态 | `getState()` | 统一状态名：`UNINSTALLED` / `INSTALLED` / `RESOLVED` / `STARTING` / `STOPPING` / `ACTIVE` / `UNKNOWN` |
| 生命周期 | `start()` / `stop()` / `uninstall()` | `stop` / `uninstall` 先释放本 bundle 持有的引用，再等待框架事件回调完毕 |
| 热升级 | `update(String url)` | `url` 为 `null` 走 `bundle.update()`；非空则本实现打开流后交由框架在本次调用内消费（Felix 7 没有 `update(URL)` 重载）；升级前处于 `ACTIVE` 则升级后自动重新启动 |
| 服务注册 | `registerService(Class, T)` / `registerService(Class, T, Map)` | 带属性注册，句柄登记在本 bundle 内 |
| 服务注销 | `unregisterService(Class, T)` | **按实例注销**：仅当注册项对应实例与入参为同一对象时才注销，避免误注销 |
| 服务查询 | `getServices(Class)` / `getRegisteredServiceTypes()` | 查询结果记入本 bundle 持有引用 |
| 底层访问 | `getDelegate()` | 返回原生 `Bundle` |

### 2.3 FelixBundleContext：BundleContext 包装

`com.chua.osgi.support.FelixBundleContext implements com.chua.common.support.osgi.BundleContext`（`@since 4.0.0.42`）。

| 能力 | 方法 | 说明 |
| --- | --- | --- |
| 注册服务 | `registerService(Class, T[, Map])` | 带属性注册，注册句柄登记（引用 → 句柄） |
| 注销服务 | `unregisterService(Class, T)` | 经 `ServiceRegistration#unregister()` 真正注销；仅调用 `unget` 只释放引用计数，服务仍留在注册表中 |
| 查询服务 | `getServices(Class)` / `getService(Class)` / `getServiceTypes()` | 按 ranking 排序；`getServiceTypes()` 全量枚举 `objectClass` |
| 取 bundle | `getBundle(String)` | 包装并绑定归属 launcher，使 `stop()` / `uninstall()` 具备事件同步屏障 |
| 刷新 | `refresh()` | 释放本上下文持有的全部服务引用 |
| 便捷入口 | `getServiceByName(String)` | 供框架外组件按类型全限定名查找；走全量 API 后按 `objectClass` 手动匹配（实证可靠） |
| 底层访问 | `getDelegate()` | 返回原生 `org.osgi.framework.BundleContext` |

---

## 3. 快速开始

引入依赖后（见 [1.1](#11-maven-坐标)），最短可运行流程：

```java
import com.chua.osgi.support.FelixOsgiLauncher;
import com.chua.common.support.osgi.OsgiBundle;
import com.chua.common.support.osgi.BundleLifecycleListener;

// 1. 启动 Felix（幂等；成功后把自身注册为全局唯一实例）
FelixOsgiLauncher launcher = new FelixOsgiLauncher();
launcher.start(null);          // 可传 Map<String, String> 框架配置

// 2. 安装并自动启动 bundle（url 为 jar 路径或远程 URL）
launcher.installBundle("file:///path/to/your-bundle.jar");

// 3. 查询 bundle
List<OsgiBundle> bundles = launcher.getBundles();
OsgiBundle demo = launcher.getBundle("com.example.your-bundle");

// 4. 获取 OSGi 服务（按 service.ranking 降序，首个即当前生效）
MyService service = launcher.getService(MyService.class);
launcher.releaseServices(MyService.class);   // 用完显式归还引用

// 5. 生命周期监听
launcher.addListener(new BundleLifecycleListener() {
    @Override public void onBundleInstalled(String symbolicName) { /* ... */ }
    @Override public void onBundleStarted(String symbolicName) { /* ... */ }
    @Override public void onBundleStopped(String symbolicName) { /* ... */ }
    @Override public void onBundleUpdated(String symbolicName, String newVersion) { /* ... */ }
    @Override public void onBundleUninstalled(String symbolicName) { /* ... */ }
    @Override public void onBundleStateChanged(String symbolicName, String oldState, String newState) { /* ... */ }
    // onBundleRemoved / onBundleResolveFailed 为默认实现，按需覆写
});

// 6. 热升级（走框架原生 update）与卸载
launcher.updateBundle("com.example.your-bundle", "file:///path/to/your-bundle-1.1.jar");
launcher.uninstallBundle("com.example.your-bundle");

// 7. 停止（幂等：注销监听器 → 回调应用停止 → 释放服务引用 → 停止框架）
launcher.stop();
```

---

## 4. 目录热部署（BundleDeployWatcher）

`com.chua.osgi.support.deploy.BundleDeployWatcher`（`@since 4.0.0.43`）：周期扫描部署目录，按 jar 的 MANIFEST 做增量处置。

```java
BundleDeployWatcher watcher = new BundleDeployWatcher(
        launcher, "./osgi-deploy", 10000L, true, true);
watcher.start();   // 周期扫描（守护线程 osgi-deploy-watcher）
watcher.stop();    // 停止
```

| 构造参数 | 含义 |
| --- | --- |
| `directory` | 部署目录，为 `null` / 空白时默认 `./osgi-deploy`；不存在时启动即创建 |
| `pollingInterval` | 扫描间隔（毫秒），非正数时取默认值 `10000L` |
| `uninstallOnRemoved` | 目录中 jar 被删除后卸载对应 bundle |
| `updateOnChanged` | jar 内容变化（最后修改时间 + 文件大小指纹）时对已安装 bundle 执行原地升级 |

- 符号名取自 jar 的 `MANIFEST` 的 `Bundle-SymbolicName`，会剥离 `;singleton:=true` 之类的指令后缀；目录中的**文件名不必等于符号名**，定位真实文件时先尝试「符号名.jar」直连，再回扫目录按 MANIFEST 匹配。
- 首次出现的新 jar 安装并启动（自动启动由 launcher 的 `autoStartInstalledBundles` 决定）；已安装且符号名相同、指纹变化时走框架原生 `update` 原地升级；jar 消失且 `uninstallOnRemoved` 为真时卸载。
- 扫描异常有兜底（`scanSafely`），不会因单轮异常取消定时任务；`scan()` 也可手动触发。

---

## 5. 源码热重载扩展点（source 子包）

`com.chua.osgi.support.source`（核心类 `@since 4.0.0.43`）：把「源码目录」变成「可随时改代码即生效的 SPI 扩展点」，**不进 Felix**——SPI 扩展点只要求「某个接口的某个名字能取到实现」，用独立 ClassLoader 承载编译产物即可满足，卸载时可整批丢弃。

### 5.1 组成

| 类型 | 职责 |
| --- | --- |
| `SourceSpi`（注解） | 标注「本身不实现业务接口、只作为若干 SPI 实现宿主」的类；编译器扫描后把其中**所有 public 字段**按「字段静态类型 = SPI 类型、字段名 = 扩展名」翻译成注册动作（`@since 4.0.0.43`） |
| `SourceJavaCompiler` | 编译目录下 `.java` → 扩展点声明；每轮新建 `SourceClassLoader`（父加载器为应用 ClassLoader，目录为唯一 classpath 条目）；编译失败或收集失败时**保留上一轮注册** |
| `SourceGroovyLoader` | 加载 `.groovy` 脚本 → 扩展点声明；复用 `ScriptProviders` 探测引擎，groovy 不在 classpath 时自动跳过不报错 |
| `SourceExtensionFactory` | 源码扩展点的注册契约：`create()` / `spiType()` / `extensionName()`；是无状态工厂而非单例持有者 |
| `SourceExtensionResolver` | 实现 `ServiceResolver`，把声明翻译为可注销的 SPI 服务定义（`finderType = SourceExtensionResolver.class`，`isDynamic() = true`，同名覆盖） |
| `SourceExtensionScheduler` | 轮询调度：目录指纹未变直接返回；变化则重编译并整体替换发布；任一语言编译失败**跳过整轮发布**，保留上一轮注册状态 |
| `SourceContext` / `SourceContextHolder` | 源码侧取容器 Bean 的契约与全局持有者：`install(Map)` / `clear()` / `get(Class)` / `getBean(String)`；用 `volatile` 引用整体替换，不用 ThreadLocal（源码 SPI 实现可能在任意线程被调用） |

### 5.2 Java 源码宿主（@SourceSpi）

```java
// 源码目录下的 .java 文件
@SourceSpi
public class MyExtensions {
    // 扩展名 JSON（字段名即扩展名），实现类即 DataProcessor
    public final DataProcessor json = new MyJsonProcessor();
}
```

字段必须 `public`（反射读取）、非 `static`（宿主实例由编译器构造一次并复用）；为 `null` 的字段会被跳过并告警，不阻断整批注册；实现类须有 **public 无参构造器**。扩展名在注册表内统一转大写。

### 5.3 Groovy 脚本声明

脚本返回一个 `Map`（或 `List` 承载多个声明）：

```groovy
[spi: 'com.example.MySpi', name: 'myExt', value: 扩展实例]
```

约定字段：`spi`（必需，SPI 类型全限定名）、`name`（必需，扩展名）、`value`（必需，扩展点实例）。脚本路径与 `ScriptProvider` 的缓存键一致，重载前先 `unloadScript` 再 `loadScript`，否则「改了不生效」。

### 5.4 调度器用法

```java
Path sourceDir = Path.of("./osgi-source");
Path outputDir = Path.of("./osgi-source-target");

SourceJavaCompiler javaCompiler = new SourceJavaCompiler(sourceDir, outputDir, null);
SourceGroovyLoader groovyLoader = new SourceGroovyLoader(sourceDir, "groovy"); // 引擎缺失自动跳过
SourceExtensionScheduler scheduler = new SourceExtensionScheduler(javaCompiler, groovyLoader, 5000L);

scheduler.start();        // 立即执行一轮 + 周期轮询（守护线程 osgi-source-scheduler）
scheduler.reloadIfChanged(); // 也可手动触发一轮（对外暴露，测试/「立刻生效」场景）
scheduler.stop();         // 注销全部扩展点 + 卸载脚本 + 释放 ClassLoader + 清空上下文
```

- 目录指纹 = 各源码文件「相对路径 + 大小 + 修改时间」，不做内容哈希。
- Spring Boot fat jar 场景：`SourceJavaCompiler` 会把 `jar:nested:` 协议的 `BOOT-INF/classes` 与 `BOOT-INF/lib/*.jar` 抽取到输出目录下的 `boot-inf` 缓存（只抽取一次），并把应用 classpath 显式传给 javac；classpath 以 ClassLoader URL 为主来源、`java.class.path` 系统属性兜底。
- 编译产物按轮次放独立子目录（`r<时间戳>`），下一轮开始前清理上一轮目录，避免 class 残留与磁盘堆积；`stop()` 时 `releaseLoader()` 并清空输出目录。
- 需要完整 JDK（`jdk.compiler` 模块提供 `JavaCompiler`），JRE 下编译失败且保留上一轮状态。

---

## 6. OSGi 服务桥接进容器（OsgiBeanDefinitionRegister）

`com.chua.osgi.support.register.impl.OsgiBeanDefinitionRegister extends BeanSingletonRegistry implements BeanDefinitionRegister, BundleLifecycleListener`（`@since 4.0.0.42`，标记 `@SpiIgnore`，不参与 SPI 自动注册）。

- **只读桥接**：`isWritable()` 恒为 `false`，`register` / `unregister` 固定抛 `UnsupportedOperationException`（需要让服务从容器视图消失时，正确做法是卸载对应 bundle 或调整其 `service.ranking`，由生命周期联动推进快照版本）。`getName()` 返回 `"osgi"`，`getPriority()` 返回 `100`。
- **优先级贯通**：OSGi `service.ranking` 经 `clampPriority` 收敛后写入 `BeanDefinition#setPriority(int)`（ranking 越界时取 `Integer.MAX_VALUE` / `Integer.MIN_VALUE`；语义同为「值越大越优先」），容器既有的按优先级选优逻辑无需改动即可选出 ranking 最高的服务。
- **带版本号的快照**：框架状态版本号（`System.nanoTime()` 初始化）仅在 bundle 生命周期事件推进时自增，快照据此判断是否重建，避免每次查询都全量枚举服务注册表；旧快照统一置为不可用并释放单例缓存。
- **Bean 名称**：`服务类型全限定名:服务标识`（分隔符 `:`），名称取自 `OsgiServiceDescriptor#stableId()`（该字段的完整格式由 `utils-support-common-starter` 定义，源码未提供）。
- **验收**：每个投影定义经过 `BeanAcceptance` 验收器链（SPI 装载 + `addAcceptance` 显式追加，任一拒绝即拒绝），拒绝原因记录在 `getRejectedSnapshots()`；`setAcceptanceEnabled(false)` 可关闭验收环节。
- **装配**：`setOsgiLauncher(OsgiLauncher)` 注入 launcher（源码注释：由 Spring 注入），`initialize()` 后注册为 launcher 的 `BundleLifecycleListener` 即可联动失效；开关 `setInvalidateOnBundleLifecycle(boolean)` 默认开启。
- 查询入口：`getBeanDefinition` / `getBeanDefinitionOfType` / `containsBean` / `getBeanDefinitionNames` / `getBeansWithAnnotation` / `getBeansWithMethodAnnotation`；`close()` 关闭并释放缓存。

---

## 7. 配置项

**Spring 配置键：无。** 依据：本模块源码不存在任何 `@ConfigurationProperties` / `application.properties` / Spring 依赖，模块内既有文档亦明确 Spring 环境的属性绑定由 `spring-support-osgi-starter` 负责（其装配代码源码未提供）。

本模块所有运行参数均为**构造参数 / 方法参数 / 私有常量**，没有可配键名：

| 位点 | 参数/常量 | 默认值 |
| --- | --- | --- |
| `FelixOsgiLauncher.start(Map)` | 框架配置，仅 `putIfAbsent` 写入 `org.osgi.framework.storage.clean = onFirstInit` | — |
| `FelixOsgiLauncher`（private 常量） | 事件屏障超时 / 轮询间隔 | `3000L` ms / `2L` ms |
| `FelixOsgiLauncher` | 自动启动开关 | `autoStartInstalledBundles = true` |
| `BundleDeployWatcher` 构造 | 部署目录 / 扫描间隔 / 卸载开关 / 升级开关 | `./osgi-deploy` / `10000L` / 传入值 |
| `SourceExtensionScheduler` 构造 | 轮询间隔（毫秒） | `5000L` |
| `SourceGroovyLoader` 构造 | 引擎名 | `"groovy"` |
| `SourceJavaCompiler` 构造 | 源码目录 / 产物目录 / 应用 ClassLoader | 必传 / 必传 / `null` 时取上下文 ClassLoader |
| `OsgiBeanDefinitionRegister` | `setAcceptanceEnabled` / `setInvalidateOnBundleLifecycle` / `addAcceptance` | `true` / `true` / — |

> `@SourceSpi` 的 javadoc 提及 `plugin.osgi.source.directory`（源码目录的归属键），该键在 Spring 环境的实际装配方式源码未提供。

---

## 8. SPI 扩展点

| 扩展点 | 定义位置 | 本模块行为 |
| --- | --- | --- |
| `BundleApplication` | `com.chua.common.support.osgi`（common-starter） | `FelixOsgiLauncher` 启动时经 `ServiceProvider` 发现并回调 `onBundleStart(ctx)`，停止时回调 `onBundleStop(ctx)`；单个应用异常被隔离并告警 |
| `BundleLifecycleListener` | `com.chua.common.support.osgi`（common-starter） | 经 `FelixOsgiLauncher.addListener` 注册；框架事件统一投递，单个监听器异常隔离 |
| `BeanAcceptance` | `com.chua.common.support.objects.acceptance`（common-starter） | `OsgiBeanDefinitionRegister` 的桥接验收器链：SPI 装载 + `extraAcceptances` 显式追加；验收器异常按拒绝处理 |
| `ScriptProvider`（经 `ScriptProviders` 探测） | `com.chua.common.support.task.script`（common-starter） | `SourceGroovyLoader` 依赖 `groovy` 引擎（源码注释：由 `utils-support-groovy-starter` 提供）；引擎缺失时自动跳过 `.groovy` 源码，不影响 Java 侧 |
| `OsgiBeanDefinitionRegister` 自身 | 本模块 | 标记 `@SpiIgnore`，不参与 SPI 自动注册；launcher 由注入装配 |
| 抽象层实现（非 SPI 注册） | `com.chua.common.support.osgi`（common-starter） | `OsgiLauncher` / `OsgiBundle` / `BundleContext` / `BundleStateQuery` / `OsgiServiceDescriptor` 的 Felix 实现落在 `com.chua.osgi.support` |

---

## 9. 构建与依赖

构建要求：JDK 25、UTF-8 编码；父工程 `com.chua:utils-support-core-parent:4.0.0.51`。模块同时内置 GraalVM `native-image` 配置（`src/main/resources/META-INF/native-image/com.chua/utils-support-osgi-starter/` 下的 `reflect-config.json`、`resource-config.json`）。

依赖（pom.xml 声明 + 依赖树实证，`4.0.0.42` 构建期实测）：

```
utils-support-osgi-starter
├── com.chua:utils-support-common-starter        （同版本 ${project.version}）
├── org.apache.felix:org.apache.felix.framework  （7.0.5）
├── org.apache.felix:org.apache.felix.main       （7.0.5）
├── org.apache.felix:org.apache.felix.gogo.runtime（1.1.6）
├── org.apache.felix:org.apache.felix.gogo.command（1.1.2）
├── org.apache.felix:org.apache.felix.gogo.shell （1.1.4）
├── org.apache.sshd:sshd-core / sshd-osgi / sshd-sftp / sshd-scp  （2.13.2）
├── io.github.classgraph:classgraph              （4.8.180）
└── provided：lombok（1.18.46）、org.jetbrains:annotations、com.google.code.findbugs:jsr305
```

- 源码中直接使用 Felix Framework API 的类只有 `com.chua.osgi.support` 三个主类与部署/桥接类；Gogo shell 套件（telnet 远程管理）与 Apache SSHD（SSH 远程管理，注释要求先装 sshd-core 再装 sshd-osgi/sshd-sftp/sshd-scp）作为 Felix 配套引入，其交互入口在本模块源码中未提供。
- groovy **不在**生产依赖：`SourceGroovyLoader` 靠 `ScriptProviders.resolve` 探测引擎是否可用，缺失时降级为「只支持 Java 源码」。

---

## 10. 边界：这些能力不在本模块

- **不是 Spring 集成模块**：不做属性绑定、不做 Bean 装配；Spring 环境集成由 `spring-support-osgi-starter` 承担（源码未提供）。
- **不制作 bundle 工程**：不提供 bundle 编译、打包、签名、`Import-Package` 版本区间解析等工具。
- **不提供远程管理界面**：Gogo shell / SSH 依赖虽已引入，但 telnet/SSH 控制台交互入口在本模块源码中未提供。
- **不提供跨 JVM 管理**：同一 JVM 只允许一个活动 `Framework` 实例，本模块不管理远程/多进程 OSGi 框架。
- **不内置 Groovy 引擎**：`.groovy` 热重载依赖 `ScriptProvider` 的 `groovy` 引擎（由 `utils-support-groovy-starter` 提供），缺失时自动降级；引擎行为本身不属本模块。
- **不做源码的语义检查与容器注入**：源码编译走 JDK `javac`，需要完整 JDK（`jdk.compiler`）；源码侧 Bean 仅经 `SourceContext` 契约以 Map 暴露，等价于「把容器的某一小块交给源码」，不提供完整 Spring 注入。
- **桥接只读**：`OsgiBeanDefinitionRegister` 只把 OSGi 服务投影为容器定义，不支持反向注册、不支持跨框架状态的手工增删。