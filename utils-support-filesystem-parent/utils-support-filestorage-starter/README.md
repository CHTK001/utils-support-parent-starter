# utils-support-filestorage-starter

> 文件存储基础模块：把任意 `FileStorage` 后端挂到 common-starter 的 `Server` 上，通过 `?preview` / `?download` / `?flash` 三类 URL 参数提供文件预览、断点续传下载与一次性「闪图」下载；内置图片滤镜参数管线、37 个格式的预览提供者、PDF 转换缓存与蓝奏云网盘实现。
>
> 版本：`4.0.0.51`｜JDK：`25`（`maven.compiler.source/target=25`）｜包名：`com.chua.filestorage.support`
>
> 模块 pom 自述：文件存储基础模块，提供文件存储服务器过滤器、配置及 SPI 扩展。

---

## 目录

- [1. 它是什么 / 不是什么](#1-它是什么--不是什么)
- [2. 核心 API 与速查表](#2-核心-api-与速查表)
- [3. 快速开始](#3-快速开始)
- [4. 配置项](#4-配置项)
- [5. SPI 扩展点](#5-spi-扩展点)
- [6. 构建与依赖](#6-构建与依赖)
- [7. 边界：这些能力不在本模块](#7-边界这些能力不在本模块)

---

## 1. 它是什么 / 不是什么

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-filestorage-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 文件服务 | 基于 common-starter `ServerFilter` 的预览/下载/闪图 HTTP 过滤器，URL 即 API | 不是独立网关产品，不含管理界面 |
| 存储接入 | 通过 common-starter 的 `FileStorage` SPI 接任意后端；内置蓝奏云实现 | 不是对象存储后端本身，不含 S3/OSS/MinIO 等实现 |
| 预览 | 37 个 `FileStoragePreviewProvider` + 客户端直渲染 + PDF 转换兜底 | 不含 LibreOffice/OnlyOffice 等外部转换进程 |
| 图片处理 | URL 参数解析（`FileOperationSetting`）与全局滤镜链编排 | 不实现图片算法本身（通过 `ImageOperation` SPI 调用） |
| 依赖形态 | 纯 Java + common/image starter，可嵌入任何进程 | 不是 Spring Boot starter，无自动装配、无 application.yml 配置键 |

规模：Java 源文件 62 个（其中预览提供者 37 个）；SPI 注册文件 5 个（`src/main/resources/META-INF/extensions/`）；GraalVM native-image 配置 2 个（`reflect-config.json` / `resource-config.json`）。

### 1.3 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 开启文件预览 | `new FileStorageViewServerFilter(setting)` | 拦截 `?preview`，六层降级管线 |
| 开启下载/闪图 | `new FileStorageDownloadServerFilter(setting)` | 拦截 `?download` / `?flash`，支持 Range 断点续传 |
| 全局配置 | `FileStorageSetting.builder()` | 开关、缓存、水印、SPI 实现选择 |
| 解析图片参数 | `FileStorageFileSetting.parse(request)` | `?size=200x200&format=webp` → `FileOperationSetting` |
| 全局图片滤镜 | `FileStorageFilterSetting.getFilterChain()` | 系统属性 `filestorage.filter.*` 驱动 |
| 新增预览格式 | 实现 `FileStoragePreviewProvider` | `supports()` + `preview()` → `PreviewResult` |
| PDF 转换缓存 | `PreviewPdfCache` | 磁盘 + 内存 LRU + 并发去重 + TTL |
| 一次性下载 | `FlashTokenService` | `createToken` / `validateToken` / `consumeToken` |
| MIME 与能力判断 | `MimeTypeUtils` | `getMimeType`、`isConvertableToPdf`、`isRangeSupported` 等 |
| 客户端渲染判定 | `ClientPreviewSupport` | 魔数嗅探 `rendererOfSniffed`、复合扩展名 `resolveExtension` |
| file-viewer 静态资源 | `FileViewerStaticServerFilter` | Serve `/file-viewer/**`，immutable 缓存 + ETag |
| 蓝奏云网盘 | `LanzouFileStorage`（SPI 名 `lanzou`） | 列表 / 上传 / 下载 / 删除 |

---

## 2. 核心 API 与速查表

### 2.1 URL 约定

路径格式统一为 `/{bucket}/{filepath}`，`bucket` 对应 `addFileStorage(name, storage)` 注册的存储名；查不到时回退 `default`；请求的 bucket 即为 `default` 且也未注册时，回退任意一个已注册存储。

| 请求 | 处理器 | 行为 |
| --- | --- | --- |
| `/{bucket}/{filepath}?preview&size=200x200&format=webp` | `FileStorageViewServerFilter` | 按六层管线返回预览；`openPreview=false` 时 403 |
| `/{bucket}/{filepath}?download&filename=x.png` | `FileStorageDownloadServerFilter` | 附件下载；`openDownload=false` 时 403；命中 `Range` 头且 MIME 支持时返回 206 分片（`openRange=false` 关闭） |
| `/{bucket}/{filepath}?flash=create` | `FileStorageDownloadServerFilter` | 创建闪图令牌：201 + `{"token":"..."}`，响应头 `X-Flash-Token` |
| `/{bucket}/{filepath}?flash=<token>` | `FileStorageDownloadServerFilter` | 一次性下载：校验通过后返回文件并销毁令牌；`openFlash=false` 时 403 |

下载过滤器还支持 `filename` 参数自定义下载文件名；闪图模式下 `cache.flashAutoDeleteOrigin=true` 时消费后连带删除原文件。

### 2.2 预览管线（按优先级依次尝试）

`FileStorageViewServerFilter` 对每个 `?preview` 请求按以下顺序处理（单文件预览读取上限 512MB，超限 413）：

| 层级 | 条件 | 结果 |
| --- | --- | --- |
| 1. 浏览器原生 | MIME 为图片/音频/视频 | 直接返回字节，可先过滤镜；响应头 `X-FileStorage-Preview: native` |
| 2. 客户端直渲染 | 扩展名在 `ClientPreviewSupport` 清单内（docx/xlsx/pptx/pdf 等），且未传 `render=server` | 回吐原始字节；`X-FileStorage-Preview: client` + `X-FileStorage-Client-Renderer: docx/excel/pptx/pdf` |
| 3. SPI 预览提供者 | 某个 `FileStoragePreviewProvider.supports()` 命中 | 返回 HTML 页面；`X-FileStorage-Preview: spi:<类名>`；全部失败则 502 |
| 4. PDF 转换 | 扩展名可转 PDF（doc/xls/ppt/odt 等） | 经 common-starter `ConvertSupport` 转 PDF 后交 PDF.js 提供者；`X-FileStorage-Converted: true` |
| 5. 前端插件 | 扩展名在插件清单（3D/CAD/压缩包等） | 返回提示文本 + `X-FileStorage-Preview-Plugin: true` |
| 6. 不支持 | 以上全部未命中 | 415 |

> 实际命中提示：`doc/xls/ppt` 由 `OldOfficePreviewProvider`、`odt/ods/odp` 由 `OpenDocumentPreviewProvider` 在第 3 层接住并渲染 HTML；第 4 层 PDF 转换只兜底「可转 PDF 但无 SPI 提供者认领」的扩展名。

`?preview=true&render=server` 可强制走服务端链路，用于前端组件渲染失败后的兜底重试。

### 2.3 客户端直渲染（`ClientPreviewSupport`）

「预览渲染通道」的唯一事实来源，utils 侧与 spring 侧过滤器共用：

- 渲染器映射：`docx/docm/dotx/dotm → docx`；`xlsx/xlsm/xlsb/xltx/xltm/xlt/xls → excel`；`pptx/pptm/potx/potm/ppsx/ppsm → pptx`；`pdf → pdf`。
- 魔数嗅探：对 `doc/xls/ppt` 这类传统扩展名按内容判定——命中 OLE2 头（`D0 CF 11 E0 A1 B1 1A E1`）交服务端 POI；命中 ZIP 头则扫描 OOXML 部件路径（`word/document.xml` 等）按真实类型交给客户端。扩展名骗人的「实为 docx 的 .doc」因此不会被误送 POI。
- 复合扩展名（`tar.gz`、`tar.bz2` 等）在 `resolveExtension` 中优先于单段扩展名识别。
- 常用方法：`rendererOf`、`rendererOfSniffed`、`isClientRenderable`、`isClientPreferred(renderParam)`、`resolveExtension`、`clientRenderableExtensions()`、`isOle2/isZip/isPdf/isRtf`。

### 2.4 闪图（`FlashTokenService`）

用 0 字节 marker 文件存令牌（路径 `<flashDir>/<token>`），令牌为无连字符 UUID：

| 方法 | 说明 |
| --- | --- |
| `createToken()` | 生成令牌并落盘 |
| `validateToken(token)` | 文件存在且未超过 `flashExpireSeconds` |
| `consumeToken(token)` | 校验并删除 marker（用后即焚） |
| `cleanExpired()` | 清理全部过期 marker；是否周期调度由调用方决定，本类不含后台线程 |

### 2.5 PDF 转换缓存（`PreviewPdfCache`）

将非原生预览文件（Word/Excel 等）转 PDF 后缓存，避免重复转换：

- 磁盘目录默认 `${java.io.tmpdir}/file-storage-preview-cache`，缓存文件名 `<md5(key)>.pdf`，键规则 `storageName:fileKey:pdf`。
- TTL 自动过期（默认 1 天，0 = 永不过期）、内存 LRU（默认 128 条、单文件 5MB 上限防 OOM）、`CompletableFuture` 并发去重（同一文件并发请求只转一次）、独立转换线程池、命中/未命中/淘汰计数。
- 入口 `getOrConvert(storageName, key, 转换回调)`；用完需 `close()` 释放调度线程。

### 2.6 MIME 工具（`MimeTypeUtils`）

| 方法 | 说明 |
| --- | --- |
| `getMimeType(ext)` / `getMimeTypeFromFilename(name)` | 扩展名 → MIME，未知返回 `application/octet-stream` |
| `isBrowserPreviewSupported(mime)` | 浏览器可原生预览（图片/音视频/PDF/文本类） |
| `isConvertableToPdf(ext)` | 可转 PDF：doc/docx/xls/xlsx/ppt/pptx/odt/ods/odp/txt/md/html/htm |
| `isPluginPreviewSupported(ext)` | 走前端插件：办公族 + glb/gltf/obj/stl/dxf + vsdx/vsd/vdx + zip/rar/7z/tar/gz/tgz/bz2/xz |
| `isRangeSupported(mime)` | 支持 Range 断点续传的 MIME（视频/音频/PDF/图片/zip 等） |

### 2.7 file-viewer 静态资源（`FileViewerStaticServerFilter`）

从 classpath `file-viewer/` 读取前端资源并 Serve 到 `/file-viewer/**`（兼容 `/file-viewer/{version}/...` 版本路径）。缓存策略：`wasm/js/mjs/woff/woff2/ttf/otf/eot` 用 `Cache-Control: public, immutable, max-age=31536000`，其余 24 小时；均带内容 MD5 ETag，命中 `If-None-Match` 返回 304。

---

## 3. 快速开始

依赖：本模块 + `utils-support-common-starter`（随本模块传递）+ 一个 `FileStorage` 实现。下例用 `utils-support-filesystem-starter` 的本地磁盘实现（SPI 名 `local`）。

```java
import com.chua.common.support.network.server.Server;
import com.chua.common.support.network.server.ServerBuilder;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.setting.BucketSetting;
import com.chua.filestorage.support.filter.FileStorageDownloadServerFilter;
import com.chua.filestorage.support.filter.FileStorageViewServerFilter;
import com.chua.filestorage.support.setting.FileStorageSetting;

// 1. 全局配置（全部默认值：预览/下载/闪图开，Range 开）
FileStorageSetting setting = FileStorageSetting.builder().build();

// 2. 存储后端：任意 FileStorage SPI 实现（此处为本地磁盘）
FileStorage storage = FileStorage.createStorage("local", new BucketSetting());

// 3. 两个过滤器共用同一份存储映射
FileStorageViewServerFilter view = new FileStorageViewServerFilter(setting);
view.addFileStorage("default", storage);

FileStorageDownloadServerFilter download = new FileStorageDownloadServerFilter(setting);
download.addFileStorage("default", storage);

// 4. 挂到 common-starter 的 Server 上
Server server = ServerBuilder.create()
        .type("jdk")
        .host("0.0.0.0")
        .port(8080)
        .build();
server.addFilter(view);
server.addFilter(download);
server.start();
```

跑起来之后的典型请求：

```text
GET /default/docs/report.docx?preview              → 客户端直渲染（X-FileStorage-Client-Renderer: docx）
GET /default/imgs/a.png?preview&size=200x200       → 原生返回 + 缩放滤镜（X-FileStorage-Preview: native）
GET /default/docs/old.doc?preview                  → OldOfficePreviewProvider 渲染 POI HTML（X-FileStorage-Preview: spi:OldOfficePreviewProvider）
GET /default/pack/backup.tar.gz?preview            → ArchivePreviewProvider 渲染压缩包
GET /default/imgs/a.png?download                   → 附件下载，支持 Range: bytes=0-1023 → 206
GET /default/imgs/a.png?flash=create               → 201 + {"token":"..."}（X-Flash-Token）
GET /default/imgs/a.png?flash=<token>              → 一次性下载，用后即焚
```

> 过滤器 `getOrder()=80`、`supportPath()="/**"`，会拦截所有路径再按参数放行；不需要预览/下载能力的请求原样穿透过滤链。

---

## 4. 配置项

本模块配置为**纯 POJO + Builder，以代码方式传入**；没有 Spring `@ConfigurationProperties`，也不读取 application.yml。

### 4.1 FileStorageSetting（全局）

| 字段 | 类型 / 默认值 | 说明 |
| --- | --- | --- |
| `openPreview` | boolean / `true` | 预览总开关（`?preview`） |
| `openDownload` | boolean / `true` | 下载总开关（`?download`） |
| `openRange` | boolean / `true` | Range 断点续传开关 |
| `openWebjars` | boolean / `true` | webjars 资源访问开关 |
| `openRemoteFile` | boolean / `false` | 远程文件访问开关 |
| `openFlash` | boolean / `true` | 闪图（一次性预览/下载）开关 |
| `cache` | `FileStorageCacheSetting` | 缓存配置（见 4.2） |
| `watermark` | `FileStorageWatermarkSetting` | 水印配置（见 4.3） |
| `fileSettingKey` | String / 空（取 `jdk`） | `FileStorageFileSetting` SPI 实现名 |
| `filterSettingKey` | String / 空（取 `jdk`） | `FileStorageFilterSetting` SPI 实现名 |
| `imageFilterKey` | String / 空 | `ImageOperation` SPI 实现名 |
| `imageFilterEnabled` | boolean / `false` | 是否启用全局图片滤镜 |

### 4.2 FileStorageCacheSetting（缓存）

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `pdfCacheDir` | `${java.io.tmpdir}/filestorage-pdf-cache` | PDF 转换缓存目录 |
| `flashDir` | `${java.io.tmpdir}/filestorage-flash` | 闪图 marker 文件目录 |
| `ttl` | `86400`（1 天；0 = 永不过期） | 缓存 TTL（秒） |
| `memoryCacheCapacity` | `128`（0 = 不启用内存缓存） | 内存 LRU 容量（条目数） |
| `maxMemoryFileSize` | `5MB` | 单文件内存缓存上限（字节） |
| `flashExpireSeconds` | `600`（10 分钟） | 闪图令牌过期时间（秒） |
| `flashAutoDeleteOrigin` | `false` | 闪图消费后是否删除原文件 |

### 4.3 FileStorageWatermarkSetting（水印）

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否启用水印 |
| `type` | `text` | `text`（文字）或 `image`（图片，源码注释作「镜像」） |
| `text` / `imageUrl` | 空 | 文字内容 / 图片 URL（按 type 取用） |
| `position` | `BOTTOM_RIGHT` | 九宫格位置 + `TILE` 平铺（支持 `LEFT_TOP`/`TOP_CENTER`/…/`RIGHT_BOTTOM` 等别名） |
| `fontSize` / `color` / `fontFamily` | `24` / `rgba(180,180,180,0.3)` / 空 | 文字样式 |
| `opacity` | `0.3` | 透明度 0.0~1.0 |
| `offsetX` / `offsetY` | `20` / `20` | 偏移（像素） |
| `rotation` | `0` | 旋转角度（度） |
| `tileSpacingX` / `tileSpacingY` | `50` / `50` | 平铺间距（像素） |

### 4.4 系统属性（JDK 滤镜实现读取）

`JdkFileStorageFilterSetting` 从系统属性读取全局滤镜链：

```properties
filestorage.filter.chain=resize,grayscale
filestorage.filter.exclude.paths=**/avatar.*,**/logo.*
filestorage.filter.exclude.extensions=svg
```

| 属性 | 默认 | 说明 |
| --- | --- | --- |
| `filestorage.filter.chain` | 空（不处理） | 逗号分隔的滤镜 id 序列，即执行顺序 |
| `filestorage.filter.exclude.paths` | 空 | Ant 风格路径排除（`**` 跨段、`*` 单段） |
| `filestorage.filter.exclude.extensions` | `svg` | 排除的扩展名 |

---

## 5. SPI 扩展点

所有扩展点均为 common-starter 的 `@Spi` 体系：实现类标 `@Spi("名字")`，并在 `META-INF/extensions/<接口全限定名>` 登记。

| SPI 接口 | 本模块内置实现 | 作用 |
| --- | --- | --- |
| `FileStorageFileSetting`（SPI 名 `jdk` → `JdkFileStorageFileSetting`） | ✔ | 解析 `?size=200x200&format=webp&quality=80...` 等 URL 参数为 `FileOperationSetting` |
| `FileStorageFilterSetting`（SPI 名 `jdk` → `JdkFileStorageFilterSetting`） | ✔ | 全局图片滤镜链 + 排除规则 |
| `FileStoragePreviewProvider`（37 个实现，见下） | ✔ | 单文件格式 → HTML 预览 |
| `FileStorage`（common 契约，SPI 名 `lanzou` → `LanzouFileStorage`） | ✔ | 蓝奏云网盘存储 |
| `ServerFilter`（common 契约，注册名 `file_viewer`） | ✔ | `/file-viewer/**` 静态资源；注册文件中的类名写作 `FileViewerStaticFilter`，与源码实际类名 `FileViewerStaticServerFilter` 不一致，以源码为准 |
| `ImageOperation`（common 契约） | ✘ 本模块无实现 | 实际图片处理，由 `utils-support-image-starter` 等按 `imageFilterKey` 提供 |

> Rust/native 实现（`NativeFileStorageFileSetting` / `NativeFileStorageFilterSetting`）已从本模块移除，注册文件中仅保留注释占位；pom 注释明确「Rust native 实现已移除，仅保留 JDK 实现」。

### 5.1 内置预览提供者（37 个，按格式分组）

| 分组 | 实现类 |
| --- | --- |
| 文本/文档 | `TextPreviewProvider`、`MarkdownPreviewProvider`、`CodePreviewProvider`、`HtmlPreviewProvider`、`RtfPreviewProvider`、`EpubPreviewProvider`、`MobiPreviewProvider`、`DjvuPreviewProvider`、`XpsPreviewProvider` |
| 表格/数据 | `CsvPreviewProvider`、`SqlitePreviewProvider`、`ParquetPreviewProvider`、`AvroPreviewProvider`、`ShapefilePreviewProvider` |
| Office | `PdfViewerPreviewProvider`（PDF.js）、`UniverPreviewProvider`（Excel）、`OldOfficePreviewProvider`（.doc/.xls/.ppt）、`OpenDocumentPreviewProvider`（odt/ods/odp）、`IworkPreviewProvider`（iWork） |
| 图像 | `SvgPreviewProvider`、`PsdPreviewProvider`、`TiffPreviewProvider`、`HeicPreviewProvider`、`RawPreviewProvider`（仅 EXIF 与内嵌预览） |
| 3D/CAD | `ThreeModelPreviewProvider`、`FbxPreviewProvider`、`BlenderPreviewProvider`、`CadPreviewProvider` |
| 其他 | `ArchivePreviewProvider`（压缩包）、`VisioPreviewProvider`、`XMindPreviewProvider`、`FontPreviewProvider`、`EmailPreviewProvider`、`CalendarPreviewProvider`、`VCardPreviewProvider`、`JupyterPreviewProvider`、`ClassPreviewProvider`（.class 反编译） |

### 5.2 自定义预览提供者示例

```java
@Spi("preview-myfmt")
public class MyFormatPreviewProvider implements FileStoragePreviewProvider {

    @Override
    public boolean supports(String extension, String mimeType) {
        return "myfmt".equalsIgnoreCase(extension);
    }

    @Override
    public PreviewResult preview(byte[] content, String extension, String mimeType) {
        return PreviewResult.builder()
                .htmlContent("<h2>myfmt 文件</h2><pre>" + new String(content) + "</pre>")
                .build();
    }
}
```

并在 `src/main/resources/META-INF/extensions/com.chua.filestorage.support.preview.FileStoragePreviewProvider` 中登记一行：

```text
com.example.MyFormatPreviewProvider
```

`PreviewResult` 支持 `htmlContent` / `embeddedCss` / `embeddedJs` / `cssUrls` / `jsUrls` / `requiresSandbox`；`requiresSandbox=true` 时外层会用带 `sandbox` 属性与受限 CSP 的 iframe 隔离渲染。

### 5.3 蓝奏云存储配置示例

`LanzouFileStorage` 基于登录态 Cookie 调用蓝奏云接口，键约定为 `<file_id>/<文件名>`：

```java
BucketSetting setting = BucketSetting.builder()
        .extraProperties(Map.of(
                "cookiePath", "/path/to/cookies.txt",   // Netscape 或 playwright JSON
                "uid", "1645999",                        // 可选，缺省从 cookie 的 ylogin 提取
                "rootFolderId", "-1",                    // 可选，默认 -1（全部）
                "uploadUrl", "https://up.lanzou.com/up"  // 可选，上传端点
        ))
        .build();

FileStorage lanzou = FileStorage.createStorage("lanzou", setting);
```

### 5.4 热重载

`AbstractFileStorageServerFilter.upgrade(fileSetting, filterSetting)` 可在运行期替换两个 Setting SPI 实例（传 `null` 保留原值），用于管理界面动态调整图片参数能力与全局滤镜链。

---

## 6. 构建与依赖

```bash
cd D:/ch/project/utils-support-parent-starter
mvn -o -q compile -DskipTests
```

父 POM：`com.chua:utils-support-filesystem-parent:4.0.0.51`；仓库根 `utils-support-parent-starter` 统一 `maven.compiler.source/target=25`、源码编码 UTF-8。

| 依赖 | 版本 | 用途 |
| --- | --- | --- |
| `utils-support-common-starter` | `${project.version}`（4.0.0.51） | `ServerFilter`/`Server`、`FileStorage` 契约、SPI、`ConvertSupport` |
| `utils-support-image-starter` | `${project.version}` | `ImageOperation` 图片处理实现 |
| `commons-compress` + `xz` / `snappy-java` / `zstd-jni`（1.5.7-21） / `brotli-dec`（0.1.2） | 父 POM 管理 | 压缩包预览解压运行时（含 iWork IWA 解压） |
| `sqlite-jdbc` | 父 POM 管理 | SQLite 数据库预览 |
| `poi` / `poi-scratchpad` | `5.5.1` | 老版 Office（.doc/.xls/.ppt）预览 |
| `imageio-psd` | `3.15.2` | PSD 图片预览 |
| `metadata-extractor` | `2.19.0` | 相机 RAW 的 EXIF 与内嵌预览 |
| `duckdb_jdbc` | `1.5.6.0` | Parquet 列存预览（无需 Hadoop） |
| `avro` | 父 POM 管理 | Avro 数据文件预览 |
| `cfr` | 父 POM 管理 | Java .class 反编译预览 |
| `lombok` | `${lombok.version}`，provided | 编译期代码生成 |

---

## 7. 边界：这些能力不在本模块

- **`FileStorage` 契约本身**在 `utils-support-common-starter`（`com.chua.common.support.storage.FileStorage`）；本模块是其使用方与一个实现方（蓝奏云）。
- **本地磁盘存储**：`FileSystemFileStorage`（SPI 名 `local`/`file`/`filesystem`）在 `utils-support-filesystem-starter`。
- **其他对象存储后端**（S3/OSS/COS/MinIO/WebDAV 等）：由各对应 starter 通过 `FileStorage` SPI 提供。
- **图片处理算法**：`ImageOperation` 的 JDK/native 实现在 image 相关模块；本模块只按 `imageFilterKey` 调用。
- **前端 file-viewer 静态资源本体**：不在本模块（本模块的 `FileViewerStaticServerFilter` 只从 classpath `file-viewer/` 读取并 Serve）。
- **文档转换进程**：LibreOffice/OnlyOffice 等外部服务不在本模块；服务端 PDF 转换经 common-starter 的 `ConvertSupport` 完成。
- **Spring Boot 自动装配**与 spring 侧 `com.chua.starter.filesystem.support.server.FileStorageServerFilter`：不在本模块。
- **Rust/native filestorage 实现**：已移除，本模块仅保留 JDK 实现。
