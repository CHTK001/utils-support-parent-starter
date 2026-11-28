# utils-support-filesystem-parent

文档读写/转换、图像音视频、存储服务与文件分析扩展。统一文件读写契约及基础格式见 [核心 filesystem](../utils-support-core-parent/utils-support-filesystem-starter/README.md)。版本 `4.0.0.51`，JDK 25；父 POM 只聚合构建。

## 模块目录

| 模块 README | 主要能力 |
|---|---|
| [email](utils-support-email-starter/README.md) | SMTP/IMAP/POP3 邮件发送与解析 |
| [pdf](utils-support-pdf-starter/README.md) | PDF 读写、合并/拆分与页面处理 |
| [word](utils-support-word-starter/README.md) | Word 文档读写与模板 |
| [excel](utils-support-excel-starter/README.md) | POI Excel 文件读写 |
| [dbf](utils-support-dbf-starter/README.md) | dBASE DBF 文件 |
| [ofd](utils-support-ofd-starter/README.md) | OFD 版式文档 |
| [html](utils-support-html-starter/README.md) | HTML 文件系统与转换 |
| [json](utils-support-json-starter/README.md) | JSONPath 数据提取 |
| [aspose](utils-support-aspose-starter/README.md) | Aspose 文档转换 |
| [libreoffice](utils-support-libreoffice-starter/README.md) | 外部 LibreOffice 转换 |
| [onlyoffice](utils-support-onlyoffice-starter/README.md) | OnlyOffice 服务端转换 |
| [pandoc](utils-support-pandoc-starter/README.md) | 外部 Pandoc 转换 |
| [image](utils-support-image-starter/README.md) | 图像滤镜、裁剪、转换和特效 |
| [image-apng](utils-support-image-apng-starter/README.md) | APNG 帧、延迟与动画编码 |
| [zxing](utils-support-zxing-starter/README.md) | 条码/二维码识别与生成 |
| [ffmpeg](utils-support-ffmpeg-starter/README.md) | FFmpeg 进程式音视频操作 |
| [ffmpeg-javacv](utils-support-ffmpeg-javacv-starter/README.md) | JavaCV FFmpeg 实现 |
| [ffmpeg-rust](utils-support-ffmpeg-rust-starter/README.md) | Rust native 编解码实现 |
| [filesearch](utils-support-filesearch-starter/README.md) | Rust/FFM 文件索引与搜索 |
| [filestorage](utils-support-filestorage-starter/README.md) | 文件服务过滤器、桶、权限与存储扩展 |
| [cad](utils-support-cad-starter/README.md) | DXF 解析/SVG 预览与 DWG 头信息 |
| [geoip2](utils-support-geoip2-starter/README.md) | MaxMind mmdb IP 定位 |
| [ip2region](utils-support-ip2region-starter/README.md) | xdb IP 定位 |
| [groovy](utils-support-groovy-starter/README.md) | Groovy 脚本与模板 |
| [ibd](utils-support-ibd-starter/README.md) | Python 脚本编排与资源处理 |
| [wechat-restore](utils-support-wechat-restore-starter/README.md) | 本机微信数据提取/还原 |
| [log](utils-support-log-starter/README.md) | 日志 tail、grep、时间过滤与追加 |
| [hprof](utils-support-hprof-starter/README.md) | 堆转储 JSON/Markdown 分析及 MCP |
| [qqwry](utils-support-qqwry-starter/README.md) | 纯真 IP 库；未登记到本父 POM |
| [syslog](utils-support-syslog-starter/README.md) | 跨平台系统日志查询与监听；未登记到本父 POM |

## 安装与使用

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-excel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

读写格式、转换格式和外部转换器的覆盖范围不同，按各 README 选择 FileSystem 或 ConvertFileSystem 入口。LibreOffice、OnlyOffice、Pandoc、FFmpeg 等需实际可用的程序或服务；IP 数据库、原生库和商业授权也按对应模块说明准备。

仓库根构建：`mvn -pl utils-support-filesystem-parent -am compile -DskipTests`。返回 [完整目录](../README.md)。
