# utils-support-email-starter

> utils 体系的**邮件能力模块**：基于 Jakarta Mail 提供 SMTP 发送、IMAP 收件与后台轮询监听，并把「邮件发送」以 SPI 形式（`email`）接入 common 的消息推送体系（`MessagePush`）。
>
> 版本：`4.0.0.51`｜JDK：`25`（根 pom `java.version=25`，maven-compiler-plugin `<release>25</release>`）｜包名：`com.chua.email.support`、`com.chua.email.support.client`

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
    <artifactId>utils-support-email-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块源码位置：`utils-support-parent-starter/utils-support-filesystem-parent/utils-support-email-starter`，父模块为 `utils-support-filesystem-parent`。pom 描述：**邮件发送与解析模块（SMTP/IMAP/POP3）**。主要类标注 `@since 4.0.0.42`。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 发送 | 基于 SMTP（Jakarta Mail + angus-mail）的纯文本 / HTML 邮件发送，支持多收件人、抄送（CC）、密送（BCC）；`attachment(name, bytes)` 仅登记附件参数，发送链路未装载附件（见第 7 节） | 不是邮件服务器，也不提供投递重试、发信队列等网关能力 |
| 接收 | 基于 IMAPS 的邮件拉取（`fetch`）、未读数统计（`count`）、后台轮询监听（`watch`）与 `PolledDirectory` 目录式轮询（`EmailDirectory`） | 不做复杂 MIME 树解析（正文以 `msg.getContent().toString()` 形式返回） |
| 集成 | 通过 `@Spi("email")` 注册为 common 的 `MessagePush` 实现，按名称 `email` 装配 | 不含 Spring Boot 自动装配、HTTP 端点或管理界面 |
| 依赖 | 仅依赖 `utils-support-common-starter`、`jakarta.mail-api`、`angus-mail`（lombok 由父模块 `provided`） | 不依赖 Spring、数据库驱动或具体邮箱厂商 SDK |

### 1.3 规模

- 主源码类 **3** 个：`EmailPush`（SPI 推送实现）、`EmailClient`（发送 + 收件门面）、`EmailDirectory`（`PolledDirectory` 轮询目录）。
- SPI 注册文件 **1** 个：`src/main/resources/META-INF/extensions/com.chua.common.support.task.message.MessagePush`。

---

## 2. 核心 API 与速查表

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 最简发信 | `EmailClient.create(smtpHost, username, password)` | 快捷工厂，等价于 builder 三件套后 `build()` |
| 链式发信 | `client.send().to(...).subject(...).body(...).html(...).exec()` | 另提供 `cc` / `bcc` / `attachment` / `from` 链式方法（`attachment` 仅登记参数不随邮件发出、`from` 不生效，见第 7 节），返回 `SendResult` |
| 拉取邮件 | `client.fetch().folder("INBOX").limit(20).exec()` | 走 IMAPS，返回 `List<Map<String, Object>>`（`subject`/`from`/`date`/`size`/`content`） |
| 未读数 | `client.getUnreadCount("INBOX")` | 内部为 `fetch().folder(folder).unreadOnly(true).count()` |
| 后台监听 | `client.watch().folder("INBOX").pollInterval(60).onMessage(...).start()` | 守护线程轮询未读邮件，`stop()` 停止 |
| SPI 消息推送 | `ServiceProvider.of(MessagePush.class).getExtension("email")` | 得到 `EmailPush`，走 `MessageRequest` / `MessageResponse` 契约 |
| 目录式轮询 | `EmailDirectory.builder()...build()` + `addListener(...)` + `start(...)` | 实现 `PolledDirectory`，按 `MessageID` 去重后回调 `onCreate` |
| SMTP 可用性 | 构造 `EmailClient` 时自动探测 | 不可用时发送自动降级（见 2.4） |

### 2.1 EmailClient（发送 + 收件门面）

```java
EmailClient client = EmailClient.builder()
        .smtpHost("smtp.example.com")     // 默认端口 587
        .imapHost("imap.example.com")     // 默认端口 993；不填则由 smtpHost 把 "smtp" 替换为 "imap" 推断
        .pop3Host("pop3.example.com")     // 默认端口 110；不填则由 smtpHost 把 "smtp" 替换为 "pop3" 推断
        .username("user@example.com")
        .password("pass")
        .build();                          // 构造时探测 SMTP 可用性（连接超时 5s）
```

### 2.2 SendOperation / SendResult

```java
EmailClient.SendResult result = client.send()
        .to("to@example.com")             // 收件人（多个用逗号分隔，交由 InternetAddress.parse 解析）
        .cc("cc1@example.com")            // 抄送，可多次调用
        .bcc("bcc1@example.com")          // 密送，可多次调用
        .subject("主题")
        .body("<h1>正文</h1>")
        .html(true)                        // 默认 false（纯文本 utf-8）；true 时按 text/html; charset=utf-8 发送
        .attachment("报表.pdf", bytes)     // 附件：文件名 + 字节（仅存入 attachments Map，SMTP 发送未装载，见第 7 节）
        .exec();                           // 返回 SendResult(success, messageId, message)

result.success(); result.messageId(); result.message();
```

### 2.3 FetchOperation / WatchOperation

```java
// 拉取：folder 默认 INBOX，limit 默认 20，unreadOnly 默认 false
List<Map<String, Object>> mails = client.fetch()
        .folder("INBOX").limit(10).unreadOnly(true)
        .exec();

int unread = client.fetch().folder("INBOX").unreadOnly(true).count();

// 监听：pollInterval 单位秒，默认 60；每次轮询取最新 10 封未读。
// 注意：watch() 每次返回新的操作对象，需持有引用以便 stop()。
EmailClient.WatchOperation watch = client.watch()
        .folder("INBOX")
        .pollInterval(60)
        .onMessage(mail -> System.out.println(mail.get("subject")))
        .start();
// ...
watch.stop();
```

### 2.4 降级行为

- `EmailClient` 构造时用 `Transport.connect(smtpHost, smtpPort, username, password)` 探测 SMTP；失败仅 `log.debug` 并标记 `smtpAvailable=false`，不抛异常。
- SMTP 不可用时，`send(...).exec()` 走降级路径：记录日志并返回 `SendResult(true, "queue-" + System.currentTimeMillis(), "已加入本地队列（SMTP 不可用）")`。**源码中未见实际的本地队列存储或补发线程**，调用方不应据此认为邮件必然送达。

### 2.5 EmailDirectory（PolledDirectory 轮询目录）

```java
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;

import java.util.concurrent.TimeUnit;

EmailDirectory dir = EmailDirectory.builder()
        .imapHost("imap.example.com")   // 必填，缺失时 build() 抛 IllegalArgumentException
        .imapPort(993)                   // 默认 993
        .username("user@example.com")    // 必填
        .password("pass")
        .folder("INBOX")                 // 默认 INBOX
        .pollInterval(60)                // 轮询间隔（秒），默认 60
        .build();

dir.addListener(new SimplePolledListener() {
    @Override
    public void onCreate(WatcherEvent event, EventObserver observer) {
        System.out.println(observer.getSource());
    }
});

// DirectoryPollerEnvironment 没有 defaults() 工厂，只有 3 参构造：
// events 传 null 表示监听全部事件，60 为轮询间隔，单位秒
dir.start(new DirectoryPollerEnvironment(null, 60, TimeUnit.SECONDS));
// 单参 start(env) 默认包装 VirtualThreadPollerExecutor；
// 只有走两参 start(env, null)，实现才自建守护线程 email-poller
// ...
dir.close();
```

每轮 `upgrade()` 拉取收件箱最新约 10 封，按 `MimeMessage#getMessageID()` 去重（`seenMessageIds`），新邮件以 `EventObserver`（`currentPath=folder`、`triggerFile=messageId`、`source={messageId, subject, from, date, content}`、`eventType=WatcherEvent.CREATE`）回调每个 `PolledListener#onCreate`。

---

## 3. 快速开始

最短可运行示例（对应源码 Javadoc 用法）：

```java
import com.chua.email.support.client.EmailClient;
import com.chua.email.support.client.EmailClient.SendResult;

import java.util.List;
import java.util.Map;

public class EmailQuickStart {

    public static void main(String[] args) {
        EmailClient client = EmailClient.builder()
                .smtpHost("smtp.example.com").smtpPort(587)
                .username("user@example.com").password("pass")
                .imapHost("imap.example.com").imapPort(993)
                .build();

        // 1) 发送（HTML）
        SendResult result = client.send()
                .to("recipient@example.com")
                .subject("Hello")
                .body("<p>World</p>")
                .html(true)
                .exec();
        System.out.println(result.success() + " / " + result.message());

        // 2) 拉取收件箱最新 10 封
        List<Map<String, Object>> mails = client.fetch().folder("INBOX").limit(10).exec();
        mails.forEach(m -> System.out.println(m.get("subject") + " <- " + m.get("from")));

        // 3) 后台监听新邮件（守护线程，main 结束即退出）
        EmailClient.WatchOperation watch = client.watch()
                .folder("INBOX")
                .pollInterval(60)
                .onMessage(m -> System.out.println("收到: " + m.get("subject")))
                .start();
    }
}
```

走 SPI 契约发送（`EmailPush`）：

```java
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.task.message.MessagePush;
import com.chua.common.support.task.message.MessageRequest;
import com.chua.common.support.task.message.MessageResponse;

// SPI 名称固定为 "email"（见 META-INF/extensions 注册文件）
MessagePush push = ServiceProvider.of(MessagePush.class).getExtension("email");

// MessageRequest 需提供：to、cc（List）、subject、contentType（"html" 或其他）、content；
// 返回 MessageResponse(success, messageId, durationMillis)。
// MessageRequest 的构建方式见 utils-support-common-starter，本模块源码未提供示例。
MessageResponse response = push.send(buildRequest());
```

`EmailPush` 的环境配置在构造时通过 `MessageEnvironment` 传入（键见第 4 节），无参构造使用空环境。

---

## 4. 配置项

### 4.1 EmailPush 环境键（`MessageEnvironment`）

| 键 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `smtp.host` | 是 | 无 | SMTP 服务器地址 |
| `smtp.port` | 否 | `587` | SMTP 端口 |
| `smtp.username` | 否 | 无 | SMTP 用户名（认证时与 `smtp.password` 同时存在才启用 `Authenticator`） |
| `smtp.password` | 否 | 无 | SMTP 密码 / 授权码 |
| `smtp.from` | 否 | 取 `smtp.username` | 发件人地址 |
| `smtp.auth` | 否 | `true` | 是否需要认证 |
| `smtp.starttls` | 否 | `true` | 是否启用 STARTTLS |
| `smtp.ssl.enable` | 否 | `false` | 是否启用隐式 TLS（如 465/SMTPS） |

行为细节（源码内置，不可配）：

- 连接超时 `mail.smtp.connectiontimeout=5000`，读超时 `mail.smtp.timeout=30000`，写超时 `mail.smtp.writetimeout=30000`。
- `smtp.ssl.enable=true` 与 `smtp.starttls=true` 互斥，SSL 优先（隐式 TLS 信道上不能再发起 STARTTLS）。
- `contentType` 为 `html`（忽略大小写）时正文按 `text/html; charset=utf-8` 发送，否则按纯文本 `utf-8`。

### 4.2 EmailClient / EmailDirectory 构建参数

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `smtpHost` / `smtpPort` | 无 / `587` | SMTP 服务器 |
| `imapHost` / `imapPort` | 无 / `993` | IMAP 服务器；`imapHost` 缺省时由 `smtpHost.replace("smtp", "imap")` 推断 |
| `pop3Host` / `pop3Port` | 无 / `110` | POP3 服务器；`pop3Host` 缺省时由 `smtpHost.replace("smtp", "pop3")` 推断 |
| `username` / `password` | 无 | 登录凭据 |
| `folder`（EmailDirectory / WatchOperation / FetchOperation） | `INBOX` | 收件文件夹 |
| `pollInterval` | `60` 秒 | 轮询间隔 |
| `limit`（FetchOperation） | `20` | 单次拉取条数 |
| `unreadOnly`（FetchOperation） | `false` | 是否只取未读 |

`EmailDirectory.Builder#build()` 在 `imapHost` 或 `username` 为空时抛 `IllegalArgumentException`。

### 4.3 Spring / 系统属性配置

**无。** 本模块不读取 Spring 配置文件、环境变量或系统属性；所有配置通过 `MessageEnvironment`（`EmailPush`）或 `Builder`（`EmailClient` / `EmailDirectory`）在代码中显式传入。依据：pom 无 Spring 依赖，源码中无 `@ConfigurationProperties` / `@Value` / `System.getProperty` 等读取逻辑。

---

## 5. SPI 扩展点

**本模块注册的 SPI（对外提供）：**

| SPI 接口 | 名称 | 实现 | 注册文件 |
| --- | --- | --- | --- |
| `com.chua.common.support.task.message.MessagePush` | `email` | `com.chua.email.support.EmailPush` | `META-INF/extensions/com.chua.common.support.task.message.MessagePush`（内容 `email=com.chua.email.support.EmailPush`） |

实现类标注 `@Spi("email")` 与 `@SpiDescribe(value = "邮件推送", type = "EMAIL", desc = "基于 SMTP 协议发送纯文本或 HTML 邮件，支持多收件人、抄送与阅读追踪")`，可选参数声明：`smtp.host`、`smtp.port`（默认 587）、`smtp.username`、`smtp.password`、`smtp.from`。

**本模块暴露给外部扩展的 SPI：无。** `EmailClient`、`EmailDirectory` 是普通类 + Builder，不是 SPI；如需接入新的消息通道（短信、IM 等），应在各自模块实现 `MessagePush` 并注册自己的 extensions 文件，而不是修改本模块。

**依赖的 common 侧契约（由 `utils-support-common-starter` 提供，本模块只消费）：**

- 消息推送：`MessagePush`、`MessageEnvironment`、`MessageRequest`、`MessageResponse`、`TemplateInfo`
- 目录轮询：`PolledDirectory`、`PolledListener`、`WatcherEvent`、`EventObserver`、`DirectoryPollerEnvironment`、`DirectoryPollerExecutor`
- 工具：`Wraps`、`StringUtils`、`@Spi` / `@SpiDescribe` / `@SpiParam`

---

## 6. 构建与依赖

### 6.1 依赖清单（pom.xml）

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（= 4.0.0.51） | 契约与工具（SPI、MessagePush、PolledDirectory、Wraps 等） |
| `jakarta.mail:jakarta.mail-api` | 由父 pom 管理 | Jakarta Mail API |
| `org.eclipse.angus:angus-mail` | 由父 pom 管理 | Jakarta Mail 实现（SMTP/IMAP/POP3） |
| `org.projectlombok:lombok` | `provided`（父模块注入） | 编译期生成 getter 等 |

### 6.2 构建命令

```bash
# 在本模块目录
mvn -o -q compile -DskipTests

# 或从 utils-support-filesystem-parent 目录按模块构建
mvn -o -q -pl utils-support-email-starter compile -DskipTests
```

GraalVM 原生镜像：父模块提供 `native` profile（`native-maven-plugin`），本模块未附加额外 native 配置文件（源码中未见 `reflect-config.json` 等）。

---

## 7. 边界：这些能力不在本模块

| 能力 | 现状 | 应去哪里 |
| --- | --- | --- |
| 邮件服务器 / 网关、投递重试 | 无。SMTP 不可用时的「本地队列」仅返回入队提示，源码中未见队列存储与补发实现 | 需要可靠投递请自建队列或使用专业邮件服务 |
| POP3 收信 | `Builder` 保留了 `pop3Host` / `pop3Port`（默认 110），但 `fetch` / `watch` / `EmailDirectory` 均只实现 IMAPS；源码中未见 POP3 收信路径 | 如需 POP3 拉取需自行扩展 |
| 关键字搜索过滤 | `FetchOperation.search(s)` 仅保存关键字，`exec()` 中未见使用，不改变拉取结果 | 自行在返回结果上过滤 |
| 自定义发件人生效 | `SendOperation.from(String)` 保存了值，但 SMTP 发送实际使用 `username` 作为发件人（`message.setFrom(new InternetAddress(client.username))`） | 发件人需与登录账号一致时无需设置；确需别名请扩展实现 |
| 发送附件 | `attachment(name, bytes)` 仅把附件写入 `attachments` Map，`sendViaSmtp()` 未构建 Multipart/MimeBodyPart，附件不会随邮件发出 | 如需真实附件发送，需扩展 `SendOperation` |
| 模板渲染与持久化 | `EmailPush#registerTemplate` / `listTemplates` / `getTemplate` 仅为进程内 `ConcurrentHashMap` 存储，无持久化、无默认模板；模板渲染逻辑源码未提供 | 在上层业务实现模板管理 |
| 附件解析、HTML 清洗、反垃圾、归档审计 | 无 | 不属于本模块职责 |
| Spring Boot 自动装配、HTTP 端点、定时任务注册 | 无。`watch` / `EmailDirectory` 的轮询线程由调用方显式 `start` / `stop` / `close` | Spring 集成由上层工程完成 |
| 其它消息通道（短信、钉钉、企微等） | 无。本模块只注册 `email` 一个 `MessagePush` 实现 | 各通道在对应模块实现并注册 SPI |
