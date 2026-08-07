# utils-support-gateway-server-starter

远控网关 - 服务端（信令中心）。

承担角色：
- 接收 agent 注册、连接认证
- 接收 controller 连接，路由 controller 到目标 agent
- 转发控制指令（鼠标/键盘/剪贴板等）+ 反向回传音视频流
- HTTP/WS/TCP 多协议端口暴露
