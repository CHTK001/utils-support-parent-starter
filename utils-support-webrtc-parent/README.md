# utils-support-webrtc-parent

WebRTC 远控的 Maven 聚合模块，当前仅含 [utils-support-webrtc-starter](utils-support-webrtc-starter/README.md)。父 POM 不启动信令服务器或采集桌面。

## 安装

版本 `4.0.0.51`，要求 JDK 25：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-webrtc-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

## 能力与使用入口

Java 侧通过 webrtc-java 建立 PeerConnection，使用 WebSocket 交换信令，采集屏幕并通过 DataChannel 处理键鼠控制。会话/连接、采集服务、控制授权和浏览器客户端协议均见 [子模块完整用法](utils-support-webrtc-starter/README.md)。

运行前准备平台原生 WebRTC 库、可访问的桌面及必要的屏幕/输入权限；跨网络连接按实际环境配置 STUN/TURN。媒体通道成功不代表控制权限已授予，两者按子模块的安全模型分别处理。

仓库根构建：`mvn -pl utils-support-webrtc-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
