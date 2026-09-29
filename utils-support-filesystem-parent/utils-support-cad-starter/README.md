# Utils Support CAD Starter

CAD 图纸预览模块。位于 `utils-support-filesystem-parent` 下，作为独立模块存在，
不污染 `filestorage`（文件存储）与 `filesystem`（文件系统）既有职责。

## 为什么单独建模块

| 理由 | 说明 |
|---|---|
| 依赖隔离 | 本模块**零第三方依赖**，只依赖 `filestorage`（为了复用 `FileStoragePreviewProvider` SPI）。不把 CAD 相关的传递依赖带进所有文件预览使用者 |
| 职责清晰 | `filestorage` 管「文件怎么存/怎么预览」，本模块管「CAD 图纸怎么解析」。一个 CAD 库的问题不应该牵连压缩包、Office、PDF 的预览链路 |
| 可选装配 | 基础模块（`common`）不反向依赖它。谁需要 CAD 预览，谁显式引这个 starter |

## 能力边界（务必先读）

| 格式 | 几何渲染 | 说明 |
|---|---|---|
| **DXF** | ✅ 真渲染 | 纯 Java 解析为 SVG，零外部依赖 |
| **DWG** | ❌ 仅头部信息 | Autodesk 私有二进制格式，见下 |
| DGN / STEP / IGES / BREP | — | 仍由 `filestorage` 的 `CadPreviewProvider` 处理，本模块不接管 |

### 为什么 DWG 没有图形

DWG 的图元存放在 object map 与 handle 表中，结构随版本变化，Autodesk 未完整公开。
**Java 生态没有能可靠解析 DWG 图元几何的库**——我实测确认过：

- Maven Central 上 `com.aspose/aspose-cad` **不存在（404）**，只在 Aspose 自家仓库，且是**商业授权**（评估版加水印 + 主动限制文件复杂度）
- `kabeja`（Java DXF 库）**早已停止维护**，且只支持 DXF 不支持 DWG
- 本机无 ODA File Converter / FreeCAD / LibreDWG

真渲染 DWG 只有三条路，都有代价：

1. **LibreDWG**（GPL-3.0）— 免费，但把 GPL 代码打进前端产物会传染
2. **ODA File Converter** — 免费版有水印，商业版收费，且需安装外部 exe
3. **Aspose.CAD** — 保真度最高，商业授权

因此本模块对 DWG 的定位是：**明确告诉用户「这是什么版本的图纸」，并引导另存为 DXF 获得完整预览**。这比展示一张「请用 AutoCAD 打开」的空白卡有实质信息量。

## 已实现内容

### DXF 真渲染

- `DxfParser` — 纯 Java 解析 DXF 的分组码结构，取 `HEADER` 与 `ENTITIES` 两段
  - 支持：`LINE` / `POINT` / `CIRCLE` / `ARC` / `ELLIPSE` / `LWPOLYLINE` / `POLYLINE` / `TEXT` / `MTEXT`
  - 不支持（跳过并记入 `unsupportedEnts()`，由前端如实告知用户）：`INSERT` / `SPLINE` / `3DFACE` / `DIMENSION` / `HATCH` 等
  - 不解析图层属性表（颜色/线宽/线型），所有图元同色渲染
- `DxfSvgRenderer` — 输出内联 SVG
  - 矢量，缩放不失真
  - 描边用 `currentColor`，**koi 暗色模式只需改容器 `color` 即可适配**，SVG 不重渲染
  - DXF 的 Y 轴向上、SVG 向下，用一次 `transform="scale(1,-1)"` 整体翻转，文字单独再翻回来
  - 线宽按图形对角线比例计算，任意缩放下视觉粗细一致
- `DxfPreviewProvider` — SPI 实现，`preview-dxf`

### DWG 头信息解析

- `DwgHeaderParser` — 读前 6 字节版本代码，映射到可读版本
  - 版本表取自 [Autodesk 官方图纸版本代码文档](https://www.autodesk.com/support/technical/article/caas/sfdcarticles/sfdcarticles/drawing-version-codes-for-autocad.html)
  - `AC1015`→AutoCAD 2000/2000i/2002、`AC1018`→2004-2006、`AC1021`→2007-2009、`AC1024`→2010-2012、`AC1027`→2013-2017、`AC1032`→2018-2024
  - 未收录的版本**如实报「已识别代码，未收录名称」**，不编造
  - 非 DWG 内容（如误传的 DXF）返回「未识别的 DWG 版本」而非抛异常——预览失败要能回退到下载按钮
- `DwgPreviewProvider` — SPI 实现，`preview-dwg`。**只接管 DWG**，其余扩展名交回原 `CadPreviewProvider`，避免行为变更面扩大

## 已知限制（如实说明，不假装支持）

- DXF 仅支持 ASCII；二进制 DXF 不解析
- DXF 按 UTF-8 解码；传统 ANSI 编码的中文图层名可能乱码，**几何不受影响**
- 不解析 Xref 外部参照、块定义内容、注释性缩放——这些信息在 DXF/DWG 中本就不完整
- 「完整还原 AutoCAD」做不到：字体缺失回退、布局空间初始视图、代理对象等均无法从单文件还原

## 依赖

```
utils-support-cad-starter
  └─ utils-support-filestorage-starter   （仅为复用 FileStoragePreviewProvider SPI）
       └─ utils-support-common-starter
```

**无第三方依赖。** 不引入任何商业或 GPL 库。

## 已知环境问题（与本模块无关，排查时注意）

- 本地 Maven 仓库 `io/vertx/*/5.2.0/*.jar` 全部截断损坏（缺 ZIP EOCD），`5.0.7` 完好
- 离线模式下 test 作用域依赖不可解析（`byte-buddy-agent`、`spring-test`），故 `mvn install` 会失败，需联网或用 `mvn clean compile` + 手工 `jar` + `install:install-file`
