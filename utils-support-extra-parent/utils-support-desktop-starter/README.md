# utils-support-desktop-starter

> utils 体系的**桌面通知原生实现**模块：把统一的 `MessagePush` 消息推送契约落到 Windows / macOS / Linux 的系统级桌面通知上，供消息推送体系按 SPI 名 `desktop` 装配使用。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.desktop.support`

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
    <artifactId>utils-support-desktop-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 定位 | `MessagePush` 的 `desktop` SPI 实现：调用操作系统原生能力发系统通知 | 不是桌面 GUI 框架，不创建窗口 / 托盘应用 |
| 平台 | Windows（PowerShell 气泡通知）、macOS（osascript）、Linux 及其它（notify-send） | 不含浏览器 Web 通知、不含移动端推送 |
| 依赖 | 仅依赖 `utils-support-common-starter`，自身不引入第三方依赖 | 不依赖 Spring、无自动装配、无配置文件 |
| 模板 | 内存模板表 + `MessagePush` 默认 `sendTemplate` 占位符替换 | 不做模板持久化、不做发送记录存储 |

**判断口径**：要在本机弹一条系统通知 → 用本模块；要发邮件 / 短信 / IM → 找 `MessagePush` 的其它 SPI 实现；要做桌面自动化 / 系统托盘 → 本模块不提供。

### 1.3 模块组成

| 类型 | 名称 | 说明 |
| --- | --- | --- |
| SPI 实现 | `DesktopMessagePush` | `MessagePush` 的桌面通知实现，SPI 名 `desktop` |
| 内部接口 | `NativeDesktopNotifier` | 平台无关抽象：`notify(title, content, icon)` / `isSupported()` / `getPlatform()` |
| 平台实现 | `WindowsDesktopNotifier` | Windows，PowerShell `ShowBalloonTip` |
| 平台实现 | `MacOsDesktopNotifier` | macOS，`osascript` 通知 |
| 平台实现 | `LinuxDesktopNotifier` | Linux 及其它，`notify-send` |

SPI 注册文件：`src/main/resources/META-INF/extensions/com.chua.common.support.task.message.MessagePush`，内容仅一行：

```text
desktop=com.chua.desktop.support.DesktopMessagePush
```

---

## 2. 核心 API 与速查表

### 2.1 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 发一条桌面通知 | `MessagePush.create("desktop").send(request)` | 标准 `MessagePush` 用法 |
| 不走 SPI 直接用 | `new DesktopMessagePush()` | 等价，省去 SPI 发现 |
| 定制标题 / 内容 | `MessageRequest.builder().subject(...).content(...)` | `subject` 为空时回退 `desktop.title` |
| 定制默认标题 | `new DesktopMessagePush(new MessageEnvironment().set("desktop.title", "..."))` | 仅请求未带 `subject` 时生效 |
| 设置通知图标 | `MessageEnvironment.set("desktop.icon", path)` | 仅 Linux 实现实际使用 |
| 判断平台是否支持 | `NativeDesktopNotifier.create().isSupported()` | 不支持时 `send` 返回失败响应而非抛异常 |
| 查询当前平台名 | `NativeDesktopNotifier.create().getPlatform()` | `windows` / `macos` / `linux` |
| 模板发送 | `registerTemplate(TemplateInfo)` + `sendTemplate(...)` | `{{参数}}` 占位符替换，模板存内存 |

### 2.2 DesktopMessagePush API

```java
public class DesktopMessagePush implements MessagePush {
    public DesktopMessagePush();                                // 默认空环境
    public DesktopMessagePush(MessageEnvironment environment);  // 携带 desktop.* 配置

    public String getProvider();                                // 恒为 "desktop"
    public MessageResponse send(MessageRequest request) throws Exception;
    public List<TemplateInfo> listTemplates();                  // 内存模板表（默认为空）
    public TemplateInfo getTemplate(String templateId);
    public void registerTemplate(TemplateInfo template);        // 本模块自有方法
}
```

`send` 的执行流程（与源码一致）：

1. 标题取 `request.getSubject()`，为空回退环境键 `desktop.title`（默认 `通知`）；
2. 内容取 `request.getContent()`，图标取环境键 `desktop.icon`；
3. `NativeDesktopNotifier.create()` 按当前操作系统选择平台实现；
4. 平台不支持 → 返回 `success=false`、`errorMessage="当前平台不支持桌面通知: <平台>"`，不抛异常；
5. 平台支持 → 同步启动系统命令并 `waitFor()` 等待结束，成功返回 `success=true`、随机 UUID `messageId`、`durationMillis` 耗时。

配套契约（来自 `utils-support-common-starter` 的 `com.chua.common.support.task.message` 包）：

- `MessageRequest`：构建器模式，`to` 必填非空（桌面通知不使用该字段），常用 `subject` / `content`；
- `MessageResponse`：`isSuccess()` / `getMessageId()` / `getErrorMessage()` / `getDurationMillis()`；
- `MessageEnvironment`：`set(key, value)` 链式写入，`get(key)` / `get(key, defaultValue)` 读取。

---

## 3. 快速开始

### 3.1 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-desktop-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短可运行示例

```java
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;

public class DesktopNotifyDemo {

    public static void main(String[] args) throws Exception {
        // SPI 名固定为 "desktop"；也可直接 new DesktopMessagePush()
        MessagePush push = MessagePush.create("desktop");

        MessageResponse resp = push.send(MessageRequest.builder()
                .to("local")                     // MessageRequest 必填项，桌面通知不使用
                .subject("构建完成")              // 为空时回退 desktop.title（默认「通知」）
                .content("utils-support-desktop-starter 通知已发出")
                .build());

        System.out.println(resp.isSuccess());    // 平台支持时为 true
    }
}
```

运行后：Windows 弹出系统气泡通知，macOS 走系统通知中心，Linux 调用 `notify-send`。

### 3.3 携带环境配置

```java
import com.chua.common.support.task.message.MessageEnvironment;
import com.chua.desktop.support.DesktopMessagePush;

MessageEnvironment env = new MessageEnvironment()
        .set("desktop.title", "运维提醒")
        .set("desktop.icon", "/path/to/icon.png");   // 仅 Linux 生效

MessagePush push = new DesktopMessagePush(env);
```

### 3.4 模板发送

```java
import com.chua.common.support.task.message.TemplateInfo;
import com.chua.desktop.support.DesktopMessagePush;
import java.util.Map;

DesktopMessagePush push = new DesktopMessagePush();
push.registerTemplate(new TemplateInfo(
        "deploy-done", "部署完成", "服务 {{name}} 已上线", "notification", null));

push.sendTemplate("deploy-done", "local", Map.of("name", "order-service"));
```

---

## 4. 配置项

| 键 | 说明 | 默认值 | 生效范围 |
| --- | --- | --- | --- |
| `desktop.title` | 通知标题；请求未带 `subject` 时使用 | `通知` | 全平台 |
| `desktop.icon` | 通知图标路径 | 无 | 仅 Linux（`notify-send -i`） |

- 配置载体是 `MessageEnvironment`（`set` / `get`），通过 `new DesktopMessagePush(environment)` 注入；`@SpiDescribe` 元数据的 `optional` 字段同样声明了这两个键。
- 本模块**没有**基于 `application.yml` / `*.properties` 的配置加载，也没有 Spring 配置绑定（`pom.xml` 仅依赖 `utils-support-common-starter`，不含 Spring）。
- 平台行为参数（如 Windows 气泡展示时长 5000ms）为源码内固定值，不是配置项。

---

## 5. SPI 扩展点

### 5.1 作为 MessagePush 扩展被装配

本模块向 `MessagePush` SPI 注册名为 `desktop` 的实现。调用方两种等价入口：

```java
MessagePush viaSpi = MessagePush.create("desktop");   // 内部走 ServiceProvider.getNewExtension，每次新实例
MessagePush direct = new DesktopMessagePush();        // 直接构造
```

### 5.2 平台分发（NativeDesktopNotifier）

`NativeDesktopNotifier` 是模块内部的平台抽象，`static create()` 按 `OsFamily.current()` 静态分派：

| 平台判定 | 实现 | 系统调用 | `getPlatform()` |
| --- | --- | --- | --- |
| `OsFamily.isWindows()` | `WindowsDesktopNotifier` | `powershell -NoProfile -Command`（`System.Windows.Forms.NotifyIcon.ShowBalloonTip(5000, …)`，单引号加倍转义） | `windows` |
| `OsFamily.isMacOs()` | `MacOsDesktopNotifier` | `osascript -e 'display notification "内容" with title "标题"'`（反斜杠与双引号转义） | `macos` |
| 其它（含 Linux） | `LinuxDesktopNotifier` | `notify-send [-i icon] title content` | `linux` |

- 三个实现均同步启动子进程并 `waitFor()` 等待退出；
- Windows 与 macOS 实现不消费 `icon` 参数，仅 Linux 将其作为 `notify-send -i` 传入；
- 注意：`LinuxDesktopNotifier.isSupported()` 只排除 Windows / macOS，并不检查 `notify-send` 是否安装；命令缺失时 `notify` 抛出的异常会由 `send` 向上传播。

### 5.3 如何扩展

- **新增消息渠道**（邮件、飞书等）：不要改本模块。在自己的模块实现 `MessagePush`，标注 `@Spi("渠道名")`，并在 `META-INF/extensions/com.chua.common.support.task.message.MessagePush` 登记实现类，即可与 `desktop` 并列被 `MessagePush.create(...)` 装配。
- **新增桌面平台**：`NativeDesktopNotifier.create()` 是硬编码静态工厂，本模块未为 `NativeDesktopNotifier` 提供 SPI 注册文件，无法通过 META-INF 追加新平台实现；如需扩展须修改本模块源码。

---

## 6. 构建与依赖

- Maven 坐标：`com.chua:utils-support-desktop-starter:4.0.0.51`；父模块链：`utils-support-parent-starter` → `utils-support-extra-parent` → 本模块。
- 编译目标：JDK 25（根 POM `maven.compiler.source` / `maven.compiler.target` = 25，源码编码 UTF-8）。
- 运行依赖：仅 `com.chua:utils-support-common-starter`（`MessagePush` 契约、`MessageEnvironment`、`OsFamily`、`StringUtils`、`Wraps` 均来自它），无其它第三方依赖。
- 平台运行前提：Windows 需 PowerShell（系统自带）；macOS 需 `osascript`（系统自带）；Linux 需系统已安装 `notify-send`。
- 构建命令（仓库根目录）：`mvn -o -q compile -DskipTests`；单独构建本模块：`mvn -o -q -pl utils-support-extra-parent/utils-support-desktop-starter -am compile -DskipTests`。

---

## 7. 边界：这些能力不在本模块

| 能力 | 去哪找 / 说明 |
| --- | --- |
| 邮件 / 短信 / 飞书 / 钉钉推送 | 其它 `MessagePush` SPI 实现（`MessageEnvironment` 注释列有 email / sms / feishu / dingding 的配置键），不在本模块 |
| 桌面 GUI、系统托盘、窗口程序 | 本模块只发系统通知，不构建桌面应用 |
| 发送记录持久化、失败重试、异步队列 | `send` 为同步一次性调用；重试 / 编排需求用 common-starter 的 `task.retry` / `task.pipeline` |
| 模板持久化管理 | 模板表是进程内 `ConcurrentHashMap`，进程重启即失 |
| Windows / macOS 的通知图标 | 这两个平台实现不消费 `desktop.icon`，仅 Linux `notify-send -i` 生效 |
| Spring Boot 自动装配 | 本模块不是 Spring starter，无自动配置类；按 SPI 手动装配 |
| 浏览器 Web 通知 / 移动端推送 | 不在本模块范围 |
