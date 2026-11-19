# utils-support-webrtc-starter

> 基于 `dev.onvoid.webrtc:webrtc-java` 的 **WebRTC 远程控制模块**：Java 服务端完成 WebSocket 信令、桌面采集与键鼠注入，浏览器端通过 WebRTC 媒体流 + DataChannel 实现「看屏 + 远控」。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.webrtc`｜底层：`webrtc-java 0.14.0`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 包地图](#2-包地图)
- [3. 快速开始](#3-快速开始)
- [4. 信令协议](#4-信令协议)
- [5. 会话与连接](#5-会话与连接)
- [6. 桌面采集](#6-桌面采集)
- [7. 远程键鼠控制](#7-远程键鼠控制)
- [8. 安全模型](#8-安全模型)
- [9. 配置项](#9-配置项)
- [10. SPI 扩展点](#10-spi-扩展点)
- [11. 构建与依赖](#11-构建与依赖)
- [12. 边界：这些能力不在本模块](#12-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-webrtc-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 角色 | Java 服务端 WebRTC 远控实现：信令（自实现 WebSocket）+ 桌面采集（`VideoDesktopSource`）+ 键鼠注入（`java.awt.Robot`）+ `control` DataChannel | 不是浏览器端 SDK（仅附带一个演示页 `demo/webrtc-client.html`） |
| 抽象 | `WebRtcService` / `ScreenCaptureService` / `ControlService` 三个契约接口 | 不是 SFU / TURN 等媒体服务器 |
| 依赖 | 只依赖 common-starter、webrtc-java、spring-boot-starter-websocket / autoconfigure | 不依赖数据库驱动、不依赖前端框架 |

### 1.3 规模

- Java 源文件 **26** 个，顶层包 **8** 个（`spi`、`server`、`model`、`control`、`capture`、`input`、`config`、`error`）。
- SPI 注册文件 1 个：`META-INF/services/com.chua.webrtc.spi.WebRtcService` → `com.chua.webrtc.server.WebRtcServer`。
- 内置浏览器演示页：`src/main/resources/demo/webrtc-client.html`。

### 1.4 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 启动远控服务 | `new WebRtcServer(setting)` + `start()` | 监听 `ws://host:port/webrtc` |
| 创建 / 查询会话 | `createSession(id)` / `session(id)` / `sessions()` | `WebRtcService` 方法 |
| 处理信令 | `receiveSignaling(sessionId, request)` | offer / answer / ice-candidate |
| 查会话下的连接 | `peers(sessionId)` | `List<WebRtcPeer>` |
| 桌面采集 | `extensions().screenCaptureService()` | 帧率 / 分辨率 / 屏幕列表 |
| 键鼠控制 | `extensions().controlService()` | 注册监听器、注入控制事件 |
| 销毁会话 | `destroySession(sessionId)` | 释放 Peer 与采集资源 |
| 服务端主动下发信令 | `WebRtcServer.sendJson(sessionId, json)` | 经信令 Socket 回发浏览器 |
| 信令访问令牌 | `setSignalingToken(token)` | 握手 `?token=` 校验 |

---

## 2. 包地图

| 包 | 文件数 | 职责 | 代表类型 |
| --- | --- | --- | --- |
| `spi` | 2 | 对外 SPI 契约 | `WebRtcService`、`spi.media.ScreenCaptureService` |
| `server` | 4 | 服务端实现：信令、会话、连接 | `WebRtcServer`、`WebRtcSignalingHandler`、`WebRtcSessionImpl`、`WebRtcPeerImpl` |
| `model` | 12 | 会话 / 连接 / 信令 / 控制事件 / 媒体轨道模型 | `WebRtcSession`、`WebRtcPeer`、`WebRtcSignalingRequest`、`WebRtcSignalingResponse`、`ControlEvent`、`MediaStreamTrack` |
| `control` | 2 | 控制面板 SPI | `ControlService`、`ControlEventListener` |
| `capture` | 1 | 桌面采集实现 | `JavaScreenCaptureService` |
| `input` | 2 | 键鼠注入实现与异常 | `JavaInputSender`、`UnavailableSystemInput` |
| `config` | 1 | 配置属性 | `WebRtcProperties` |
| `error` | 1 | 异常 | `WebRtcException` |

---

## 3. 快速开始

### 3.1 启动服务端

```java
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.webrtc.server.WebRtcServer;

public class WebRtcRemoteDemo {
    public static void main(String[] args) {
        WebRtcServer server = new WebRtcServer(ServerSetting.builder()
                .host("0.0.0.0")                    // 默认即 0.0.0.0
                .port(10000)                        // 信令端口
                .protocol(ProtocolType.WS.name())   // WebSocket 信令（ServerSetting.protocol 为 String）
                .build());
        server.start();
    }
}
```

- 无参构造 `new WebRtcServer()` 等价于 `new WebRtcServer(ServerSetting.defaults())`，此时端口为 `ServerSetting` 的默认值 `8080`。
- `start()`（`doStart()`）会：创建 webrtc-java 的 `PeerConnectionFactory` 并注入采集服务；在 `host:port` 上监听 WebSocket 升级请求（RFC 6455 握手、帧收发、ping/pong/close）；失败抛出 `WebRtcException`。
- 每个浏览器连接由一个独立守护线程处理（accept 线程名 `webrtc-signal-acceptor`）。
- `stop()`（`doStop()`）销毁全部会话 → 停止桌面采集 → 关闭信令 → 释放 `PeerConnectionFactory`。

### 3.2 浏览器端

使用模块内置演示页 `src/main/resources/demo/webrtc-client.html`（默认连接 `ws://127.0.0.1:10000/webrtc`）。其流程：

1. `new WebSocket(...)` 连接信令地址，发送 `{peerId, type: 'offer', payload}`；
2. `navigator.mediaDevices.getDisplayMedia(...)` 采集屏幕并 `addTrack`，同时 `createDataChannel('control', {ordered: true})`；
3. 收到服务端 `answer` → `setRemoteDescription`；收到 `ice-candidate` → `addIceCandidate`；`ontrack` 渲染远端视频。

演示页使用的 ICE 服务器为公共 STUN：`stun:stun.l.google.com:19302`、`stun:stun1.l.google.com:19302`。

### 3.3 监听远端控制事件

```java
server.extensions().controlService().register((sessionId, peerId, event) -> {
    System.out.printf("session=%s peer=%s type=%s%n", sessionId, peerId, event.type());
    return false;   // 观察者语义：当前实现中返回值不阻断后续注入
});
```

### 3.4 会话管理

```java
WebRtcSession session = server.createSession("session-1");  // 幂等：同 ID 复用已有会话
server.session("session-1");        // Optional<WebRtcSession>
server.peers("session-1");          // List<WebRtcPeer>
server.destroySession("session-1"); // 销毁并释放资源
```

---

## 4. 信令协议

传输层为 WebSocket 文本帧，载荷为 JSON：`{"peerId": "...", "type": "...", "payload": "..."}`。
首个帧中的 `peerId` 会被提取为会话 ID 并注册信令连接；未携带时按 `session-0` 处理。

| 方向 | type | payload | 服务端行为 |
| --- | --- | --- | --- |
| 浏览器 → 服务端 | `offer` | SDP offer | 创建会话与 `peer-0`，设置远端描述 → 创建 answer → 添加桌面视频轨道 |
| 浏览器 → 服务端 | `answer` | SDP answer | 设置远端描述，连接状态置 `CONNECTED` |
| 浏览器 → 服务端 | `ice-candidate` | `{"sdpMid","sdpMLineIndex","sdp"}` | 加入 `RTCPeerConnection` |
| 服务端 → 浏览器 | `ok` | `{}` | 信令确认 |
| 服务端 → 浏览器 | `answer` | SDP answer | 协商产物，经信令 Socket 回发 |
| 服务端 → 浏览器 | `ice-candidate` | `{"sdpMid","sdpMLineIndex","sdp"}` | 本端 ICE 候选 |
| 服务端 → 浏览器 | `error` | 提示文本 | 会话停止 / 连接失败 |

类型常量：`WebRtcSignalingRequest.Type`（`offer` / `answer` / `ice-candidate`）、`WebRtcSignalingResponse.Type`（`ok` / `offer` / `answer` / `error`）。

**重连语义**：同一会话再次收到 `offer` 时，旧 peer 会先 `disconnect()` 并移除，再创建新的 `peer-0`，避免固定 peer 覆盖造成泄漏。

**连接注销**：信令断开后（浏览器关标签 / 崩溃），若该会话已无信令连接，则连同 session/peer 一起销毁；最后一个会话结束时自动停止桌面采集。

---

## 5. 会话与连接

### 5.1 WebRtcSession

| 方法 | 说明 |
| --- | --- |
| `getId()` | 会话 ID |
| `addPeer(WebRtcPeer)` / `removePeer(WebRtcPeer)` | 增删连接 |
| `peer(String peerId)` | 按连接 ID 查找 |
| `peers()` | 全部连接（`List<WebRtcPeer>`） |
| `destroy()` | 销毁会话（逐个 `disconnect()`） |

### 5.2 WebRtcPeer

| 方法 | 说明 |
| --- | --- |
| `getId()` / `getSessionId()` | 连接 ID / 会话 ID |
| `getState()` / `isConnected()` | 连接状态与是否已连接 |
| `sendOffer(String)` / `sendAnswer(String)` / `sendCandidate(String)` | SDP / ICE 处理 |
| `sendControl(ControlEvent)` | 经 DataChannel 向浏览器发送控制消息（JSON 文本帧） |
| `remoteTrack()` | 远端视频轨道（`dev.onvoid.webrtc.media.video.VideoTrack`） |
| `addRemoteTrackListener(MediaStreamTrackListener)` | 远端轨道监听 |
| `addControlEventListener(ControlEventListener)` | 控制事件监听 |
| `heartbeat()` | 保持活跃（当前为空实现） |
| `disconnect()` | 关闭 DataChannel 与 PeerConnection |

连接状态 `WebRtcPeer.ConnectionState`：`NONE`（未创建）→ `CALLING`（协商中）→ `CONNECTED`（已连接）→ `FAILED` / `DISCONNECTED`。

### 5.3 线程模型（来自 `WebRtcService` 契约注释）

- 所有 WebRTC 回调均运行在 webrtc-java 内部的 **Native 线程**中；
- 调用方需自行将回调翻译到业务线程（如 Spring `@Async` 或 `ScheduledExecutor`）;
- 屏幕帧投递 / 键鼠事件注入需避免长耗时操作。

---

## 6. 桌面采集

契约接口 `com.chua.webrtc.spi.media.ScreenCaptureService`：

| 方法 | 说明 |
| --- | --- |
| `spiName()` | SPI 名称 |
| `start(VideoTrack)` / `stop(VideoTrack)` | 启停采集 |
| `setCaptureRate(int fps)` | 设置帧率（javadoc 建议 1–30；实现钳制到 1–60） |
| `setResize(int width, int height)` | 设置采集分辨率 |
| `supportWindowMode()` | 是否支持单窗口模式（当前实现返回 `true`） |
| `screens()` | 列出可采集屏幕（`ScreenInfo`：`id/name/width/height`） |
| `videoTrack()` | 当前采集的视频轨道（未开始时为 `null`） |
| `close()` | 释放 Native 资源 |

默认实现 `JavaScreenCaptureService`（`@Spi(value = "webrtc.desktop-capture", order = 10)`、`@SpiDefault`）：

- 基于 webrtc-java `VideoDesktopSource` 的系统级录屏：Windows 走 DXGI Desktop Duplication、Linux 走 X11 Shm / PipeWire、macOS 走 VideoToolbox；
- 默认帧率 15、默认尺寸 1280×720；**未显式 `setResize` 时按真实屏幕像素 1:1 采集**（远控鼠标坐标 = 屏幕坐标，注入端按同一套数值落点）；
- `ScreenCapturer` 懒创建：webrtc-java 的 Native 库由 `PeerConnectionFactory` 静态块加载，提前实例化会抛 `UnsatisfiedLinkError`；
- 采集源 ID 默认值 `0` 不是合法桌面源（Native 端不报错但 0 帧产出），未显式指定时自动回落到「第一个可用桌面源」；源码未提供公开的采集源切换入口；
- `init(PeerConnectionFactory)` 由 `WebRtcServer` 启动时注入，视频轨道名固定为 `videoTrack`。

---

## 7. 远程键鼠控制

### 7.1 控制事件模型 `ControlEvent`

浏览器端把键鼠状态包装为 JSON，经 DataChannel 发到服务端，由 `DefaultControlEventFactory.parse(json)` 反序列化：`type=mouse` → `DefaultMouseEvent`，`type=keyboard` → `DefaultKeyboardEvent`，其余抛 `IllegalArgumentException`。

公共字段：`type`（`mouse` / `keyboard`）、`path`（事件路径，如 `control`）、`timestamp`（毫秒）。

| 常量 | 值 |
| --- | --- |
| `ControlEvent.Type.MOUSE` / `KEYBOARD` | `mouse` / `keyboard` |
| `BUTTON_LEFT` / `BUTTON_MIDDLE` / `BUTTON_RIGHT` | `mouse-left` / `mouse-middle` / `mouse-right` |
| `ACTION_PRESS` / `ACTION_RELEASE` / `ACTION_MOVE` / `ACTION_WHEEL` | `press` / `release` / `move` / `wheel` |
| `ACTION_DOWN` / `ACTION_UP` | `down` / `up` |

- `MouseEvent` 字段：`x`、`y`（屏幕空间坐标）、`button`、`action`、`delta`（滚轮增量）。
- `KeyboardEvent` 字段：`keyCode`（Java 虚拟键码）、`action`、`control`、`alt`、`shift`、`meta`（修饰键）。

鼠标移动事件示例：

```json
{"type":"mouse","path":"control","timestamp":1730000000000,"x":100,"y":200,"button":"mouse-left","action":"move","delta":0}
```

### 7.2 控制服务契约 `ControlService`

| 方法 | 说明 |
| --- | --- |
| `spiName()` | SPI 名称 |
| `register(listener)` / `unregister(listener)` / `listeners()` | 控制事件监听器管理 |
| `onControlEvent(sessionId, peerId, path, event)` | DataChannel 收到事件后调用 |
| `isSupport(sessionId)` | 是否支持控制 |
| `close()` | 释放资源 |

`ControlEventListener` 为单方法接口：`boolean onControlEvent(String sessionId, String peerId, ControlEvent event)`（返回值表示是否已消费；当前实现只做通知，不影响注入流程）。

### 7.3 默认实现 `JavaInputSender`

- `@Spi(value = "webrtc.input-sender", order = 10)`、`@SpiDefault`；SPI 名常量 `SPI_NAME = "webrtc.input-sender"`；控制路径常量 `CONTROL_PATH = "control"`（path 不匹配时直接忽略）；
- 基于 `java.awt.Robot`：`mouseMove` / `mouseWheel` / `mousePress` / `mouseRelease` / `keyPress` / `keyRelease`；
- 按键名映射同时兼容 `mouse-left` 等常量与浏览器常用的 `left` / `middle` / `right` 写法，未知按钮回落左键；
- headless 环境下 `new Robot()` 抛 `AWTException`，构造器转抛 `UnavailableSystemInput`；`WebRtcServer` 捕获后降级为**只读模式**（内部空实现）：画面照常推送，控制事件丢弃，`isSupport` 返回 `false`。

---

## 8. 安全模型

- **信令令牌**：`setSignalingToken(token)` 设置后，握手请求必须携带查询参数 `?token=...`，服务端用 `MessageDigest.isEqual` 做常量时间比较；不匹配返回 `403 Forbidden`。未配置令牌时放行（仅建议在本机或受信网络下使用）。
- **媒体面加密**：WebRTC 媒体面本身是 DTLS/SRTP。
- **信令通道**：为明文 TCP 上的 WebSocket；服务端启动时会拒绝「流加密」配置并提示：需要加密信令请用反向代理终结 TLS。
- **控制通道**：仅接受 `path=control` 的事件；服务端不内置账号 / 权限体系。

---

## 9. 配置项

配置属性类 `com.chua.webrtc.config.WebRtcProperties`，前缀 **`utils.webrtc`**：

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `utils.webrtc.server.protocol` | String | `WS`（`ProtocolType.WS.name()`） | 信令协议类型 |
| `utils.webrtc.server.port` | int | `10000` | 信令端口 |
| `utils.webrtc.server.autoStart` | boolean | `true` | 是否自动启动 |
| `utils.webrtc.server.autoClose` | boolean | `true` | 是否自动关闭 |

**依据与限制**：源码未提供 Spring Boot 自动配置类（`src/main/java/com/chua/webrtc/config` 下仅有 `WebRtcProperties`；`src/main/resources` 中没有 `spring.factories` 或 `AutoConfiguration.imports`），也没有读取该属性并启动 `WebRtcServer` 的装配代码。上述属性需宿主自行注册 `@ConfigurationProperties`（或以其它方式绑定）才会生效。另注意：直接 `new WebRtcServer()` 的默认端口来自 `ServerSetting.defaults()` 的 `8080`，与属性默认值 `10000` 不同；`WebRtcServer` 另有编程式开关 `setAutoClose(boolean)`、`setSignalingToken(String)`。

**遗留产物提示**：当前 `target/classes` 中存在历史构建遗留的 `com.chua.webrtc.config.WebRtcAutoConfiguration`（`javap` 可见 `webRtcServer(WebRtcProperties)` 工厂方法），但 `src` 中已无对应 `.java`，且没有任何自动配置注册文件，Spring Boot 实际不会装配它；执行 `mvn clean` 后该产物即消失。

---

## 10. SPI 扩展点

| SPI 接口 | 名称 | 默认实现 | 注册方式 |
| --- | --- | --- | --- |
| `WebRtcService` | `webrtc` | `WebRtcServer` | `META-INF/services/...WebRtcService` + `@Spi("webrtc")` `@SpiDefault` |
| `ScreenCaptureService` | `webrtc.desktop-capture` | `JavaScreenCaptureService` | `@Spi(value = "webrtc.desktop-capture", order = 10)` `@SpiDefault` |
| `ControlService` | `webrtc.input-sender` | `JavaInputSender` | `@Spi(value = "webrtc.input-sender", order = 10)` `@SpiDefault` |

扩展方式：

- **换采集实现**：实现 `ScreenCaptureService`（如窗口级采集、远程视频源），替换默认桌面录屏；
- **换注入实现**：实现 `ControlService`（如平台级输入事件、自定义注入通道）；
- **事件监听**：实现 `ControlEventListener` 并 `register` 到控制服务；
- **信令模型**：`WebRtcSignalingRequest.of(json)` / `WebRtcSignalingResponse.of(type, payload)` 使用默认 JSON 实现（`DefaultWebRtcSignalingRequest` / `DefaultWebRtcSignalingResponse`），字段为 `type` + `payload`。

`WebRtcService.extensions()` 聚合了采集与控制两个扩展服务：`extensions().screenCaptureService()`、`extensions().controlService()`。

---

## 11. 构建与依赖

模块位置：`utils-support-parent-starter/utils-support-webrtc-parent/utils-support-webrtc-starter`。

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`（4.0.0.51） | `AbstractServer`/`ServerSetting`、`@Spi` 体系、`Json`、`StringUtils` |
| `dev.onvoid.webrtc:webrtc-java` | `0.14.0`（父 pom `dependencyManagement` 统一） | Java 桌面端 WebRTC Native 封装（自带各平台原生库） |
| `org.springframework.boot:spring-boot-starter-websocket` | Boot BOM（4.1.1）托管 | WebSocket 支持 |
| `org.springframework.boot:spring-boot-autoconfigure` | Boot BOM（4.1.1）托管 | 自动配置支持 |
| `org.springframework.boot:spring-boot-configuration-processor` | Boot BOM（4.1.1）托管，`optional` | 配置元数据生成 |
| `org.projectlombok:lombok` | 父 pom `provided` | — |

构建命令（在模块目录或父 pom 目录执行）：

```bash
mvn -o -q compile -DskipTests
```

运行注意：webrtc-java 依赖本地原生库与图形环境；headless 或无显示设备环境下键鼠注入自动降级为只读（见第 7.3 节）。

---

## 12. 边界：这些能力不在本模块

| 能力 | 现状 |
| --- | --- |
| Spring Boot 自动装配 | 源码未提供自动配置类与 `AutoConfiguration.imports` / `spring.factories` 文件 |
| 信令 TLS 加密 | 不内置；信令是明文 TCP WebSocket，需用反向代理终结 TLS |
| STUN / TURN 服务器 | 服务端使用默认 `RTCConfiguration`（未配置 iceServers）；演示页使用公共 Google STUN |
| 音频采集 / 语音通话 | `ScreenCaptureService` 仅提供视频轨道（`VideoTrack`），源码未提供音频采集 |
| 多屏 / 多源切换 | `screens()` 仅列出屏幕；源码未提供公开的采集源切换 setter |
| 多连接级联 | 同一会话仅保留一个 peer（`peer-0`），新 offer 视为重连 |
| 录屏存储 / 回放 | 无 |
| 账号 / 权限体系 | 无（仅提供信令令牌校验） |
| 浏览器端 SDK / 前端封装 | 仅 `demo/webrtc-client.html` 演示页，无 npm / 框架组件 |
