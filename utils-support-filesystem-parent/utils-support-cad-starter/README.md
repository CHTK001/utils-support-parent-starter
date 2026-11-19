# Utils Support CAD Starter

CAD 图纸预览模块：DXF 真渲染（纯 Java 解析转 SVG）与 DWG 头信息解析。

- **坐标**：`com.chua:utils-support-cad-starter:4.0.0.51`，位于 `utils-support-filesystem-parent` 之下
- **JDK**：25（随仓库 `maven.compiler.source/target=25`、`maven-compiler-plugin` `<release>25</release>`）
- **包名**：`com.chua.cad.support`（子包 `dxf` / `dwg` / `provider`）
- **依赖**：仅 `utils-support-filestorage-starter`（为复用其预览 SPI）加编译期 Lombok（`provided`），**零第三方运行时依赖**

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 与速查表](#核心-api-与速查表)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界：这些能力不在本模块](#边界这些能力不在本模块)

---

## 它是什么 / 不是什么

| | 说明 |
|---|---|
| ✅ 是 | **DXF 真渲染**：纯 Java 解析 DXF 文本分组码结构，渲染为内联 SVG，矢量可无损放大 |
| ✅ 是 | **DWG 头信息解析**：读出 AutoCAD 版本、维护版本号、发行号、文件体积，输出信息卡 |
| ✅ 是 | **文件预览 SPI 的两个实现**：`preview-dxf` / `preview-dwg`，接入 filestorage 预览链路 |
| ❌ 不是 | **DWG 图元渲染器**：DWG 为 Autodesk 私有二进制格式，Java 生态无可靠的图元几何解析库 |
| ❌ 不是 | **全格式 CAD 预览**：DGN / STEP / IGES / BREP 仍由 filestorage 侧原有 `CadPreviewProvider` 处理，本模块不接管 |
| ❌ 不是 | **Spring 自动装配模块**：无自动配置类、无配置属性，纯粹靠 SPI 被发现 |

---

## 核心 API 与速查表

| 你想做什么 | 入口 |
|---|---|
| DXF 解析为文档对象 | `DxfParser.create().parse(byte[] content)` → `DxfDocument` |
| DXF 渲染为 SVG | `DxfSvgRenderer.create().render(DxfDocument doc, int width)` → SVG 字符串 |
| DXF 一步预览（SPI） | `DxfPreviewProvider`（`@Spi("preview-dxf")`），`supports` 仅认扩展名 `dxf` |
| DXF 图元类型统计 | `DxfSvgRenderer.create().countByType(DxfDocument doc)` → `Map<String, Integer>` |
| DWG 读头信息 | `DwgHeaderParser.create().parse(byte[] content)` → `DwgHeaderInfo` |
| 判断字节是否形似 DWG | `DwgHeaderParser.create().looksLikeDwg(byte[] content)` |
| DWG 一步预览（SPI） | `DwgPreviewProvider`（`@Spi("preview-dwg")`），`supports` 仅认扩展名 `dwg` |
| 看解析出了什么 | `DxfDocument` 的 `entities()` / `layers()` / `unsupportedEnts()` / `hasValidBounds()` |

### DXF 数据模型（均为 record）

| 类型 | 关键内容 |
|---|---|
| `DxfDocument` | `header`、`entities`（图元，顺序即绘制顺序）、`layers`（图层名 → 图元数）、`minX/minY/maxX/maxY`、`unsupportedEnts`；另有 `width()` / `height()` / `hasValidBounds()` |
| `DxfHeader` | `acadVersion`（`$ACADVER`）、`unitsCode`（`$INSUNITS`）、`unitsLabel`（单位中文名）、`extMinX/extMinY/extMaxX/extMaxY` |
| `DxfEntity` | `type`、`layer`、坐标与几何字段（`startX/startY`、`endX/endY`、`radiusX/radiusY`、`startAngle/endAngle`、`height`、`rotation`、`closed`、`points`、`text`），字段含义随 `type` 变化 |

### 支持与跳过的 DXF 图元

- **可渲染**：`LINE`、`POINT`、`CIRCLE`、`ARC`、`ELLIPSE`、`LWPOLYLINE`、`POLYLINE`、`TEXT`、`MTEXT`
- **跳过并记入 `unsupportedEnts()`**：`INSERT`、`SPLINE`、`3DFACE`、`DIMENSION`、`HATCH` 等（不渲染，但如实上报）
- **不解析**：图层属性表（颜色/线宽/线型），所有图元同色渲染

### DWG 版本识别

内置版本代码表（依据 Autodesk 官方图纸版本代码文档），常用项：

| 版本代码 | 含义 |
|---|---|
| `AC1015` | AutoCAD 2000/2000i/2002 |
| `AC1018` | AutoCAD 2004/2005/2006 |
| `AC1021` | AutoCAD 2007/2008/2009 |
| `AC1024` | AutoCAD 2010/2011/2012 |
| `AC1027` | AutoCAD 2013-2017 |
| `AC1032` | AutoCAD 2018-2024 |

未收录的版本代码**如实报「已识别的 DWG 版本（未收录名称）」**；非 DWG 内容（如误传的 DXF）返回「未识别的 DWG 版本」而**不抛异常**——预览失败要能回退到下载按钮。

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-cad-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. DXF：解析 + 渲染为 SVG（最短可运行示例）

```java
import com.chua.cad.support.dxf.DxfDocument;
import com.chua.cad.support.dxf.DxfParser;
import com.chua.cad.support.dxf.DxfSvgRenderer;

byte[] content = Files.readAllBytes(Path.of("plan.dxf"));

DxfDocument doc = DxfParser.create().parse(content);
String svg = DxfSvgRenderer.create().render(doc, 1200);
// svg 可直接内嵌进 HTML；描边色用 currentColor，容器改 color 即可适配暗色模式
```

`DxfDocument` 还能回答「图里有什么」：

```java
doc.layers();           // 图层名 → 图元数量
doc.unsupportedEnts();  // 被跳过的实体类型，如 [INSERT, HATCH]
doc.hasValidBounds();   // 范围是否可用于视图适配
```

### 3. DWG：读头信息

```java
import com.chua.cad.support.dwg.DwgHeaderInfo;
import com.chua.cad.support.dwg.DwgHeaderParser;

DwgHeaderInfo info = DwgHeaderParser.create().parse(content);
info.versionCode();           // 如 "AC1015"；无法识别时为空串
info.versionLabel();          // 如 "AutoCAD 2000/2000i/2002"
info.maintenanceVersion();    // 第 7 字节的维护版本号；无意义时为 -1
info.fileSize();              // 文件字节数
```

### 4. 走文件预览 SPI（无需手动调用）

引入本模块后，`DxfPreviewProvider` 与 `DwgPreviewProvider` 通过
`META-INF/extensions/com.chua.filestorage.support.preview.FileStoragePreviewProvider`
注册，由 filestorage 侧的预览过滤器自动发现：扩展名为 `dxf` 的文件返回内联 SVG，
扩展名为 `dwg` 的文件返回版本信息卡（并提示可另存为 DXF 获得完整预览）。

---

## 配置项

**无。**

依据：本模块源码中没有任何 `@ConfigurationProperties`、Spring 自动配置类或
`application.yml` / `application.properties`；`src/main/resources` 下仅有
`META-INF/extensions/`（SPI 注册文件）与 `META-INF/native-image/`（GraalVM 元数据）。
模块行为由代码内常量约定：DXF 单文件上限约 64 MB、图元数上限 20 万、SVG 画布基准宽
1200 像素（`DxfPreviewProvider` 调用 `render` 时传入）。

---

## SPI 扩展点

本模块是 filestorage 预览 SPI 的**实现方**，自身不再暴露新的扩展点。

| 项 | 值 |
|---|---|
| SPI 接口 | `com.chua.filestorage.support.preview.FileStoragePreviewProvider`（来自 `utils-support-filestorage-starter`） |
| 接口方法 | `boolean supports(String extension, String mimeType)`、`PreviewResult preview(byte[] content, String extension, String mimeType)` |
| 返回值 | `PreviewResult`（builder 模式，本模块只填 `htmlContent`） |
| 注册方式 | `META-INF/extensions/com.chua.filestorage.support.preview.FileStoragePreviewProvider` 文件中逐行列出实现类全名 |
| 本模块注册的实现 | `com.chua.cad.support.provider.DxfPreviewProvider`、`com.chua.cad.support.provider.DwgPreviewProvider` |
| 发现方 | filestorage 侧预览过滤器自动发现；服务启动后优先尝试 SPI 预览，未命中再回退其他链路 |

若想新增 CAD 格式支持，应实现同一个 `FileStoragePreviewProvider` 接口并在自己的
`META-INF/extensions/` 下注册，而不是修改本模块。

---

## 构建与依赖

```
utils-support-cad-starter
  └─ utils-support-filestorage-starter   （仅为复用 FileStoragePreviewProvider SPI）
       └─ utils-support-common-starter
```

- 父模块：`utils-support-filesystem-parent`（其上为 `utils-support-parent-starter`）
- 编译：JDK 25（仓库 `maven.compiler.source/target=25`、`maven-compiler-plugin` `<release>25</release>`）；Lombok 由父 pom 以 `provided` 引入
- **无第三方运行时依赖**：仅编译期 Lombok（`provided`）；不引入任何商业或 GPL 库（不引 LibreDWG / ODA / Aspose.CAD / kabeja）
- GraalVM 原生镜像：`META-INF/native-image/com.chua/utils-support-cad-starter/` 下已注册
  两个 Provider 的反射配置，并包含 `META-INF/extensions/.*` 资源；父 pom 提供 `native` profile

在仓库根目录构建本模块：

```bash
mvn -o -q compile -DskipTests -pl utils-support-filesystem-parent/utils-support-cad-starter -am
```

---

## 边界：这些能力不在本模块

| 能力 | 现状 |
|---|---|
| DWG 图元几何渲染 | **不做**。DWG 图元存于 object map 与 handle 表，结构随版本变化且 Autodesk 未完整公开；真渲染需 LibreDWG（GPL-3.0）/ ODA File Converter / Aspose.CAD（商业授权）之一 |
| DGN / STEP / IGES / BREP 预览 | 仍由 filestorage 侧原有 `CadPreviewProvider` 处理，本模块只接管 `dxf` 与 `dwg` 两个扩展名 |
| 二进制 DXF | 不解析，仅支持 ASCII DXF |
| 非 UTF-8 编码的 DXF | 按 UTF-8 解码；传统 ANSI 编码的中文图层名可能乱码，几何不受影响 |
| 图层颜色 / 线宽 / 线型 | 不解析图层属性表，所有图元同色渲染 |
| Xref 外部参照、块定义内容、注释性缩放 | 不解析 |
| 「完整还原 AutoCAD」 | 做不到：字体缺失回退、布局空间初始视图、代理对象等无法从单文件还原 |
| 超限防护 | DXF 超过约 64 MB 或图元超过 20 万时拒绝解析/停止收录（防御 OOM 与构造炸弹） |
