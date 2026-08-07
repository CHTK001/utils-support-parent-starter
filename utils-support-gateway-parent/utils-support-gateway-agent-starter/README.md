# utils-support-gateway-agent-starter

远控网关 - 被控端。

承担角色：
- 部署在被控机器上
- 向 gateway-server 注册，等待 controller 连接
- 抓屏（GDIGRAB/DXGI）、编码（H264）、回传到 gateway
- 接收 controller 控制指令并执行
