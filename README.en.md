# Utils Support Parent Starter\r\n
\r\n
[![Java Version](https://img.shields.io/badge/Java-25%2B-blue)](https://openjdk.java.net/)\r\n
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)\r\n
\r\n
A powerful, highly extensible Java toolkit collection with an SPI-based plugin architecture, providing rich out-of-the-box functionality.\r\n
\r\n
## Table of Contents\r\n
\r\n
- [Introduction](#introduction)\r\n
- [Module Overview](#module-overview)\r\n
- [Quick Start](#quick-start)\r\n
- [Advanced Examples](#advanced-examples)\r\n
- [Installation](#installation)\r\n
- [Documentation](#documentation)\r\n
- [Contributing](#contributing)\r\n
\r\n
---\r\n
\r\n
## Introduction\r\n
\r\n
An enterprise-grade Java toolkit collection with a modular, SPI-driven architecture. All components can be extended and replaced via SPI.\r\n
\r\n
### Key Features\r\n
\r\n
- **Plugin Architecture**: SPI-based hot-plug extensions\r\n
- **Modularity**: Import on demand, minimize conflicts\r\n
- **Unified Interfaces**: ProtocolServer, FileStorage, ChatClient, IdentificationEngine\r\n
- **High Performance**: Built on Netty, Vert.x, Disruptor\r\n
- **Dynamic Plugins**: Runtime load/unload with Spring integration\r\n
\r\n
---\r\n
\r\n
## Module Overview\r\n
\r\n
```\r\n
utils-support-parent-starter/\r\n
|\r\n
|   (all dirs verified against the filesystem layout on 2026-11-04)\r\n
|\r\n
|   utils-support-core-parent/                    # Core (required, 15)\r\n
|   |   common-starter                            # Core utilities\r\n
|   |   crawler-starter                           # Web crawler\r\n
|   |   datasource-starter                        # DataSource engine\r\n
|   |   deeplearning-starter                      # Deep learning embedding\r\n
|   |   filesystem-starter                        # ZIP/TAR/CSV/JSON/XML\r\n
|   |   flow-starter                              # Flow engine\r\n
|   |   flyway-starter                            # DB migration\r\n
|   |   flyway-maven-plugin                       # Flyway Maven plugin\r\n
|   |   maven-starter                             # Maven tooling\r\n
|   |   network-starter                           # Network tools\r\n
|   |   osgi-starter                              # OSGi support\r\n
|   |   payment-starter                           # Payment gateways\r\n
|   |   runtime-support                           # Runtime utilities\r\n
|   |   spring-starter                            # Spring integration\r\n
|   |   springboot-starter                        # Spring Boot auto-config\r\n
|\r\n
|   utils-support-cloud-parent/                   # Cloud & AI providers (28)\r\n
|   |   alibaba-starter / tencent-starter         # Aliyun OSS / Tencent COS / SMS\r\n
|   |   amazon-starter / google-starter           # AWS S3 / Google Cloud\r\n
|   |   huawei-starter / baidu-starter            # OBS / BOS\r\n
|   |   openai-starter / claude-starter           # LLM APIs (ChatClient)\r\n
|   |   zhipu-starter / doubao-starter            # ChatGLM / ByteDance\r\n
|   |   ollama-starter / xunfei-starter           # Local LLM / iFlytek\r\n
|   |   huggingface-starter / modelscope-starter  # HuggingFace / ModelScope\r\n
|   |   hunyuan-starter / zai-starter             # Tencent Hunyuan / Zhihu\r\n
|   |   microsoft-starter / wechat-starter / qq-starter  # WeChat, QQ, Microsoft\r\n
|   |   dingding-starter / feishu-starter         # DingTalk / Feishu\r\n
|   |   bilibili-starter / qiniu-starter          # Bilibili / Qiniu\r\n
|   |   webhook-starter                           # Webhook integration\r\n
|   |   whatsapp-starter                          # WhatsApp\r\n
|   |   cloudflare-starter                        # Cloudflare\r\n
|   |   mosi-starter                              # Mosi\r\n
|   |   ilink-starter / trae-starter              # Internal / trae\r\n
|\r\n
|   utils-support-datasource-parent/              # Databases & datasources (27)\r\n
|   |   mysql-starter / postgresql-starter        # RDBMS: MySQL, PG\r\n
|   |   h2-starter / sqlite-starter               # Embedded: H2, SQLite\r\n
|   |   oracle-starter / sqlserver-starter        # RDBMS: Oracle, SQL Server\r\n
|   |   clickhouse-starter / duckdb-starter       # Columnar: ClickHouse, DuckDB\r\n
|   |   greptimedb-starter / influxdb-starter     # Time series\r\n
|   |   questdb-starter / rocksdb-starter         # Time series / embedded KV\r\n
|   |   elasticsearch-starter                     # Search engine\r\n
|   |   lucene-starter                            # Full-text search\r\n
|   |   solr-starter / tablesaw-starter           # Solr / Table computation\r\n
|   |   parquet-starter / hbase-starter / neo4j-starter # Parquet / HBase / Graph\r\n
|   |   milvus-starter / jvector-starter          # Vector db\r\n
|   |   hibernate-starter                         # ORM\r\n
|   |   calcite-starter / shardingv5-starter      # SQL engine, sharding\r\n
|   |   debezium-starter                          # CDC\r\n
|   |   nitrite-starter / vector-starter          # Embedded KV / Vector\r\n
|\r\n
|   utils-support-datatask-parent/                # Data tasks (8)\r\n
|   |   datasearch-starter                        # Data search\r\n
|   |   datarecovery-starter                      # Data recovery\r\n
|   |   datasync-starter / datasync-agent-starter # Data sync\r\n
|   |   collapse-starter / ueba-starter           # Collapse / user behavior\r\n
|   |   quartz-starter                            # Scheduled tasks\r\n
|   |   retry-starter                             # Retry mechanism\r\n
|\r\n
|   utils-support-datalake-parent/                # Data lake (7)\r\n
|   |   datalake-starter                          # Data lake core\r\n
|   |   datalake-sink-starter                     # Data lake writer (Sink SPI)\r\n
|   |   datalake-subscribe-starter                # Data lake subscriber\r\n
|   |   datalake-query-starter                    # Data lake HTTP query\r\n
|   |   datalake-cdc-starter                      # CDC MySQL change capture\r\n
|   |   datalake-mqtt-starter                     # MQTT 5.0 inbound adapter\r\n
|   |   datalake-webhook-starter                  # HTTP Webhook inbound adapter\r\n
|\r\n
|   utils-support-deeplearning-parent/            # Deep learning (19)\r\n
|   |   deeplearning-onnx-starter                 # ONNX Runtime\r\n
|   |   deeplearning-pytorch-starter              # PyTorch\r\n
|   |   deeplearning-tensorflow-starter           # TensorFlow\r\n
|   |   deeplearning-opencv-starter               # CV\r\n
|   |   deeplearning-arcsoft-starter              # Face detection\r\n
|   |   deeplearning-agentscope-starter           # Multi-agent collaboration\r\n
|   |   deeplearning-langchain4j-starter          # LangChain4j\r\n
|   |   deeplearning-safetensors-starter          # Safetensors model loader\r\n
|   |   deeplearning-3d-starter                   # 3D models\r\n
|   |   deeplearning-dl4j-starter                 # Deeplearning4j\r\n
|   |   deeplearning-gpu-llama3-starter           # GPU LLaMA3\r\n
|   |   deeplearning-laya-starter                 # Lays\r\n
|   |   deeplearning-mage-starter                 # Mage\r\n
|   |   deeplearning-mxnet-starter                # MXNet\r\n
|   |   deeplearning-ollama-starter               # Ollama\r\n
|   |   deeplearning-paddle-starter               # PaddlePaddle\r\n
|   |   deeplearning-sherpa-starter               # Sherpa\r\n
|   |   deeplearning-tinyai-starter               # TinyAI\r\n
|   |   deeplearning-weka-starter                 # Weka\r\n
|\r\n
|   utils-support-extra-parent/                   # Extra components (21)\r\n
|   |   fastjson-starter / gson-starter           # JSON serialization\r\n
|   |   serialize-starter                         # String/object serialization\r\n
|   |   crypto-starter / captcha-starter          # Crypto / Captcha\r\n
|   |   desktop-starter / oshi-starter            # Desktop / system info\r\n
|   |   metrics-starter / tika-starter / fory-starter\r\n
|   |   asm-starter / ast-starter                 # Bytecode / AST analysis\r\n
|   |   date-starter                              # Date utilities\r\n
|   |   enhance-starter                           # Enhancement\r\n
|   |   jdk15on-starter                           # JDK 15+ security\r\n
|   |   mock-starter                              # Mock objects\r\n
|   |   multipart-starter                         # Multipart\r\n
|   |   protobuf-starter                          # Protocol Buffers\r\n
|   |   video-processor-starter                   # Video processing\r\n
|   |   zstd-starter                              # Zstandard compression\r\n
|   |   playwright-starter                        # Browser automation\r\n
|\r\n
|   utils-support-filesystem-parent/              # Files & formats (30)\r\n
|   |   excel-starter / pdf-starter               # Excel, PDF, Word, OFD\r\n
|   |   image-starter / image-apng-starter        # Image processing, APNG\r\n
|   |   ffmpeg-starter / ffmpeg-javacv-starter    # FFmpeg, JavaCV\r\n
|   |   ffmpeg-rust-starter                       # FFmpeg Rust binding\r\n
|   |   email-starter                             # SMTP/IMAP\r\n
|   |   aspose-starter / libreoffice-starter      # Document conversion\r\n
|   |   json-starter                              # JSON processing\r\n
|   |   groovy-starter                            # Groovy scripting\r\n
|   |   pandoc-starter                            # Markdown conversion\r\n
|   |   filestorage-starter                       # File storage (compressed preview)\r\n
|   |   filesearch-starter                        # File search (Rust backend)\r\n
|   |   zxing-starter / geoip2-starter            # QR code, geo IP\r\n
|   |   ip2region-starter / qqwry-starter         # IP address database\r\n
|   |   syslog-starter / html-starter             # Syslog, HTML processing\r\n
|   |   dbf-starter / word-starter / ofd-starter\r\n
|   |   ibd-starter                               # MySQL IBD file parsing\r\n
|   |   cad-starter                               # CAD\r\n
|   |   hprof-starter                             # Hprof profiling\r\n
|   |   onlyoffice-starter                        # OnlyOffice\r\n
|   |   log-starter                               # Log processing\r\n
|   |   wechat-restore-starter                    # WeChat restore\r\n
|\r\n
|   utils-support-middleware-parent/              # Middleware (19)\r\n
|   |   kafka-starter / rabbitmq-starter          # Message queues\r\n
|   |   nacos-starter / zookeeper-starter         # Registry/config\r\n
|   |   redis-starter                             # Redis client\r\n
|   |   prometheus-starter / sentinel-starter     # Monitoring / rate limiting\r\n
|   |   chronicle-starter                         # Chronicle Queue\r\n
|   |   docker-starter / guacamole-starter        # Docker / remote desktop\r\n
|   |   mqtt-starter / minio-starter              # MQTT / MinIO\r\n
|   |   apollo-starter / undertow-starter         # Apollo config / Undertow\r\n
|   |   tomcat-starter / nats-starter             # Tomcat / NATS\r\n
|   |   opentelemetry-starter                     # OpenTelemetry\r\n
|   |   resilience4j-starter                      # Resilience4j\r\n
|   |   skywalking-starter                        # SkyWalking APM\r\n
|\r\n
|   utils-support-network-parent/                 # Network protocols (34)\r\n
|   |   httpclient-starter / okhttp-starter       # HTTP clients\r\n
|   |   armeria-starter / rsocket-starter         # HTTP/2 reactive RPC\r\n
|   |   rpc-dubbo-starter / rpc-sofa-starter / rpc-json-starter / rpc-zmq-starter   # RPC frameworks\r\n
|   |   ssh-starter / ftp-starter / smb-starter   # Remote file access\r\n
|   |   git-starter                               # Git operations\r\n
|   |   socketio-starter                          # Socket.IO\r\n
|   |   jrebel-starter                            # JRebel hot reset\r\n
|   |   webview-starter / webview-jcef-starter    # Embedded WebView\r\n
|   |   acme-starter / ngrok-starter / cloudflared-starter   # TLS cert / tunnel\r\n
|   |   playwright-starter                        # Browser automation\r\n
|   |   tshark-starter / sinch-starter            # Packet capture / SMS\r\n
|   |   kcp-starter                               # KCP protocol\r\n
|   |   winrm-starter / webdav-starter            # WinRM / WebDAV\r\n
|   |   jvppeteer-starter / duckduckgo-starter    # Headless browser / search\r\n
|   |   kimi-starter / kitesurf-starter           # AI client / custom\r\n
|   |   quarkus-starter                           # Quarkus\r\n
|   |   symmetric-starter / cdp-starter           # Encoding / CDP\r\n
|   |   dht-starter / retrofit-starter            # DHT / Retrofit\r\n
|\r\n
|   utils-support-runtime-parent/                 # Runtime enhancements (8)\r\n
|   |   runtime-agent                             # Agent injection\r\n
|   |   runtime-apm                               # APM monitoring\r\n
|   |   runtime-core                              # Runtime core\r\n
|   |   runtime-plugin                            # Plugin runtime\r\n
|   |   runtime-protocol                          # Protocol\r\n
|   |   runtime-shell                             # CLI tool\r\n
|   |   runtime-spy                               # Spy/instrumentation\r\n
|   |   runtime-starter                           # Core starter\r\n
```\r\n
\r\n
---\r\n
\r\n
## Quick Start\r\n
\r\n
### Prerequisites\r\n
\r\n
- JDK 21+\r\n
- Maven 3.6+\r\n
\r\n
### Add Dependency\r\n
\r\n
```xml\r\n
<dependency>\r\n
    <groupId>com.chua</groupId>\r\n
    <artifactId>utils-support-common-starter</artifactId>\r\n
    <version>${latest.version}</version>\r\n
</dependency>\r\n
```\r\n
\r\n
### Basic Usage\r\n
\r\n
```java\r\n
// Type conversion\r\n
Integer num = Converter.convertIfNecessary("123", Integer.class);\r\n
\r\n
// Date handling\r\n
DateTime now = DateTime.now();\r\n
String formatted = now.toString("yyyy-MM-dd HH:mm:ss");\r\n
\r\n
// JSON\r\n
String json = Json.toJson(object);\r\n
MyClass obj = Json.fromJson(json, MyClass.class);\r\n
\r\n
// HTTP request\r\n
HttpResponse response = HttpClient.get()\r\n
    .url("https://api.example.com/data")\r\n
    .newInvoker()\r\n
    .execute();\r\n
\r\n
// SPI extension\r\n
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);\r\n
MyService service = provider.getExtension("default");\r\n
```\r\n
\r\n
---\r\n
\r\n
## Advanced Examples\r\n
\r\n
### 1. Unified Protocol Server — ProtocolServer\r\n
\r\n
Multi-protocol server (HTTP/WebSocket/TCP), switchable between Netty, Vert.x, and Armeria.\r\n
\r\n
```java\r\n
ProtocolServer server = ProtocolServer.create("http://0.0.0.0:8080");\r\n
server.get("/api/users", (request, response) -> {\r\n
    response.setContentType("application/json");\r\n
    response.setBodyString("{\\"users\\": []}");\r\n
});\r\n
server.start();\r\n
```\r\n
\r\n
> Docs: [common-starter](utils-support-core-parent/utils-support-common-starter/README.md)\r\n
\r\n
### 2. Unified OSS Storage — FileStorage\r\n
\r\n
Cross-cloud object storage with a single API. Switch between Alibaba, Tencent, Huawei, AWS, etc.\r\n
\r\n
```java\r\n
FileStorage storage = FileStorage.createStorage("aliyun",\r\n
    FileStorageOption.builder()\r\n
        .accessKeyId("your-key")\r\n
        .accessKeySecret("your-secret")\r\n
        .endpoint("oss-cn-hangzhou.aliyuncs.com")\r\n
        .bucketName("your-bucket")\r\n
        .build());\r\n
\r\n
storage.putObject("path/to/file.jpg", new File("local.jpg"));\r\n
```\r\n
\r\n
> Docs: [cloud-parent](utils-support-cloud-parent/README.md)\r\n
\r\n
### 3. AI Chat — ChatClient\r\n
\r\n
Unified LLM interface supporting OpenAI, Qwen, DeepSeek, ChatGLM, and more.\r\n
\r\n
```java\r\n
ChatClient client = ChatClient.create("openai", "your-api-key");\r\n
String response = client.chatSync("Hello, introduce yourself");\r\n
\r\n
client.chat("Write a poem", chunk -> System.out.print(chunk.getContent()));\r\n
```\r\n
\r\n
> Docs: [cloud](utils-support-cloud-parent/README.md)\r\n
\r\n
### 4. Deep Learning — IdentificationEngine\r\n
\r\n
Unified inference engine for ONNX, PyTorch, TensorFlow, PaddlePaddle. Covers detection, OCR, face recognition.\r\n
\r\n
```java\r\n
IdentificationEngine engine = IdentificationEngine.builder()\r\n
    .engine("onnx")\r\n
    .configuration(DetectionConfiguration.builder()\r\n
        .option("modelPath", "/path/to/yolov8.onnx")\r\n
        .option("optDevice", "GPU")\r\n
        .build())\r\n
    .build();\r\n
\r\n
List<PredictResult> results = engine.predict(new File("image.jpg"));\r\n
```\r\n
\r\n
> Docs: [deeplearning-parent](utils-support-deeplearning-parent/README.md)\r\n
\r\n
### 5. Task Orchestration — Disruptor\r\n
\r\n
Lock-free, high-performance task orchestration using Disruptor.\r\n
\r\n
```java\r\n
DisruptorProvider<TaskEntity> provider = DisruptorProvider\r\n
    .builder(TaskEntity.class)\r\n
    .build();\r\n
\r\n
Arrange<TaskEntity> arrange = provider.newArrange();\r\n
ArrangeGroup<TaskEntity> group = arrange.group();\r\n
group.handleEventsWith(new TaskHandler("task1", 1000));\r\n
group.after("task1").handleEventsWith(\r\n
    new TaskHandler("task2", 500),\r\n
    new TaskHandler("task3", 800)\r\n
);\r\n
group.after("task2", "task3").handleEventsWith(new TaskHandler("task4", 200));\r\n
\r\n
provider.start();\r\n
```\r\n
\r\n
> Docs: [common-starter](utils-support-core-parent/utils-support-common-starter/README.md)\r\n
\r\n
### 6. File Conversion — ConvertFileSystem\r\n
\r\n
Auto-detect document format and convert between formats.\r\n
\r\n
```java\r\n
ConvertFileSystem converter = ConvertFileSystem.autoDetect(\r\n
    new File("document.pdf"), "docx");\r\n
converter.convertTo(new File("document.docx"));\r\n
```\r\n
\r\n
> Docs: [filesystem-parent](utils-support-filesystem-parent/README.md)\r\n
\r\n
### 7. SPI Extension Mechanism\r\n
\r\n
```java\r\n
@Spi\r\n
public interface MyService {\r\n
    String process(String input);\r\n
}\r\n
\r\n
@Spi("default")\r\n
public class DefaultMyService implements MyService {\r\n
    public String process(String input) {\r\n
        return "processed: " + input;\r\n
    }\r\n
}\r\n
\r\n
ServiceProvider<MyService> provider = ServiceProvider.of(MyService.class);\r\n
MyService service = provider.getExtension("default");\r\n
```\r\n
\r\n
### 8. Event Dispatch — DispatcherFlow\r\n
\r\n
Multi-rule event dispatch supporting local, Chronicle Queue persistence, and service discovery.\r\n
\r\n
```java\r\n
// Local dispatch\r\n
DispatcherFlow flow = DispatcherFlow.createLocal();\r\n
flow.register(new UserEventHandler());\r\n
flow.start();\r\n
flow.publish(new UserCreatedEvent("user123", "user"));\r\n
flow.close();\r\n
\r\n
// Chronicle Queue persistent dispatch\r\n
DispatcherFlow chronicleFlow = DispatcherFlow.createChronicle("/tmp/queue");\r\n
chronicleFlow.register(new OrderEventHandler());\r\n
chronicleFlow.start();\r\n
chronicleFlow.publish(new OrderCreatedEvent("order123", 100.0));\r\n
chronicleFlow.close();\r\n
```\r\n
\r\n
---\r\n
\r\n
## Installation\r\n
\r\n
### From Maven Central\r\n
\r\n
```xml\r\n
<dependency>\r\n
    <groupId>com.chua</groupId>\r\n
    <artifactId>utils-support-common-starter</artifactId>\r\n
    <version>${latest.version}</version>\r\n
</dependency>\r\n
```\r\n
\r\n
### Build from Source\r\n
\r\n
```bash\r\n
git clone https://gitee.com/achtk/utils-support-parent-starter.git\r\n
cd utils-support-parent-starter\r\n
mvn clean install -DskipTests\r\n
```\r\n
\r\n
---\r\n
\r\n
## Documentation\r\n
\r\n
| Document | Description |\r\n
|----------|-------------|\r\n
| [Cloud Integration](utils-support-cloud-parent/README.md) | Alibaba, Tencent, Huawei, AWS, LLM APIs |\r\n
| [Datasource Module](utils-support-datasource-parent/README.md) | 27 database/datasource connectors |\r\n
| [File System Module](utils-support-filesystem-parent/README.md) | Documents, images, video, email |\r\n
| [Deep Learning Module](utils-support-deeplearning-parent/README.md) | ONNX, PyTorch, TensorFlow inference |\r\n
| [Common Starter](utils-support-core-parent/utils-support-common-starter/README.md) | Core utilities, SPI framework |\r\n
| [Capability List](CAPABILITY.MD) | Full capability overview |\r\n
| [Changelog](CHANGELOG.md) | Release history |\r\n
\r\n
---\r\n
\r\n
## Contributing\r\n
\r\n
1. Fork the repository\r\n
2. Create a feature branch (`git checkout -b feature/AmazingFeature`)\r\n
3. Commit your changes\r\n
4. Push and open a Pull Request\r\n
\r\n
## License\r\n
\r\n
Apache License 2.0\r\n
