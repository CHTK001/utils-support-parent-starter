# utils-support-excel-starter

> utils 体系的 **Excel 能力模块**：基于 Apache POI 提供 `FileSystem` SPI 的 Excel 实现（读 / 写构建器、样式与列配置），并承接统一导出门面的 xlsx 文档生成（接口一）与 `{{}}` 占位符模板填充（接口二）。
>
> 版本：`4.0.0.51`｜JDK：`25`（继承根 POM `maven.compiler.release=25`，UTF-8，开启预览特性）｜包名：`com.chua.excel.support.*`

---

## 目录

- [1. 模块定位](#1-模块定位)
- [2. 常用能力速查](#2-常用能力速查)
- [3. 快速开始](#3-快速开始)
- [4. 文件系统入口：ExcelFileSystem](#4-文件系统入口excelfilesystem)
- [5. 样式与列配置：CellStyleConfig / ColumnConfig](#5-样式与列配置cellstyleconfig--columnconfig)
- [6. 导出与模板填充（export 包）](#6-导出与模板填充export-包)
- [7. SPI 注册与扩展点](#7-spi-注册与扩展点)
- [8. 配置项](#8-配置项)
- [9. 构建与依赖](#9-构建与依赖)
- [10. 边界：这些能力不在本模块](#10-边界这些能力不在本模块)

---

## 1. 模块定位

### 1.1 Maven 坐标

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-excel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

模块 POM 自述：*Excel 文件系统 SPI 实现（基于 Apache POI）*。父模块为 `utils-support-filesystem-parent`，根 POM 为 `utils-support-parent-starter`（均为 `4.0.0.51`）。

### 1.2 它是什么 / 不是什么

| 维度 | 是 | 不是 |
| --- | --- | --- |
| 文件系统 | `FileSystem` SPI 的 `excel` 实现：`.xlsx` / `.xls` 读取、`.xlsx` 写入，链式构建器 | 不是通用 Office 套件（不做 Word / PDF / PPT，见同仓对应 starter） |
| 导出 | 统一导出门面在 xlsx 格式上的 `DocumentGenerator`（注解驱动、从零生成工作簿） | 不定义导出契约本身（`Documents`、`ExportColumn` 等在 `utils-support-common-starter`） |
| 模板 | `TemplateFiller` 的 POI 实现：`{{key}}`、循环块、元信息占位符 | 不依赖第三方模板库（占位符解析复用核心的 `Placeholders`） |
| 装配 | 纯 SPI 扩展模块，引入即注册 | 不是 Spring 自动装配模块（无自动配置类、无配置属性） |

### 1.3 规模与构成

- Java 源文件 **6** 个，分布在 **3** 个包：

| 包 | 内容 |
| --- | --- |
| `com.chua.excel.support.file.impl` | `ExcelFileSystem`（含内部类 `ExcelReadBuilder`、`ExcelWriteBuilder`） |
| `com.chua.excel.support.file.config` | `ColumnConfig`、`CellStyleConfig` |
| `com.chua.excel.support.export` | `PoiDocumentGenerator`、`PoiTemplateFiller`、`PoiStyles`（包私有） |

- SPI 注册文件 **4** 个：`META-INF/services/com.chua.common.support.file.FileSystem`（JDK ServiceLoader）+ `META-INF/extensions/` 下 `FileSystem`、`DocumentGenerator`、`TemplateFiller` 三个扩展声明。
- GraalVM `native-image` 配置：`reflect-config.json` / `proxy-config.json` / `resource-config.json`。
- 本模块未包含测试目录（`src/test` 不存在）。

---

## 2. 常用能力速查

| 需求 | 入口 | 说明 |
| --- | --- | --- |
| 拿到 Excel 文件系统 | `FileSystem.create("excel")` | SPI 名称 `excel`；`FileSystem.auto(file)` 对 `.xls`/`.xlsx` 也会路由到它 |
| 读取全表 | `fs.read(file)` → `ExcelReadBuilder.rows()` | 首行为表头，返回 `List<Map<String, Object>>` |
| 只读部分列 / 过滤行 | `.selectColumns("id","name")`、`.filter(row -> ...)` | 列投影 + 行过滤，链式 |
| 指定工作表 | `.withSheetName("明细")` / `.withSheetIndex(1)`；`sheetNames()` 列出全部 | 按名优先，找不到抛 `IllegalArgumentException` |
| 写出表格 | `fs.write(file).withHeaders(...).write(rows).finish()` | 支持 `Map` / `List<Map>` / POJO 行 |
| 样式、冻结、筛选 | `.headerStyle(...)`、`.style(...)`、`.freezeHeader()`、`.withFilter()` | 见 `ExcelWriteBuilder` |
| 列级配置 | `.columnConfig("score", ColumnConfig.of("score").width(10))` | 显示名、列宽、隐藏、独立样式 |
| 注解驱动导出 xlsx | `Documents.of(rows).sheet("用户").output(file).export()` | 由本模块 `PoiDocumentGenerator` 承接 |
| 模板填充 xlsx | `Documents.ofTemplate(path).bind("rows", list).output(out).export()` | 由本模块 `PoiTemplateFiller` 承接 |

---

## 3. 快速开始

### 3.1 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-excel-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 3.2 最短读写示例

`FileSystem.read()/write()` 声明返回的是基类 `ReadBuilder` / `WriteBuilder`，使用 Excel 特有方法时转型为内部构建器类型（也可直接 `new ExcelFileSystem()`）：

```java
import com.chua.excel.support.file.impl.ExcelFileSystem;

ExcelFileSystem fs = new ExcelFileSystem();

// ===== 写入：表头 + 数据 + 冻结表头 + 自动筛选 =====
ExcelFileSystem.ExcelWriteBuilder w =
        (ExcelFileSystem.ExcelWriteBuilder) fs.write(new File("report.xlsx"));
w.withHeaders(List.of("id", "name", "score"))
 .freezeHeader()
 .withFilter()
 .write(rows)                 // List<Map<String, Object>>，也支持 POJO 列表
 .finish();

// ===== 读取：列投影 + 行过滤 =====
ExcelFileSystem.ExcelReadBuilder r =
        (ExcelFileSystem.ExcelReadBuilder) fs.read(new File("report.xlsx"));
List<Map<String, Object>> result = r.selectColumns("id", "name")
        .filter(row -> row.get("score") != null)
        .rows();
```

只使用通用契约时的最简写法（无需转型）：

```java
FileSystem fs = FileSystem.create("excel");
fs.write(new File("out.xlsx"))
  .withHeaders(List.of("id", "name"))
  .write(rows)
  .finish();
```

### 3.3 样式 + 多 Sheet 写入

```java
CellStyleConfig headerStyle = CellStyleConfig.create()
        .bold().fontSize(12).backgroundColor("4472C4").fontColor("FFFFFF")
        .horizontalCenter().verticalCenter().border(BorderStyle.THIN);

CellStyleConfig bodyStyle = CellStyleConfig.create()
        .border(BorderStyle.THIN).horizontalCenter();

ExcelFileSystem.ExcelWriteBuilder w =
        (ExcelFileSystem.ExcelWriteBuilder) FileSystem.create("excel").write(new File("multi.xlsx"));

w.writeSheet("汇总", summaryRows)          // Sheet 1：直接带数据
 .freezeHeader().withFilter()

 .writeSheet("明细")                        // Sheet 2：切换后再配置
 .withHeaders(List.of("id", "name"))
 .headerStyle(headerStyle)
 .style(bodyStyle)
 .columnConfig("score", ColumnConfig.of("score").width(10))
 .write(detailRows)
 .finish();
```

### 3.4 统一导出门面（xlsx 由本模块承接）

```java
import com.chua.common.support.export.Documents;

// 接口一：List → 文档（注解解析 SPI 驱动，无需模板）
Documents.of(list)
         .sheet("用户")
         .output(new File("user.xlsx"))
         .export();

// 接口二：模板替换（List + 标量 → 模板 → 文档）
Documents.ofTemplate(Path.of("templates/user-report.xlsx"))
         .put("title", "2024 年度报表")
         .bind("users", list)              // 循环块 {{#users}}
         .output(Path.of("user-report.xlsx"))
         .export();
```

也可以绕过门面，直接按 SPI 名称取实现：

```java
DocumentGenerator gen = ServiceProvider.of(DocumentGenerator.class).getExtension("xlsx");
TemplateFiller filler = ServiceProvider.of(TemplateFiller.class).getExtension("poi");
```

---

## 4. 文件系统入口：ExcelFileSystem

`com.chua.excel.support.file.impl.ExcelFileSystem`，注解 `@Spi("excel")`，实现 `com.chua.common.support.file.FileSystem`。

| 方法 | 说明 |
| --- | --- |
| `getType()` | 返回 `"excel"` |
| `read(File)` | 返回 `ExcelReadBuilder` |
| `write(File)` | 返回 `ExcelWriteBuilder` |

### 4.1 读取：ExcelReadBuilder

| 方法 | 说明 |
| --- | --- |
| `rows()` | 读取为 `List<Map<String, Object>>`；首行为表头；结束后统一应用 `filter` 与 `mapRows` |
| `withSheetName(String)` | 按名称选工作表（优先于索引）；找不到抛 `IllegalArgumentException("Sheet not found: ...")` |
| `withSheetIndex(int)` | 按下标选工作表，默认 `0`；越界抛 `IllegalArgumentException("Sheet index not found: ...")` |
| `selectColumns(String...)` / `selectColumns(Collection<String>)` | 列投影，仅读取指定列（保持文件中的列顺序） |
| `filter(Predicate<Map<String, Object>>)` | 行过滤（覆写自 `ReadBuilder`，返回本类型） |
| `sheetNames()` | 列出全部工作表名；打开失败时返回空列表 |
| `read()` | 等价于 `rows()` |
| `withCharset(String)` | 覆写自基类（Excel 为二进制格式，实际读取不依赖该编码） |

继承自 `ReadBuilder` 也可用：`columnMapping(Map)`（列名映射，读取时自动改名）、`mapRows(Function)`（行转换）、`withCallback(ReadCallback)`（回调 `onHeader` / `onBody` / `onComplete`）、`readAsync(callback)`。

**单元格取值规则**：`STRING`→字符串；`NUMERIC`→日期格式则 `Date`，否则 `double`；`BOOLEAN`→布尔；`FORMULA`→公式文本；`BLANK`→`null`。合并单元格区域内的所有坐标在结果中都取区域左上角的值。

**失败行为**：打开/解析异常时包装为 `RuntimeException("读取 Excel 失败: " + file, e)`，并先回调 `onComplete(0)`。

### 4.2 写入：ExcelWriteBuilder

构造时默认创建名为 `Sheet1` 的活跃工作表。写入最终通过 `finish()`（或 `writeAndFlush`）一次性产出**新的 `.xlsx` 工作簿**（内部固定使用 POI `XSSFWorkbook`）。

| 方法 | 说明 |
| --- | --- |
| `writeSheet(String name)` | 切换/创建工作表，后续配置与数据写入该表 |
| `writeSheet(String name, List<Map<String, Object>> rows)` | 切换工作表并追加数据 |
| `sheetName(String)` | 同 `writeSheet(String)` |
| `write(Object data)` | 追加数据：`Map` 单行、`List`（元素为 `Map` 或 POJO）、单个 POJO（经 `BeanUtils.objectToMap` 转换） |
| `writeRows(List<Map<String, Object>>)` | 追加多行 |
| `withHeaders(List<String>)` | 固定表头列及顺序（不设则从首行数据的键推断） |
| `withHeader(boolean)` | 是否写表头行 |
| `style(CellStyleConfig)` | 当前表数据单元格默认样式 |
| `headerStyle(CellStyleConfig)` | 当前表表头行样式 |
| `columnConfig(String, ColumnConfig)` | 设置某列配置（显示名 / 宽度 / 样式 / 隐藏） |
| `columnConfigs(Map<String, ColumnConfig>)` | 批量设置列配置 |
| `columnWidth(String, int)` | 快捷设置列宽（字符数；保留该列已有样式/显示名配置） |
| `defaultColumnWidth(int)` | 当前表默认列宽 |
| `freezeHeader()` | 冻结表头行（`createFreezePane(0, 表头行+1)`） |
| `withFilter()` | 启用自动筛选（覆盖表头行范围） |
| `newWorkbook(boolean)` | 开关保留；当前实现始终新建 `XSSFWorkbook`，即总是产出新的 `.xlsx` 文件 |
| `writeAndFlush(List<Map<String, Object>>)` | 不走 `finish()`，立即把当前活跃表写入文件 |
| `finish()` | 写出全部工作表；无数据且不写表头的工作表会被跳过 |
| `withCharset(String)` | 覆写自基类 |

样式应用优先级：列配置（`ColumnConfig.style` / `ColumnConfig.headerStyle`）＞ 表级默认（`style` / `headerStyle`）。`CellStyleConfig` 实例按引用缓存复用 POI `CellStyle`（`IdentityHashMap`），避免超出样式上限。

**单元格写值规则**：`null`→空白；`Number`→`double`；`Boolean`→布尔；`Date`→日期；其余→`String.valueOf`。写入前会先应用行过滤（基类 `filter(Predicate)`，`testRow` 判定）。

**失败行为**：异常包装为 `RuntimeException("写入 Excel 失败: " + file, e)`，回调 `onComplete(false)`；正常完成回调 `onComplete(true)`。`finish()` 的回调序列：`onStart` → `onBeginWrite` → 每个被写出的 sheet 一次 `onProgress(已处理, 总数)` → `onComplete`（`writeAndFlush` 无 `onProgress`）。

---

## 5. 样式与列配置：CellStyleConfig / ColumnConfig

### 5.1 CellStyleConfig（单元格样式，`@since 4.0.0.42`）

流式构建器，入口 `CellStyleConfig.create()`，通过 `applyTo(Workbook)` 生成 POI `CellStyle`。

| 分类 | 方法 |
| --- | --- |
| 字体 | `fontName(String)`、`fontSize(int)`、`bold()` / `bold(boolean)`、`italic()`、`strikeout()`、`underline(byte)`、`fontColor(String)` |
| 背景 | `backgroundColor(String)`、`fillPattern(FillPatternType)`（默认 `SOLID_FOREGROUND`） |
| 边框 | `border(BorderStyle)`（四边同设）、`borderTop` / `borderBottom` / `borderLeft` / `borderRight(BorderStyle)`、`borderColor(String)` |
| 对齐 | `horizontalLeft()` / `horizontalCenter()` / `horizontalRight()`、`horizontalAlignment(HorizontalAlignment)`、`verticalTop()` / `verticalCenter()` / `verticalBottom()`、`verticalAlignment(VerticalAlignment)`、`wrapText(boolean)`、`indention(int)`、`rotation(int)` |
| 数据格式 | `dataFormat(String)`，如 `"yyyy-MM-dd"` |
| 行高 | `rowHeight(float)`（磅，随使用该样式的行生效） |

颜色取值两种写法：六位 RGB 十六进制（如 `"4472C4"`，在 `XSSFWorkbook` 下经 `XSSFColor` 精确着色）或 `IndexedColors` 枚举名（如 `"RED"`，大小写不敏感；无法解析时回退黑色）。

### 5.2 ColumnConfig（列配置，`@since 4.0.0.42`）

流式构建器，入口 `ColumnConfig.of(String fieldName)`。

| 方法 | 说明 |
| --- | --- |
| `displayName(String)` | 表头显示文本（缺省用字段名） |
| `width(int)` | 列宽（字符数，写出时 ×256 换算为 POI 单位） |
| `style(CellStyleConfig)` | 该列数据单元格样式 |
| `headerStyle(CellStyleConfig)` | 该列表头样式（覆盖表级表头样式） |
| `hidden()` | 隐藏该列 |

---

## 6. 导出与模板填充（export 包）

### 6.1 PoiDocumentGenerator（接口一：数据 → 文档）

`com.chua.excel.support.export.PoiDocumentGenerator`，注解 `@Spi(value = {"poi", "excel"}, order = 0)`，实现 `com.chua.common.support.export.spi.DocumentGenerator`。

- `formats()` 返回 `{xlsx}`（`ExportFormats.XLSX`）；`engine()` 返回 `"poi"`。
- 入口 `generate(ExportContext, OutputStream)`：按 `DocumentMeta` 从零构建工作簿。
- 能力：可选标题行（合并整行、14pt 加粗、行高 26 磅）、表头行（深蓝底 `#1F4E79` 白字、行高 22 磅）、冻结窗格、自动筛选、列宽（列元数据指定，否则 `max(16, min(40, 列名字符数×2+6))`）。
- 分片：数据行数超过 `DocumentMeta.getRowsPerSheet()` 时自动拆分多个工作表，分片数大于 1 时各表在基础名后追加 `_1`、`_2`、`_3`…；基础表名最长 28 字符（分片后缀另计），非法字符 `[\\/*?:\[\]]` 替换为 `_`，空名回退 `Sheet1`。
- 流式：`ExportConfig.isStreaming()` 为真时使用 `SXSSFWorkbook(200)` 滚动窗口写出。
- 校验：无列时抛 `ExportException("没有可导出的列，请检查 @ExportColumn 或列限定参数")`。
- 格式边界：仅产出 OOXML（`.xlsx`）；旧版二进制 `.xls` 无法处理，调用时需改用 `.xlsx`。
- 单元格写值：`Boolean` / `BigDecimal` / `Number` 写数值，其余写字符串，`null` 留白。

### 6.2 PoiTemplateFiller（接口二：模板 → 文档）

`com.chua.excel.support.export.PoiTemplateFiller`，注解 `@Spi(value = {"poi", "excel"}, order = 0)`，实现 `com.chua.common.support.export.spi.TemplateFiller`。

- `formats()` 返回 `{xlsx}`；`engine()` 返回 `"poi"`。
- 入口 `fill(TemplateContext, InputStream template, OutputStream out)`：加载模板工作簿，**逐个工作表**填充后写出。
- 占位符语法（由核心 `com.chua.common.support.export.support.Placeholders` 解析，与 Word / PDF 侧一致）：

| 写法 | 含义 |
| --- | --- |
| `{{key}}` | 标量替换；整格恰好一个占位符时按值类型写入原生类型（数值 / 布尔等），单元格样式保持不变 |
| `{{customer.address.city}}` | 嵌套属性路径 |
| `{{_row.field}}` | 循环行内取未导出为列的字段 |
| `{{@index}}` / `{{@first}}` / `{{@last}}` | 循环元信息 |
| `{{#tag}}` … `{{/tag}}` | 循环块：起始/结束标记行不产出内容，填充后移除；中间整行作为模板按数据条数复制；块内无数据时整块消失 |

- 模板块内的合并单元格区域会按数据条数平移重建；循环块自下而上处理，避免行号漂移。
- 格式边界：仅支持 OOXML（`.xlsx` / `.xlsm`）；旧版二进制 `.xls` 无法处理。

### 6.3 PoiStyles（样式工厂，包私有）

`com.chua.excel.support.export.PoiStyles` 为包私有 `final` 类，仅供 `PoiDocumentGenerator` 内部使用：按签名缓存 `CellStyle`（`ConcurrentReferenceHashMap`），因 Excel 样式数量有 64K 硬上限必须复用。内置常量：表头底色 `#1F4E79`、标题底色 `#DDEBF7`、边框色 `#BFBFBF`（非 XSSF 回退 `GREY_40_PERCENT`）、百分比格式 `0.00%`、金额列默认 2 位小数。

---

## 7. SPI 注册与扩展点

本模块是 SPI **实现方**；接口与解析逻辑都在 `utils-support-common-starter`。注册文件实际内容：

```
# META-INF/extensions/com.chua.common.support.file.FileSystem
excel=com.chua.excel.support.file.impl.ExcelFileSystem

# META-INF/services/com.chua.common.support.file.FileSystem（JDK ServiceLoader）
com.chua.excel.support.file.impl.ExcelFileSystem

# META-INF/extensions/com.chua.common.support.export.spi.DocumentGenerator
poi=com.chua.excel.support.export.PoiDocumentGenerator
excel=com.chua.excel.support.export.PoiDocumentGenerator
xlsx=com.chua.excel.support.export.PoiDocumentGenerator

# META-INF/extensions/com.chua.common.support.export.spi.TemplateFiller
poi=com.chua.excel.support.export.PoiTemplateFiller
excel=com.chua.excel.support.export.PoiTemplateFiller
xlsx=com.chua.excel.support.export.PoiTemplateFiller
```

扩展方式（来自 common 契约）：实现接口 → 标注 `@Spi` / `@Extension` → 在 `META-INF/extensions/<接口全限定名>` 登记 `名称=实现类`。`ServiceProvider.of(接口).getExtension(名称)` 取用；`@SpiOrder` / `order` 控制优先级（本模块两个 export 实现均 `order = 0`）。

`FileSystem.auto(new File("x.xlsx"))`（及 `.xls`）会按扩展名路由到本模块的 `excel` 实现。

---

## 8. 配置项

**无。** 依据：`src/main/resources` 下仅有 SPI 注册文件与 native-image 配置，没有 `application*.properties` / `*.yml`，源码中也没有任何 `@ConfigurationProperties` 或自动配置类；所有行为（表名、样式、列宽、过滤等）均通过构建器方法在代码中显式指定。字符集相关仅有构建器参数 `withCharset(...)`（默认取系统编码 `Charset.defaultCharset()`），对 Excel 二进制格式实际不参与读写。

---

## 9. 构建与依赖

```bash
mvn -o -q compile -DskipTests    # 在 utils-support-parent-starter 根目录执行
```

直接依赖（`pom.xml`）：

| 依赖 | 说明 |
| --- | --- |
| `com.chua:utils-support-common-starter` | `${project.version}`，提供 `FileSystem`、`ReadBuilder`/`WriteBuilder`、`Documents`、SPI 设施 |
| `org.apache.poi:poi-ooxml` | OOXML 读写（`XSSFWorkbook` / `SXSSFWorkbook`）；版本由父 POM 管理 |
| `org.apache.poi:poi` | POI 核心；版本由父 POM 管理 |

另继承父 POM 的 `lombok`、`org.jetbrains:annotations`（`provided`）。仓库内的 `dtrees.txt` 是一份依赖树快照（记录于 `4.0.0.42` 时期，其中 POI 为 `5.5.1`），仅供参考，实际版本以当前父 POM 为准。

---

## 10. 边界：这些能力不在本模块

| 能力 | 所在位置 |
| --- | --- |
| `FileSystem` / `ReadBuilder` / `WriteBuilder` 契约、`Documents` 门面、`Placeholders`、`@ExportColumn` / `@ExportSheet` 注解与元数据解析 | `utils-support-common-starter`（`com.chua.common.support.file.*` / `com.chua.common.support.export.*`） |
| CSV / JSON / XML / TXT / ZIP / TAR 等其它文件系统实现 | common-starter 内置或同仓其它 `utils-support-*-starter` |
| Word / PDF / OFD 等其它文档格式导出 | `utils-support-word-starter`、`utils-support-pdf-starter`、`utils-support-ofd-starter` 等 |
| `.xls` 格式**写出**与模板填充 | 本模块写入固定 `XSSFWorkbook`，导出/模板仅 `.xlsx`（`.xlsm` 模板可读）；`.xls` 仅在 `ExcelFileSystem` 读取侧经 `WorkbookFactory` 支持 |
| 追加写入已有工作簿 | 不支持：`newWorkbook(boolean)` 开关保留，但当前实现总是创建新工作簿 |
