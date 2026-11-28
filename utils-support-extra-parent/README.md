# utils-support-extra-parent

字节码、序列化、加密、系统信息与格式识别等扩展的 Maven 聚合模块。版本 `4.0.0.51`，JDK 25；各能力通过具体 jar 安装。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [asm](utils-support-asm-starter/README.md) | ASM/Javassist 代理与字节码操作 |
| [ast](utils-support-ast-starter/README.md) | javac 编译期注解处理；需要配置处理器与 JDK exports |
| [captcha](utils-support-captcha-starter/README.md) | 验证码生成与校验 |
| [crypto](utils-support-crypto-starter/README.md) | 密钥策略/生命周期、数据及配置加密、密钥文件/加密狗 |
| [jdk15on](utils-support-jdk15on-starter/README.md) | BouncyCastle SM2/SM3/SM4 国密 |
| [desktop](utils-support-desktop-starter/README.md) | Windows/macOS/Linux 桌面通知 |
| [enhance](utils-support-enhance-starter/README.md) | Emoji 等增强工具 |
| [date](utils-support-date-starter/README.md) | 自然语言时间解析 |
| [multipart](utils-support-multipart-starter/README.md) | multipart 表单解析 |
| [oshi](utils-support-oshi-starter/README.md) | CPU/内存/磁盘/网络及进程信息 |
| [metrics](utils-support-metrics-starter/README.md) | Rust native 系统指标 |
| [serialize](utils-support-serialize-starter/README.md) | Kryo/JSON/Java 等序列化 SPI |
| [fory](utils-support-fory-starter/README.md) | Apache Fory 序列化 |
| [protobuf](utils-support-protobuf-starter/README.md) | Protostuff 注解驱动编解码 |
| [gson](utils-support-gson-starter/README.md) | Gson JsonProvider 与统一注解适配 |
| [fastjson](utils-support-fastjson-starter/README.md) | Fastjson JsonProvider 与统一注解适配 |
| [mock](utils-support-mock-starter/README.md) | MockString 合成数据工具；不代表真实业务数据 |
| [tika](utils-support-tika-starter/README.md) | 文档文本提取 |
| [video-processor](utils-support-video-processor-starter/README.md) | Rust JNI 视频转 HLS |
| [playwright（extra 目录）](utils-support-playwright-starter/README.md) | CDP/headless_chrome 与批量浏览器调用；未登记到本父 POM |
| [zstd](utils-support-zstd-starter/README.md) | SipStreamCompressor 的 Zstd 扩展；未登记到本父 POM |

## 安装与选择

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-fory-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

JSON/序列化适配通过 common 的统一 SPI 使用，见对应模块示例。AST 是编译期处理器，不能把普通运行依赖当作处理器配置；native/桌面功能需要对应平台运行库或桌面环境。

extra 下的 playwright 与 [network 下的 playwright](../utils-support-network-parent/utils-support-playwright-starter/README.md) 是两个目录、且 POM 使用相同 artifactId；按实际实现选用，不能在同一 reactor 同时登记两者。

仓库根构建：`mvn -pl utils-support-extra-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
