# utils-support-osgi-starter

Utils Support OSGI Starter - Apache Felix Framework

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-osgi-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 模块职责

本模块只做一件事：把 `utils-support-common-starter` 中的**纯 JDK 抽象**（`com.chua.common.support.osgi`）
落到 Apache Felix 上。抽象与实现严格分离，因此不依赖框架的模块可以直接复用抽象层。

```
utils-support-common-starter   com.chua.common.support.osgi（零三方依赖）
        │  OsgiLauncher / OsgiBundle / BundleContext / BundleStateQuery
        │  BundleApplication / BundleLifecycleListener / OsgiServiceDescriptor
        ▼
utils-support-osgi-starter      com.chua.osgi.support（Felix 实现）
```

---

## 能力清单

| 类 | 说明 |
|---|---|
| `FelixOsgiLauncher` | Felix 框架启动器。实现 `OsgiLauncher` + `BundleStateQuery`，负责生命周期、服务查询、bundle 增删改查、热升级、服务引用持有 |
| `FelixOsgiBundle` | `Bundle` 包装。提供符号名/版本/位置/MANIFEST 头/状态查询、服务注册注销、原生 update |
| `FelixBundleContext` | `BundleContext` 包装。提供带属性注册、按实例注销、服务类型枚举、框架视图刷新 |
| `BundleDeployWatcher` | 目录热部署。周期扫描目录，按符号名增量安装 / 原地升级 / 卸载 |
| `OsgiBeanDefinitionRegister` | `BeanDefinitionRegister` 实现，把 OSGi 服务桥接进核心对象上下文（只读） |

---

## 关键设计

### 事件由框架驱动，不由客户端方法触发

启动器在框架上下文上注册**同步 `BundleListener`**，所有生命周期事件都从框架事件总线投递。
客户端调用 `installBundle` / `uninstallBundle` / `updateBundle` 时**不再手工 fire 事件**。

这样做的收益：通过任何途径（含直接操作框架上下文、bundle 自身 resolve 失败）
引发的状态变化都能被感知，且同一个事件只会被投递一次。

| 框架事件 | 投递的回调 |
|---|---|
| `INSTALLED` | `onBundleInstalled` |
| `STARTED` | `onBundleStarted` |
| `STOPPED` | `onBundleStopped` |
| `UPDATED` | `onBundleUpdated` |
| `UNRESOLVED` | `onBundleResolveFailed` |
| `UNINSTALLED` | `onBundleStateChanged` + `onBundleRemoved`（内部先触发 `onBundleUninstalled`） |
| 其余（`STARTING`/`STOPPING`/`RESOLVED` 等） | `onBundleStateChanged`（旧状态取自内部状态快照） |

单个监听器抛异常会被隔离，不影响其余监听器。

### 服务引用由启动器持有

`getServices` / `getService` 取到的实例会连同 `ServiceReference` 一并被启动器持有
（引用计数 +1），在 `releaseServices(type)` / `releaseAllServices()` / `stop()` 前始终有效。

> 若沿用「取到即 unget」的写法，引用计数归零后框架可立即回收服务，
> 调用方持有的实例会在毫无征兆的情况下失效。

### 幂等的启停

`start` / `stop` 均串行化且幂等：

- 框架已活动时 `start` 直接返回，不会新建第二个 `Framework`（同一 JVM 只允许一个活动实例）
- 已 `init` 未 `start` 时复用同一实例，本次传入的配置不再生效并打印提示
- `stop` 依次回调 `BundleApplication#onBundleStop` → 释放服务引用 → 注销监听器 → 清空缓存
  → 停止框架 → 清除全局单例

### 热升级走框架原生 update

`updateBundle(symbolicName, url)` 调用框架原生 `Bundle#update`，
保留 bundle 标识与已解析的依赖关系，不存在「卸载完到重装完」的时间窗口；
升级前处于活动状态则升级后自动重新启动。

> Felix 7 的 `org.osgi.framework.Bundle` 只提供 `update()` 与 `update(InputStream)`，
> **没有** `update(URL)` 重载，因此传 URL 时由本实现打开流后交由框架在本次调用内消费。

---

## 目录热部署

```java
BundleDeployWatcher watcher = new BundleDeployWatcher(
        launcher, "./osgi-deploy", 10000L, true, true);
watcher.start();   // 周期扫描
watcher.stop();    // 停止
```

| 参数 | 含义 |
|---|---|
| `uninstallOnRemoved` | 目录中 jar 被删除后卸载对应 bundle |
| `updateOnChanged` | 目录中 jar 内容变化（最后修改时间 + 文件大小）时对已安装 bundle 执行原地升级 |

符号名取自 jar 的 `MANIFEST`，会剥离 `;singleton:=true` 之类的指令后缀；
目录中的**文件名不必等于符号名**，符号名与文件的对应关系始终以 MANIFEST 为准。

---

## 配置说明

本模块为零配置模块，引入依赖后即可直接使用 `FelixOsgiLauncher`；
Spring 环境下的属性绑定由 `spring-support-osgi-starter` 负责。

---

## 依赖关系

```
utils-support-osgi-starter
├── utils-support-common-starter
├── org.apache.felix:org.apache.felix.framework
├── org.apache.felix:org.apache.felix.gogo.{runtime,command,shell}
├── org.apache.sshd:{sshd-core,sshd-osgi,sshd-sftp,sshd-scp}
└── io.github.classgraph:classgraph
```
