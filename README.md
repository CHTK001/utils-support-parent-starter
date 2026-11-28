# Utils Support Parent Starter

Java 通用工具与 SPI 实现集合。公共契约和基础实现位于 `utils-support-common-starter`，数据库、文件格式、网络协议、云平台和推理引擎由各模块扩展。按所需能力引入叶子模块；父模块的 `packaging` 为 `pom`，用于聚合构建与管理依赖。

当前源码目录包含 **237 个 Maven 模块：本根模块、12 个分组父模块、224 个叶子模块**。下列索引覆盖全部模块，每个链接都指向对应目录的 README，安装、常用调用、配置与实现边界在模块文档中说明。

## 安装与选择

当前根 POM 版本为 `4.0.0.51`，编译目标为 **JDK 25**。例如，只使用基础工具时引入：

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-common-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

使用具体能力时，将 `artifactId` 换为下列叶子模块的坐标，并阅读该模块的前置条件。需要外部服务、浏览器、原生库、模型权重或独立 Python 服务的实现，在各自 README 中单独说明。构建成功不表示这些外部条件已经具备。

- 通用工具、HTTP、SPI、并发、任务、AI 公共门面：[common](utils-support-core-parent/utils-support-common-starter/README.md)。
- ORM、多数据源与查询包装器：[datasource 核心](utils-support-core-parent/utils-support-datasource-starter/README.md)，配合具体数据库模块。
- 文件读写与格式转换：[filesystem 核心](utils-support-core-parent/utils-support-filesystem-starter/README.md)，配合具体格式模块。
- 本地推理与模型注册：[deeplearning 核心](utils-support-core-parent/utils-support-deeplearning-starter/README.md)，配合具体引擎和所需模型文件。
- Spring 环境桥接：[spring](utils-support-core-parent/utils-support-spring-starter/README.md) / [springboot](utils-support-core-parent/utils-support-springboot-starter/README.md)。其它模块是否包含 Spring 适配，以各自 POM 与文档为准。

## 完整模块目录

标记 **未登记** 的 8 个叶子目录有独立 POM，但没有登记在所属父 POM 的 `<modules>` 中，因此根目录默认 reactor 不构建它们。索引收录不改变构建登记。

### 核心工具（16）

父模块：[utils-support-core-parent](utils-support-core-parent/README.md)。

- [utils-support-common-starter](utils-support-core-parent/utils-support-common-starter/README.md)：SPI、工具、HTTP、并发、任务、AI、存储与公共契约。
- [utils-support-crawler-starter](utils-support-core-parent/utils-support-crawler-starter/README.md)：可插拔抓取、解析与抓取数据模型。
- [utils-support-datasource-starter](utils-support-core-parent/utils-support-datasource-starter/README.md)：Engine、多数据源、ORM 包装器与查询执行。
- [utils-support-deeplearning-starter](utils-support-core-parent/utils-support-deeplearning-starter/README.md)：模型注册、下载、翻译器与推理门面。
- [utils-support-filesystem-starter](utils-support-core-parent/utils-support-filesystem-starter/README.md)：文件系统、文本格式读写、归档与搜索。
- [utils-support-flow-starter](utils-support-core-parent/utils-support-flow-starter/README.md)：流程编排、节点注册与 Spring Boot 装配。
- [utils-support-flyway-maven-plugin](utils-support-core-parent/utils-support-flyway-maven-plugin/README.md)：扫描和合并数据库初始化脚本的 Maven 插件。
- [utils-support-flyway-starter](utils-support-core-parent/utils-support-flyway-starter/README.md)：DataSource 脚本迁移、版本和校验和管理。
- [utils-support-maven-starter](utils-support-core-parent/utils-support-maven-starter/README.md)：Maven 同步/异步构建、进度回调与 JDK 选择。
- [utils-support-network-starter](utils-support-core-parent/utils-support-network-starter/README.md)：Vert.x HTTP/WebSocket、Socket 与服务过滤器。
- [utils-support-osgi-bundle-starter](utils-support-core-parent/utils-support-osgi-bundle-starter/README.md)：宿主与插件共享的零依赖 OSGi 契约。
- [utils-support-osgi-starter](utils-support-core-parent/utils-support-osgi-starter/README.md)：Apache Felix 插件容器与 SPI 桥接。
- [utils-support-otp-starter](utils-support-core-parent/utils-support-otp-starter/README.md)：OTP 消息推送与记录查询；**未登记**。
- [utils-support-payment-starter](utils-support-core-parent/utils-support-payment-starter/README.md)：支付渠道、请求、响应与支付场景契约。
- [utils-support-spring-starter](utils-support-core-parent/utils-support-spring-starter/README.md)：Spring 环境、Bean、日期转换与 Web 工具桥接。
- [utils-support-springboot-starter](utils-support-core-parent/utils-support-springboot-starter/README.md)：Spring Boot 自动配置与 API 配置桥接。

### 文件与格式（30）

父模块：[utils-support-filesystem-parent](utils-support-filesystem-parent/README.md)。

- [utils-support-aspose-starter](utils-support-filesystem-parent/utils-support-aspose-starter/README.md)：Aspose 文档格式转换。
- [utils-support-cad-starter](utils-support-filesystem-parent/utils-support-cad-starter/README.md)：DXF 渲染为 SVG、DWG 头信息解析与预览。
- [utils-support-dbf-starter](utils-support-filesystem-parent/utils-support-dbf-starter/README.md)：DBF 文件读写。
- [utils-support-email-starter](utils-support-filesystem-parent/utils-support-email-starter/README.md)：SMTP 邮件发送与邮件解析。
- [utils-support-excel-starter](utils-support-filesystem-parent/utils-support-excel-starter/README.md)：Apache POI Excel 文件系统与导出。
- [utils-support-ffmpeg-javacv-starter](utils-support-filesystem-parent/utils-support-ffmpeg-javacv-starter/README.md)：JavaCV 音视频编解码实现。
- [utils-support-ffmpeg-rust-starter](utils-support-filesystem-parent/utils-support-ffmpeg-rust-starter/README.md)：Rust 原生音视频编解码实现。
- [utils-support-ffmpeg-starter](utils-support-filesystem-parent/utils-support-ffmpeg-starter/README.md)：FFmpeg 音视频处理与转换。
- [utils-support-filesearch-starter](utils-support-filesystem-parent/utils-support-filesearch-starter/README.md)：Rust/FFM 文件索引与搜索。
- [utils-support-filestorage-starter](utils-support-filesystem-parent/utils-support-filestorage-starter/README.md)：文件存储服务器、过滤器与存储扩展。
- [utils-support-geoip2-starter](utils-support-filesystem-parent/utils-support-geoip2-starter/README.md)：MaxMind MMDB IP 地理信息查询。
- [utils-support-groovy-starter](utils-support-filesystem-parent/utils-support-groovy-starter/README.md)：Groovy 脚本、变量绑定与模板。
- [utils-support-hprof-starter](utils-support-filesystem-parent/utils-support-hprof-starter/README.md)：HPROF 分析、JSON/Markdown 导出与 MCP 工具。
- [utils-support-html-starter](utils-support-filesystem-parent/utils-support-html-starter/README.md)：HTML 文件系统与文档转 HTML。
- [utils-support-ibd-starter](utils-support-filesystem-parent/utils-support-ibd-starter/README.md)：Python 数据处理脚本与资源管理。
- [utils-support-image-apng-starter](utils-support-filesystem-parent/utils-support-image-apng-starter/README.md)：APNG 动画帧与格式处理。
- [utils-support-image-starter](utils-support-filesystem-parent/utils-support-image-starter/README.md)：图像转换、缩放、裁剪、滤镜与压缩。
- [utils-support-ip2region-starter](utils-support-filesystem-parent/utils-support-ip2region-starter/README.md)：IP2Region XDB 地理信息查询。
- [utils-support-json-starter](utils-support-filesystem-parent/utils-support-json-starter/README.md)：JSONPath 查询与数据提取。
- [utils-support-libreoffice-starter](utils-support-filesystem-parent/utils-support-libreoffice-starter/README.md)：LibreOffice 文档转换。
- [utils-support-log-starter](utils-support-filesystem-parent/utils-support-log-starter/README.md)：日志文件 tail、grep、过滤与追加。
- [utils-support-ofd-starter](utils-support-filesystem-parent/utils-support-ofd-starter/README.md)：OFD 版式文件操作。
- [utils-support-onlyoffice-starter](utils-support-filesystem-parent/utils-support-onlyoffice-starter/README.md)：OnlyOffice 文档转换。
- [utils-support-pandoc-starter](utils-support-filesystem-parent/utils-support-pandoc-starter/README.md)：Pandoc 文档格式转换。
- [utils-support-pdf-starter](utils-support-filesystem-parent/utils-support-pdf-starter/README.md)：PDF 文件操作与转换。
- [utils-support-qqwry-starter](utils-support-filesystem-parent/utils-support-qqwry-starter/README.md)：纯真 IP 数据库查询；**未登记**。
- [utils-support-syslog-starter](utils-support-filesystem-parent/utils-support-syslog-starter/README.md)：跨平台系统日志查询与监听；**未登记**。
- [utils-support-wechat-restore-starter](utils-support-filesystem-parent/utils-support-wechat-restore-starter/README.md)：本机微信数据还原与 CSV/SQL/Excel 导出。
- [utils-support-word-starter](utils-support-filesystem-parent/utils-support-word-starter/README.md)：Word 文件操作与导出。
- [utils-support-zxing-starter](utils-support-filesystem-parent/utils-support-zxing-starter/README.md)：二维码、条码识别与生成。

### 数据任务与检索（8）

父模块：[utils-support-datatask-parent](utils-support-datatask-parent/README.md)。

- [utils-support-collapse-starter](utils-support-datatask-parent/utils-support-collapse-starter/README.md)：请求合并与虚拟线程批量执行。
- [utils-support-datarecovery-starter](utils-support-datatask-parent/utils-support-datarecovery-starter/README.md)：Rust JNI 数据恢复绑定的依赖入口。
- [utils-support-datasearch-starter](utils-support-datatask-parent/utils-support-datasearch-starter/README.md)：视频/音乐搜索、用量与会话解析、技能/插件/MCP、模型目录，以及天气和地理等查询适配。
- [utils-support-datasync-agent-starter](utils-support-datatask-parent/utils-support-datasync-agent-starter/README.md)：同步 Agent 的 Source/Sink 与传输协议。
- [utils-support-datasync-starter](utils-support-datatask-parent/utils-support-datasync-starter/README.md)：同步映射、调度、执行器与 Agent 管理。
- [utils-support-quartz-starter](utils-support-datatask-parent/utils-support-quartz-starter/README.md)：Quartz 调度实现。
- [utils-support-retry-starter](utils-support-datatask-parent/utils-support-retry-starter/README.md)：重试 SPI 实现。
- [utils-support-ueba-starter](utils-support-datatask-parent/utils-support-ueba-starter/README.md)：异常流量、行为序列分析与语义解释。

### 数据库与查询引擎（28）

父模块：[utils-support-datasource-parent](utils-support-datasource-parent/README.md)。

- [utils-support-calcite-starter](utils-support-datasource-parent/utils-support-calcite-starter/README.md)：Calcite 联邦 SQL 查询。
- [utils-support-clickhouse-starter](utils-support-datasource-parent/utils-support-clickhouse-starter/README.md)：ClickHouse JDBC/R2DBC 适配。
- [utils-support-debezium-starter](utils-support-datasource-parent/utils-support-debezium-starter/README.md)：Debezium CDC 数据变更采集。
- [utils-support-duckdb-starter](utils-support-datasource-parent/utils-support-duckdb-starter/README.md)：DuckDB 嵌入式 OLAP 引擎。
- [utils-support-elasticsearch-starter](utils-support-datasource-parent/utils-support-elasticsearch-starter/README.md)：Elasticsearch 检索与 Engine 适配。
- [utils-support-greptimedb-starter](utils-support-datasource-parent/utils-support-greptimedb-starter/README.md)：GreptimeDB 时序写入、SQL 查询与清理。
- [utils-support-h2-starter](utils-support-datasource-parent/utils-support-h2-starter/README.md)：H2 引擎、元数据与 LIKE 检索门面。
- [utils-support-hbase-starter](utils-support-datasource-parent/utils-support-hbase-starter/README.md)：HBase 引擎与操作封装。
- [utils-support-hibernate-starter](utils-support-datasource-parent/utils-support-hibernate-starter/README.md)：Hibernate DDL 生成适配。
- [utils-support-influxdb-starter](utils-support-datasource-parent/utils-support-influxdb-starter/README.md)：InfluxDB 时序数据操作。
- [utils-support-jvector-starter](utils-support-datasource-parent/utils-support-jvector-starter/README.md)：JVector 嵌入式向量检索。
- [utils-support-lucene-starter](utils-support-datasource-parent/utils-support-lucene-starter/README.md)：Lucene 全文索引与检索。
- [utils-support-milvus-starter](utils-support-datasource-parent/utils-support-milvus-starter/README.md)：Milvus 向量存储与检索。
- [utils-support-mysql-starter](utils-support-datasource-parent/utils-support-mysql-starter/README.md)：MySQL 引擎、元数据、用户/索引管理与向量存储。
- [utils-support-neo4j-starter](utils-support-datasource-parent/utils-support-neo4j-starter/README.md)：Neo4j 图数据库适配。
- [utils-support-nitrite-starter](utils-support-datasource-parent/utils-support-nitrite-starter/README.md)：Nitrite 嵌入式文档数据库。
- [utils-support-oracle-starter](utils-support-datasource-parent/utils-support-oracle-starter/README.md)：Oracle 引擎、元数据、CDC 与 R2DBC。
- [utils-support-parquet-starter](utils-support-datasource-parent/utils-support-parquet-starter/README.md)：Parquet 列式文件读写与查询。
- [utils-support-postgresql-starter](utils-support-datasource-parent/utils-support-postgresql-starter/README.md)：PostgreSQL 引擎、元数据与向量适配。
- [utils-support-questdb-starter](utils-support-datasource-parent/utils-support-questdb-starter/README.md)：QuestDB 时序写入与 HTTP SQL 查询。
- [utils-support-rocksdb-starter](utils-support-datasource-parent/utils-support-rocksdb-starter/README.md)：RocksDB KV、文档与全文检索领域 API。
- [utils-support-shardingv5-starter](utils-support-datasource-parent/utils-support-shardingv5-starter/README.md)：ShardingSphere 分库分表适配。
- [utils-support-solr-starter](utils-support-datasource-parent/utils-support-solr-starter/README.md)：Solr 搜索引擎适配。
- [utils-support-sqlite-starter](utils-support-datasource-parent/utils-support-sqlite-starter/README.md)：SQLite 方言、目录轮询与响应式引擎。
- [utils-support-sqlserver-starter](utils-support-datasource-parent/utils-support-sqlserver-starter/README.md)：SQL Server JDBC/R2DBC 适配。
- [utils-support-tablesaw-starter](utils-support-datasource-parent/utils-support-tablesaw-starter/README.md)：CSV/Tablesaw 内存数据表与 Engine 查询。
- [utils-support-vector-starter](utils-support-datasource-parent/utils-support-vector-starter/README.md)：cuVS GPU 向量检索与 JVector CPU 回退。
- [utils-support-zvec-starter](utils-support-datasource-parent/utils-support-zvec-starter/README.md)：Zvec JNI 嵌入式向量数据库。

### 网络、浏览器与协议（36）

父模块：[utils-support-network-parent](utils-support-network-parent/README.md)。

- [utils-support-acme-starter](utils-support-network-parent/utils-support-acme-starter/README.md)：ACME 证书申请、验证与管理。
- [utils-support-armeria-starter](utils-support-network-parent/utils-support-armeria-starter/README.md)：Armeria HTTP 服务实现。
- [utils-support-cdp-starter](utils-support-network-parent/utils-support-cdp-starter/README.md)：JDK CDP 浏览器抓取与截图适配。
- [utils-support-cloudflared-starter](utils-support-network-parent/utils-support-cloudflared-starter/README.md)：cloudflared Quick Tunnel / 命名隧道进程管理。
- [utils-support-dht-starter](utils-support-network-parent/utils-support-dht-starter/README.md)：BitTorrent DHT 协议与节点处理。
- [utils-support-duckduckgo-starter](utils-support-network-parent/utils-support-duckduckgo-starter/README.md)：Duck.ai 浏览器通道对话。
- [utils-support-ftp-starter](utils-support-network-parent/utils-support-ftp-starter/README.md)：Apache Commons Net FTP 客户端与存储实现。
- [utils-support-git-starter](utils-support-network-parent/utils-support-git-starter/README.md)：JGit 链式操作、异步拉取与仓库监听。
- [utils-support-httpclient-starter](utils-support-network-parent/utils-support-httpclient-starter/README.md)：Apache HttpClient5 HTTP 执行器。
- [utils-support-jrebel-starter](utils-support-network-parent/utils-support-jrebel-starter/README.md)：JRebel 许可证协议服务实现。
- [utils-support-jvppeteer-starter](utils-support-network-parent/utils-support-jvppeteer-starter/README.md)：Jvppeteer 浏览器渲染、截图与网页对话。
- [utils-support-kcp-starter](utils-support-network-parent/utils-support-kcp-starter/README.md)：KCP 可靠 UDP 客户端与服务器。
- [utils-support-kimi-starter](utils-support-network-parent/utils-support-kimi-starter/README.md)：Kimi 网页会话协议适配。
- [utils-support-kitesurf-starter](utils-support-network-parent/utils-support-kitesurf-starter/README.md)：Kitesurf 截图、PDF、HTML 与 MCP 客户端。
- [utils-support-modbus-starter](utils-support-network-parent/utils-support-modbus-starter/README.md)：Modbus TCP/UDP/RTU/ASCII 客户端与服务端。
- [utils-support-ngrok-starter](utils-support-network-parent/utils-support-ngrok-starter/README.md)：Ngrok HTTP/TCP/TLS 隧道。
- [utils-support-okhttp-starter](utils-support-network-parent/utils-support-okhttp-starter/README.md)：OkHttp HTTP 执行器。
- [utils-support-playwright-starter](utils-support-network-parent/utils-support-playwright-starter/README.md)：默认 reactor 的 Playwright 抓取、解析与截图实现。
- [utils-support-quarkus-starter](utils-support-network-parent/utils-support-quarkus-starter/README.md)：基于 Vert.x 的 Quarkus HTTP 服务适配。
- [utils-support-retrofit-starter](utils-support-network-parent/utils-support-retrofit-starter/README.md)：Retrofit HTTP 调用器。
- [utils-support-rpc-dubbo-starter](utils-support-network-parent/utils-support-rpc-dubbo-starter/README.md)：Dubbo RPC 适配。
- [utils-support-rpc-json-starter](utils-support-network-parent/utils-support-rpc-json-starter/README.md)：JSON-RPC 适配。
- [utils-support-rpc-sofa-starter](utils-support-network-parent/utils-support-rpc-sofa-starter/README.md)：SOFA-RPC 适配。
- [utils-support-rpc-zmq-starter](utils-support-network-parent/utils-support-rpc-zmq-starter/README.md)：ZeroMQ RPC 与消息分发。
- [utils-support-rsocket-starter](utils-support-network-parent/utils-support-rsocket-starter/README.md)：RSocket 与消息分发/订阅 SPI。
- [utils-support-sinch-starter](utils-support-network-parent/utils-support-sinch-starter/README.md)：Sinch 语音电话。
- [utils-support-smb-starter](utils-support-network-parent/utils-support-smb-starter/README.md)：SMB 客户端、Rust 服务端与文件存储。
- [utils-support-socketio-starter](utils-support-network-parent/utils-support-socketio-starter/README.md)：Socket.IO 服务、消息分发与配置服务。
- [utils-support-ssh-starter](utils-support-network-parent/utils-support-ssh-starter/README.md)：Apache MINA SSHD 的 SSH/SFTP 能力。
- [utils-support-symmetric-starter](utils-support-network-parent/utils-support-symmetric-starter/README.md)：SymmetricDS 数据库同步。
- [utils-support-tailscale-starter](utils-support-network-parent/utils-support-tailscale-starter/README.md)：Tailscale Funnel 进程管理。
- [utils-support-tshark-starter](utils-support-network-parent/utils-support-tshark-starter/README.md)：TShark 抓包、过滤与协议解析。
- [utils-support-webdav-starter](utils-support-network-parent/utils-support-webdav-starter/README.md)：Sardine WebDAV 文件存储。
- [utils-support-webview-jcef-starter](utils-support-network-parent/utils-support-webview-jcef-starter/README.md)：JCEF WebView 与 IPC。
- [utils-support-webview-starter](utils-support-network-parent/utils-support-webview-starter/README.md)：WebView2/WKWebView/WebKitGTK 原生窗口。
- [utils-support-winrm-starter](utils-support-network-parent/utils-support-winrm-starter/README.md)：WinRM 远程命令执行。

### WebRTC（1）

父模块：[utils-support-webrtc-parent](utils-support-webrtc-parent/README.md)。

- [utils-support-webrtc-starter](utils-support-webrtc-parent/utils-support-webrtc-starter/README.md)：Java 服务端与浏览器客户端的屏幕共享、信令及数据通道控制。

### 深度学习与本地推理（20）

父模块：[utils-support-deeplearning-parent](utils-support-deeplearning-parent/README.md)。

- [utils-support-deeplearning-3d-starter](utils-support-deeplearning-parent/utils-support-deeplearning-3d-starter/README.md)：文本/图片/草图生成 3D 模型的 HTTP 适配。
- [utils-support-deeplearning-agentscope-starter](utils-support-deeplearning-parent/utils-support-deeplearning-agentscope-starter/README.md)：AgentScope Agent、工具、技能与编排桥接。
- [utils-support-deeplearning-dl4j-starter](utils-support-deeplearning-parent/utils-support-deeplearning-dl4j-starter/README.md)：DL4J 迁移学习训练与 ResNet50 推理。
- [utils-support-deeplearning-jitllm-starter](utils-support-deeplearning-parent/utils-support-deeplearning-jitllm-starter/README.md)：GGUF 对话、嵌入与 jitLLM/llama.cpp 实现。
- [utils-support-deeplearning-langchain4j-starter](utils-support-deeplearning-parent/utils-support-deeplearning-langchain4j-starter/README.md)：LangChain4j Agent SPI 桥接。
- [utils-support-deeplearning-laya-starter](utils-support-deeplearning-parent/utils-support-deeplearning-laya-starter/README.md)：本地概率决策与 AI 规则。
- [utils-support-deeplearning-mxnet-starter](utils-support-deeplearning-parent/utils-support-deeplearning-mxnet-starter/README.md)：DJL/MXNet 分类与特征提取。
- [utils-support-deeplearning-ollama-starter](utils-support-deeplearning-parent/utils-support-deeplearning-ollama-starter/README.md)：Ollama 对话、向量、图像、语音与模型运维。
- [utils-support-deeplearning-onnx-starter](utils-support-deeplearning-parent/utils-support-deeplearning-onnx-starter/README.md)：ONNX 视觉、OCR、语音、嵌入与文本推理。
- [utils-support-deeplearning-opencv-starter](utils-support-deeplearning-parent/utils-support-deeplearning-opencv-starter/README.md)：OpenCV 检测、质量评估与图像处理。
- [utils-support-deeplearning-paddle-starter](utils-support-deeplearning-parent/utils-support-deeplearning-paddle-starter/README.md)：DJL/Paddle OCR、人脸、检测与 NLP。
- [utils-support-deeplearning-pytorch-starter](utils-support-deeplearning-parent/utils-support-deeplearning-pytorch-starter/README.md)：DJL/PyTorch 视觉、嵌入、生成与翻译。
- [utils-support-deeplearning-safetensors-starter](utils-support-deeplearning-parent/utils-support-deeplearning-safetensors-starter/README.md)：Python 推理服务与 Java HTTP 客户端。
- [utils-support-deeplearning-sherpa-starter](utils-support-deeplearning-parent/utils-support-deeplearning-sherpa-starter/README.md)：sherpa-onnx 离线/流式 ASR、TTS 与 VAD。
- [utils-support-deeplearning-tensorflow-starter](utils-support-deeplearning-parent/utils-support-deeplearning-tensorflow-starter/README.md)：DJL/TensorFlow 检测、分类与超分辨率。
- [utils-support-deeplearning-weka-starter](utils-support-deeplearning-parent/utils-support-deeplearning-weka-starter/README.md)：Weka 随机森林分类、回归与评估。
- [utils-support-deeplearning-wevisone-starter](utils-support-deeplearning-parent/utils-support-deeplearning-wevisone-starter/README.md)：WeVisOne-VL HTTP 视觉任务客户端。

### 云平台与 AI 服务（29）

父模块：[utils-support-cloud-parent](utils-support-cloud-parent/README.md)。

- [utils-support-alibaba-starter](utils-support-cloud-parent/utils-support-alibaba-starter/README.md)：阿里云存储、短信、证书、支付与 AI 接口。
- [utils-support-amazon-starter](utils-support-cloud-parent/utils-support-amazon-starter/README.md)：S3 存储与 Bedrock 对话/图像生成。
- [utils-support-baidu-starter](utils-support-cloud-parent/utils-support-baidu-starter/README.md)：百度 BOS、短信与文心 AI 接口。
- [utils-support-bilibili-starter](utils-support-cloud-parent/utils-support-bilibili-starter/README.md)：Index-Translate 翻译客户端。
- [utils-support-claude-starter](utils-support-cloud-parent/utils-support-claude-starter/README.md)：Anthropic Claude 对话。
- [utils-support-cloudflare-starter](utils-support-cloud-parent/utils-support-cloudflare-starter/README.md)：Cloudflare D1 与 DNS 管理。
- [utils-support-dingding-starter](utils-support-cloud-parent/utils-support-dingding-starter/README.md)：钉钉 Bot、AI 助理与消息推送。
- [utils-support-doubao-starter](utils-support-cloud-parent/utils-support-doubao-starter/README.md)：火山方舟 Ark 对话。
- [utils-support-feishu-starter](utils-support-cloud-parent/utils-support-feishu-starter/README.md)：飞书 Bot 与事件接收。
- [utils-support-google-starter](utils-support-cloud-parent/utils-support-google-starter/README.md)：Gemini、Imagen、Cloud Storage 与 Colab。
- [utils-support-huawei-starter](utils-support-cloud-parent/utils-support-huawei-starter/README.md)：华为 OBS 与盘古对话。
- [utils-support-huggingface-starter](utils-support-cloud-parent/utils-support-huggingface-starter/README.md)：Hugging Face Hub 仓库与文件管理。
- [utils-support-hunyuan-starter](utils-support-cloud-parent/utils-support-hunyuan-starter/README.md)：混元对话/生图、COS 存储与飞书推送实现。
- [utils-support-ilink-starter](utils-support-cloud-parent/utils-support-ilink-starter/README.md)：微信 iLink 扫码、轮询收信与回复。
- [utils-support-liblib-starter](utils-support-cloud-parent/utils-support-liblib-starter/README.md)：LiblibAI ultra 文生图与任务轮询。
- [utils-support-microsoft-starter](utils-support-cloud-parent/utils-support-microsoft-starter/README.md)：Azure OpenAI 对话与生图。
- [utils-support-modelscope-starter](utils-support-cloud-parent/utils-support-modelscope-starter/README.md)：魔搭对话、生图与 Hub 文件操作。
- [utils-support-mosi-starter](utils-support-cloud-parent/utils-support-mosi-starter/README.md)：MOSS 图像生成与语音合成。
- [utils-support-openai-starter](utils-support-cloud-parent/utils-support-openai-starter/README.md)：OpenAI 兼容对话、工具、嵌入、翻译、图像、视频与音频。
- [utils-support-qiniu-starter](utils-support-cloud-parent/utils-support-qiniu-starter/README.md)：七牛 Kodo 存储；对话实现为提示桩，见模块边界。
- [utils-support-qq-starter](utils-support-cloud-parent/utils-support-qq-starter/README.md)：QQ 开放平台 Bot。
- [utils-support-tencent-starter](utils-support-cloud-parent/utils-support-tencent-starter/README.md)：微信支付渠道与配置。
- [utils-support-tencentcloud-starter](utils-support-cloud-parent/utils-support-tencentcloud-starter/README.md)：腾讯云短信推送与模板查询。
- [utils-support-webhook-starter](utils-support-cloud-parent/utils-support-webhook-starter/README.md)：通用 Webhook 消息推送。
- [utils-support-wechat-starter](utils-support-cloud-parent/utils-support-wechat-starter/README.md)：微信模板/订阅消息、企业机器人与个人微信 Hook。
- [utils-support-whatsapp-starter](utils-support-cloud-parent/utils-support-whatsapp-starter/README.md)：WhatsApp Cloud API Bot。
- [utils-support-xunfei-starter](utils-support-cloud-parent/utils-support-xunfei-starter/README.md)：讯飞星火对话。
- [utils-support-zai-starter](utils-support-cloud-parent/utils-support-zai-starter/README.md)：Z.AI 对话与图像生成。
- [utils-support-zhipu-starter](utils-support-cloud-parent/utils-support-zhipu-starter/README.md)：智谱对话、图像与视频生成。

### 数据湖与入站适配（7）

父模块：[utils-support-datalake-parent](utils-support-datalake-parent/README.md)。

- [utils-support-datalake-cdc-starter](utils-support-datalake-parent/utils-support-datalake-cdc-starter/README.md)：MySQL CDC 事件入站。
- [utils-support-datalake-mqtt-starter](utils-support-datalake-parent/utils-support-datalake-mqtt-starter/README.md)：MQTT 订阅事件入站。
- [utils-support-datalake-query-starter](utils-support-datalake-parent/utils-support-datalake-query-starter/README.md)：数据湖查询实现。
- [utils-support-datalake-sink-starter](utils-support-datalake-parent/utils-support-datalake-sink-starter/README.md)：数据输出端与持久化适配。
- [utils-support-datalake-starter](utils-support-datalake-parent/utils-support-datalake-starter/README.md)：数据湖运行时与公共模型。
- [utils-support-datalake-subscribe-starter](utils-support-datalake-parent/utils-support-datalake-subscribe-starter/README.md)：数据订阅与推送处理。
- [utils-support-datalake-webhook-starter](utils-support-datalake-parent/utils-support-datalake-webhook-starter/README.md)：HTTP Webhook 事件入站。

### 中间件与可观测性（20）

父模块：[utils-support-middleware-parent](utils-support-middleware-parent/README.md)。

- [utils-support-apollo-starter](utils-support-middleware-parent/utils-support-apollo-starter/README.md)：Apollo 配置中心适配。
- [utils-support-chronicle-starter](utils-support-middleware-parent/utils-support-chronicle-starter/README.md)：Chronicle Queue 分发与订阅。
- [utils-support-docker-starter](utils-support-middleware-parent/utils-support-docker-starter/README.md)：docker-java 容器管理客户端。
- [utils-support-guacamole-starter](utils-support-middleware-parent/utils-support-guacamole-starter/README.md)：guacd 远程会话参数与 URL 构造。
- [utils-support-kafka-starter](utils-support-middleware-parent/utils-support-kafka-starter/README.md)：Kafka 分发与订阅。
- [utils-support-minio-starter](utils-support-middleware-parent/utils-support-minio-starter/README.md)：MinIO/S3 文件存储实现。
- [utils-support-mongodb-starter](utils-support-middleware-parent/utils-support-mongodb-starter/README.md)：MongoDB 连接探测、库/集合浏览与只读查询。
- [utils-support-mqtt-starter](utils-support-middleware-parent/utils-support-mqtt-starter/README.md)：Paho MQTT 分发与订阅。
- [utils-support-nacos-starter](utils-support-middleware-parent/utils-support-nacos-starter/README.md)：Nacos 服务发现与配置。
- [utils-support-nats-starter](utils-support-middleware-parent/utils-support-nats-starter/README.md)：NATS、JetStream、KV 与对象存储。
- [utils-support-opentelemetry-starter](utils-support-middleware-parent/utils-support-opentelemetry-starter/README.md)：OpenTelemetry 客户端与引擎适配。
- [utils-support-prometheus-starter](utils-support-middleware-parent/utils-support-prometheus-starter/README.md)：Prometheus 查询与引擎适配。
- [utils-support-rabbitmq-starter](utils-support-middleware-parent/utils-support-rabbitmq-starter/README.md)：RabbitMQ 消息分发。
- [utils-support-redis-starter](utils-support-middleware-parent/utils-support-redis-starter/README.md)：Redis KV、缓存、锁、分发与服务发现。
- [utils-support-resilience4j-starter](utils-support-middleware-parent/utils-support-resilience4j-starter/README.md)：Resilience4j 熔断、限流与重试。
- [utils-support-sentinel-starter](utils-support-middleware-parent/utils-support-sentinel-starter/README.md)：Sentinel 限流。
- [utils-support-skywalking-starter](utils-support-middleware-parent/utils-support-skywalking-starter/README.md)：SkyWalking OAP GraphQL 与 APM 查询。
- [utils-support-tomcat-starter](utils-support-middleware-parent/utils-support-tomcat-starter/README.md)：嵌入式 Tomcat 容器。
- [utils-support-undertow-starter](utils-support-middleware-parent/utils-support-undertow-starter/README.md)：嵌入式 Undertow 容器。
- [utils-support-zookeeper-starter](utils-support-middleware-parent/utils-support-zookeeper-starter/README.md)：ZooKeeper 服务发现。

### 扩展工具（21）

父模块：[utils-support-extra-parent](utils-support-extra-parent/README.md)。

- [utils-support-asm-starter](utils-support-extra-parent/utils-support-asm-starter/README.md)：ASM/Javassist 字节码代理与生成。
- [utils-support-ast-starter](utils-support-extra-parent/utils-support-ast-starter/README.md)：javac AST 编译期注解处理。
- [utils-support-captcha-starter](utils-support-extra-parent/utils-support-captcha-starter/README.md)：图片验证码生成。
- [utils-support-crypto-starter](utils-support-extra-parent/utils-support-crypto-starter/README.md)：密钥策略、加密存储、配置加密与加密狗。
- [utils-support-date-starter](utils-support-extra-parent/utils-support-date-starter/README.md)：自然语言日期时间解析。
- [utils-support-desktop-starter](utils-support-extra-parent/utils-support-desktop-starter/README.md)：跨平台桌面通知。
- [utils-support-enhance-starter](utils-support-extra-parent/utils-support-enhance-starter/README.md)：Emoji 与增强工具实现。
- [utils-support-fastjson-starter](utils-support-extra-parent/utils-support-fastjson-starter/README.md)：Fastjson JSON SPI 实现。
- [utils-support-fory-starter](utils-support-extra-parent/utils-support-fory-starter/README.md)：Apache Fory 序列化。
- [utils-support-gson-starter](utils-support-extra-parent/utils-support-gson-starter/README.md)：Gson JSON SPI 实现。
- [utils-support-jdk15on-starter](utils-support-extra-parent/utils-support-jdk15on-starter/README.md)：BouncyCastle 国密算法实现。
- [utils-support-metrics-starter](utils-support-extra-parent/utils-support-metrics-starter/README.md)：Rust 原生系统指标采集。
- [utils-support-mock-starter](utils-support-extra-parent/utils-support-mock-starter/README.md)：姓名、电话、地址等字符串生成 SPI。
- [utils-support-multipart-starter](utils-support-extra-parent/utils-support-multipart-starter/README.md)：Multipart 请求解析。
- [utils-support-oshi-starter](utils-support-extra-parent/utils-support-oshi-starter/README.md)：OSHI 硬件与系统指标采集。
- [utils-support-playwright-starter](utils-support-extra-parent/utils-support-playwright-starter/README.md)：另一套 CDP/批量浏览器实现；**未登记，同名坐标说明见下文**。
- [utils-support-protobuf-starter](utils-support-extra-parent/utils-support-protobuf-starter/README.md)：Protostuff Protobuf 序列化。
- [utils-support-serialize-starter](utils-support-extra-parent/utils-support-serialize-starter/README.md)：Kryo、JSON、Java 序列化适配。
- [utils-support-tika-starter](utils-support-extra-parent/utils-support-tika-starter/README.md)：Tika 文档文本提取。
- [utils-support-video-processor-starter](utils-support-extra-parent/utils-support-video-processor-starter/README.md)：Rust JNI 视频转 HLS。
- [utils-support-zstd-starter](utils-support-extra-parent/utils-support-zstd-starter/README.md)：Zstd 流压缩 SPI；**未登记**。

`network-parent` 与 `extra-parent` 下的两个 Playwright 目录使用同一个 artifactId `utils-support-playwright-starter`。默认根 reactor 只登记 network 版本。它们的实现和依赖不同，不能把两个目录同时加入同一 reactor，也不要在本地仓把两者当作不同 Maven 坐标同时安装。

### 运行时与插桩（8）

父模块：[utils-support-runtime-parent](utils-support-runtime-parent/README.md)。

- [utils-support-runtime-agent](utils-support-runtime-parent/utils-support-runtime-agent/README.md)：Java Agent 入口与 Instrumentation 注入。
- [utils-support-runtime-apm](utils-support-runtime-parent/utils-support-runtime-apm/README.md)：日志、网络、文件与链路插桩。
- [utils-support-runtime-core](utils-support-runtime-parent/utils-support-runtime-core/README.md)：运行时接口、模型与管理器。
- [utils-support-runtime-plugin](utils-support-runtime-parent/utils-support-runtime-plugin/README.md)：插件契约、扫描与插桩扩展点。
- [utils-support-runtime-protocol](utils-support-runtime-parent/utils-support-runtime-protocol/README.md)：Endpoint、Span、Trace 等共享协议模型。
- [utils-support-runtime-shell](utils-support-runtime-parent/utils-support-runtime-shell/README.md)：Telnet Shell、命令与字符界面。
- [utils-support-runtime-spy](utils-support-runtime-parent/utils-support-runtime-spy/README.md)：ASM 字节码插桩引擎。
- [utils-support-runtime-starter](utils-support-runtime-parent/utils-support-runtime-starter/README.md)：运行时管理入口、软件管理与 Agent 注入。

## 构建

在仓库根目录执行默认 reactor 的离线编译（需要依赖和插件已存在于本地 Maven 仓）：

```bash
mvn -o -q compile -DskipTests
```

只编译一个已登记模块及其 reactor 依赖：

```bash
mvn -o -pl utils-support-network-parent/utils-support-httpclient-starter -am compile -DskipTests
```

父 POM 默认跳过测试；编译结果不能替代运行验收。运行使用预览特性的代码时需使用匹配的 JDK，并按模块说明携带 `--enable-preview`。未登记目录的单独构建方式、外部 SDK/原生库和环境限制，见该目录 README。

## 文档维护

模块 README 是功能说明和使用指南的入口。新增、移动或删除模块时同步更新根索引与所属父模块目录；修改 API 时同步更新示例、导入和配置。文档应描述实际代码行为，尚未实现或未验证的能力明确标注。

模型资源仓库 `utils-support-models-parent` 独立维护，不在本索引范围内。既有文档站与验收报告继续保留。
