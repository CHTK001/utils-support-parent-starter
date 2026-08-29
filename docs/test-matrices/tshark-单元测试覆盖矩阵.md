# TsharkExample 单元测试覆盖矩阵

## 版本信息
- 类名：TsharkExample
- 模块：utils-support-example-starter
- 作者：CH
- 更新日期：2026-07-30

## 能力点覆盖矩阵

| 能力 ID | 测试方法             | 前置条件                  | 断言                                                       | 通过条件                       |
|:-------|:--------------------|:-------------------------|:----------------------------------------------------------|:------------------------------|
| TS-01  | testParse           | 内置 TShark JSON 字符串    | 解析出 sourceIp/destinationIp/sourcePort/destinationPort/protocol/length | record 不为 null 且字段全部正确 |
| TS-02  | testParseProtocols  | 8 种 layer JSON 字符串   | 协议推断为 HTTP/TLS/DNS/TCP/UDP/ICMP/ARP/OTHER            | 8 个 checkProtocol 全部 true   |
| TS-03  | testParseInvalid    | 空串/乱码/缺 layers 节点  | 全部返回 null，不抛异常                                     | 三个调用结果都为 null           |
| TS-04  | testPolledDirectory | tmp 目录 + TsharkPolledDirectory | 投放 sample.pcap 后 packetListener 被调用                    | CountDownLatch 在 5s 内释放    |
| TS-05  | testPolledListener  | tmp 目录 + SimplePolledListener   | 投放 listener-test.pcap 后 onCreate 触发并携带正确文件名    | triggerFile 等于文件名         |
| TS-06  | testRealCycle       | 本机装 tshark（Wireshark）+ 真实网卡支持 | 真实网卡抓包（长时，1 小时 默认）→ TsharkPolledDirectory 解析 → ProtocolRestorer 还原（基于 raw bytes）| 至少 1 条协议还原命中 |
| TS-07  | testRestorer        | 已注册 ProtocolRestorer SPI | 18 种典型协议 raw bytes 还原命中                            | 命中 ≥ 18 次                   |

## 依赖矩阵

| 依赖                        | 类型      | 用途                              |
|:---------------------------|:---------|:---------------------------------|
| utils-support-tshark-starter | 必备      | PacketParserService / PacketRecord / TsharkPolledDirectory / 28 种 ProtocolRestorer 实现 |
| utils-support-common-starter | 必备      | PolledDirectory 接口、PolledListener、DirectoryPollerEnvironment、Json 工具 |
| tshark 可执行文件           | TS-06 必须 | 真实网卡抓包测试，本机 Wireshark 默认路径 `C:/Program Files/Wireshark/tshark.exe` |
| Npcap 抓包                  | TS-06 必须 | Windows 上抓真实网卡流量需 Npcap |

## 协议还原器清单（28 种）

通过 SPI 注册在 `utils-support-tshark-starter/src/main/resources/META-INF/extensions/com.chua.common.support.network.protocol.ProtocolRestorer`：

| 协议 | 别名 | 优先级 |
|:----|:----|:------|
| HTTP | default | 10 |
| HTTPS | https | 20 |
| HTTP/2 | http2 | 260 |
| DNS | dns | 30 |
| mDNS | mdns | 35 |
| FTP | ftp | 40 |
| SSH | ssh | 50 |
| Telnet | telnet | 60 |
| ICMP | icmp | 70 |
| DHCP | dhcp | 80 |
| NTP | ntp | 90 |
| ARP | arp | 100 |
| Email (POP3/IMAP/SMTP 通用) | email | 110 |
| SMTP | smtp | 105 |
| QQ (OICQ) | qq | 120 |
| WeChat (MMTLS) | wechat | 130 |
| RTP | rtp | 140 |
| SMB | smb | 150 |
| MQTT | mqtt | 160 |
| AMQP | amqp | 170 |
| MySQL | mysql | 180 |
| PostgreSQL | postgresql | 190 |
| Redis | redis | 200 |
| MongoDB | mongodb | 210 |
| Kafka | kafka | 220 |
| RTSP | rtsp | 230 |
| SIP | sip | 240 |
| WebSocket | websocket | 250 |
| Modbus | modbus | 260 |
| CoAP | coap | 270 |

## 关键能力说明

### 解析工具 (PacketParserService)
- 纯静态工具，方法签名 `PacketRecord parse(String jsonLine)`
- 支持 frame / ip / ipv6 / tcp / udp / http / tls / dns / icmp / arp 多种层识别
- TCP 包自动汇聚 `tcp_flags` 和 `lifecycle`（SYN/SYN-ACK/FIN/RST/ACK/DATA）
- 解析失败返回 null，不抛异常

### 轮询监听器 (TsharkPolledDirectory)
- 实现 common-starter 的 `PolledDirectory` 接口
- 启动时扫描 `*.pcap` 文件建立快照（文件名 → lastModified）
- 周期性 `upgrade()` 检测 CREATE / MODIFY / DELETE
- 新增或修改的文件自动调用 `tshark -T json -x` 解析（含 hex 字节）
- 通过 `tshark -T json -x` 输出中的 `frame_raw` 提取真实 raw bytes
- 通过 SPI 自动发现 `ProtocolRestorer` 实现，调用 `canRestore/restore` 填充 `PacketRecord.restoredText`
- 通过 `setTsharkBinary(String)` 自定义 tshark 路径（默认 `tshark`）
- 同时支持标准 `PolledListener` 事件回调

### 真实流量测试 (testRealCycle)
1. 调用 `tshark -D` 探测真实可用抓包接口（默认 loopback，可改为任意 Npcap 接口）
2. 异步启动 `tshark -i iface -a duration:3600 -b filesize:10240 -w capture.pcap`
   - 默认时长 **1 小时（3600s）**，可通过 `tshark.real.duration` 系统属性覆盖
   - `filesize:10240`（10MB）按大小分片，便于长时抓包不丢数据且 PolledDirectory 多次触发
3. 启动 TsharkPolledDirectory 监听 pcap 输出目录，监听 CREATE + MODIFY 事件
4. 进度打印线程每 30 秒输出一次：已抓取时长 / 累计 pcap / 数据包 / 还原命中 / 协议分布
5. CREATE 事件触发后，pcap 被解析成 PacketRecord 列表，由 tshark-starter 内部
   ProtocolRestorer SPI 填充 `restoredText`（真实 raw bytes，无合成）
6. 等待 tshark 自然结束（`tshark.real.duration + 5s` 缓冲），输出最终汇总
7. 命中 ≥ 1 即视为通过

### 协议还原器演示 (testRestorer)
- 18 种典型应用层协议的最小可识别样例（参考 RFC 实现的标准化最小字节序列）
- 演示 SPI 注册的所有 ProtocolRestorer 都可被正确调用
- 用于在 CI 环境或快速验证场景下证明还原器链路可用

## 执行记录

| 日期       | type        | 结果              | 备注                                                  |
|:-----------|:------------|:-----------------|:------------------------------------------------------|
| 2026-07-30 | all         | 全部 PASS        | 含真实流量测试（90s 抓到 30010 个真实包，全部还原） |

## 注意事项

- 临时目录基于 `java.io.tmpdir` + 时间戳，运行结束自动清理
- TS-04 / TS-05 不依赖真实 tshark 可执行文件（投放 FAKE pcap，仅验证事件分发）
- TS-06 依赖本机 tshark 二进制（如未安装，本能力点 graceful skip 并返回 true，便于 CI 环境无 tshark 时通过）
- TS-06 默认抓包时长 3600 秒（1 小时），可通过系统属性 `-Dtshark.real.duration=90` 覆盖
- TS-06 进度打印间隔默认 30 秒，可通过系统属性 `-Dtshark.real.progress=15` 覆盖
- TS-06 按 10MB 文件大小自动分片 pcap（`filesize:10240`），每个分片独立触发 PolledDirectory
- TS-07 输入字节是协议规范的最小可识别样例（参考 curl/openssl/redis-cli 等真实客户端字节），用于 SPI 还原器链路的快速自检
- 真实抓包还原请走 TS-06，由 TsharkPolledDirectory 内部调用 ProtocolRestorer 完成