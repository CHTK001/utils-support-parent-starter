# 统一文档导出（Document Export）

`com.chua.common.support.export` —— **一套门面、两个接口、三种格式**。

| 接口 | 语义 | 入口 |
| --- | --- | --- |
| 接口一 | 列表数据 → 文档（注解解析元数据） | `Documents.of(rows)` |
| 接口二 | 模板替换（标量 + 循环块） | `Documents.of(rows).template(...)` |

两种接口通过同一个门面 `Documents` 暴露，由 `template(...)` 切换构建器类型；
格式覆盖 **Excel(xlsx)、Word(docx)、PDF(pdf)**。

---

## 1. 模块归属

| 模块 | 内容 |
| --- | --- |
| `utils-support-common-starter` | SPI 接口、注解、元数据、解析器链、门面与构建器、占位符引擎 |
| `utils-support-excel-starter` | `PoiDocumentGenerator` / `PoiTemplateFiller`（引擎 `poi`） |
| `utils-support-word-starter` | `PoiWordDocumentGenerator` / `PoiWordTemplateFiller`（引擎 `poi-word`） |
| `utils-support-pdf-starter` | `PdfBoxDocumentGenerator` / `PdfAcroFormTemplateFiller`（引擎 `pdfbox`） |

SPI 与配置全部落在 `common-starter`，实现各自留在自己的模块，**core 不依赖任何 POI / PDFBox**。

---

## 2. 快速上手

### 接口一：列表数据 → 文档

```java
@ExportSheet(name = "订单", title = "订单月报", headerRow = 2)
public class OrderVo {

    @ExportColumn(name = "订单号", order = 1)
    private String orderNo;

    @ExportColumn(name = "金额", order = 2, type = ColumnType.AMOUNT, prefix = "¥")
    private BigDecimal amount;

    @ExportColumn(name = "状态", order = 3, type = ColumnType.ENUM, enumNames = {"0=未支付", "1=已支付"})
    private Integer status;

    @ExportColumn(ignore = true)
    private String traceId;
}

ExportResult result = Documents.of(orderService.list())
        .output(file)          // 或 OutputStream / Path
        .export();
```

### 接口二：模板替换

```java
// 模板直接给路径即可（文件系统路径或 classpath 相对路径）
ExportResult result = Documents.of(orders)             // 主数据自动绑定到默认标签 list
        .template(Path.of("templates/月报模板.xlsx"))  // ← 在此切换到模板模式
        .put("title", "2024 年 8 月订单月报")           // 标量
        .bind("orders", orders)                         // 循环标签
        .output(Path.of("out/订单月报.xlsx"))
        .export();
```

`template(...)` 有四个重载，路径最常用：

| 重载 | 说明 |
| --- | --- |
| `template(Path)` / `template(String)` | 路径。`String` 支持 `classpath:` 前缀；无前缀时先试文件系统再试类路径 |
| `template(File)` | 文件 |
| `template(byte[])` | 内存字节 |
| `template(InputStream)` | 流，**默认不关闭**（见 `autoCloseStream`） |

`Documents.ofTemplate(Path)` 可跳过数据行直接进入模板模式。

### 流的归属

默认遵循"谁打开谁关闭"：框架不关闭调用方传入的 `OutputStream` 与模板 `InputStream`。
需要托管时打开 `autoCloseStream(true)`：

```java
try (OutputStream out = Files.newOutputStream(Path.of("out/订单月报.xlsx"))) {
    Documents.of(orders).template(Path.of("templates/月报模板.xlsx")).output(out).export();
}

// 或者交给框架关：
Documents.of(orders)
        .template(Path.of("templates/月报模板.xlsx"))
        .output(out)                  // 无需 try-with-resources
        .autoCloseStream(true)
        .export();                    // 结束（含异常路径）时关闭 out 与模板流
```

注意：Excel / Word 填充器会先把模板读入内存再交给 POI，因此即便 `autoCloseStream(false)`，
POI 也不会"顺手"关掉调用方的模板流。

`ExportResult` 统一返回 `format` / `engine` / `rows` / `data()` / `file()` / `templated()` / `elapsed()`。

### 嵌套占位符

占位符支持对象属性路径，任意深度，Excel / Word / PDF 三端语法完全一致：

| 写法 | 含义 |
| --- | --- |
| `{{user.name}}` | 变量 → 对象/Map → 属性 |
| `{{customer.address.city}}` | 三级嵌套 |
| `{{items.0}}` / `{{items[0]}}` | 集合按下标 |
| `{{_row.remark}}` | 未导出为列的字段也能取到（循环行内） |
| `{{@index}}` / `{{@first}}` / `{{@last}}` | 循环内元信息（`{{index}}` 等价） |
| `{{#tag}} … {{/tag}}` | 列表循环块 |

```java
Documents.of(orders)
        .template(Path.of("templates/报表.docx"))
        .put("report", Map.of("owner", new User("张三", new Address("杭州"))))
        .bind("rows", orders)
        .output(Path.of("out/报表.docx"))
        .export();
```

```
{{report.owner.address.city}}          → 杭州
{{rows.0.customer.name}}               → 客户甲
{{#rows}}
| {{orderNo}} | {{customer.name}} | 第{{@index}}条 | {{_row.remark}} |
{{/rows}}
```

取值优先级：循环元信息 → **原始行对象的嵌套路径** → 当前行展开值 → 循环表 → 标量变量 → 原始行对象。
第二项是关键：展开后的行 Map 里同名列已是格式化后的展示值，若从那里再往下钻只能拿到 `toString`，
所以嵌套路径一律回到 `_row` 指向的原始对象上取。

列表数据通过 `bind(tag, rows)` 绑定；主数据（`Documents.of(rows)`）会自动绑到默认标签 `list`。


| 格式 | 接口一（数据 → 文档） | 接口二（模板替换） | 格式推断 |
| --- | --- | --- | --- |
| `xlsx` | POI 生成，带样式/冻结/筛选 | POI 填充，`{{key}}` + 行块循环 | `.xlsx` |
| `docx` | POI 生成，标题段落 + 表格 | POI 填充，段落标量 + 表格行块循环 | `.docx` |
| `pdf` | PDFBox 生成，自动换页 | 填充 PDF 的 **AcroForm 表单域** | `.pdf` |

* `.doc` / `.xls` 是旧版二进制格式，POI 只读写 OOXML，无法生成。这些格式**没有注册进 SPI**，
  传入时会明确报错并提示改用 `docx` / `xlsx`；引擎查找也不做"取第一个引擎"的兜底，
  避免把 xlsx 的内容静默写进 `.xls` / `.doc`。
* PDF 模板的占位符写在表单域的**默认值**里（Acrobat 域 / `PDTextField.setValue("{{title}}")`），
  填充按域全名匹配 `put(...)` 的键；下拉框 / 复选框按取值直接写入。
* 格式推断顺序：`format(...)` 显式指定 → `template(...)` 路径扩展名 → `output(...)` 文件名
  → 已注册填充器声明的格式 → 默认 `xlsx`。模板与输出都没有扩展名时（例如模板来自字节流）
  会跟随填充器，因此在只装了 pdf 引擎的模块里也能正常工作。

---

## 3. 元数据分层覆盖

注解解析由 `MetaResolver` 链完成，按 `order` **升序**依次作用于同一份可变的 `DocumentMeta`，
**后写入的覆盖先写入的**：

| 顺序 | 解析器 | order | 作用 |
| --- | --- | --- | --- |
| 1 | `JavabeanMetaResolver` | `-100` | 反射兜底：扫描字段、按类型推断 `ColumnType`、驼峰转标题 |
| 2 | `ThirdPartyMetaResolver` | `0` | 三方注解（FastExcel / EasyExcel），**纯反射，零编译期依赖** |
| 3 | `ExportAnnotationMetaResolver` | `100` | 自定义注解 `@ExportColumn` / `@ExportSheet`，**最终覆盖** |

即：**三方注解 → 自定义注解 → 反射兜底**，即"谁更具体谁赢"。

### 3.1 哨兵值：未声明 ≠ 覆盖

分层覆盖能成立的前提是能区分"没写"和"写了空"。约定如下：

| 类型 | 哨兵（未声明） | 说明 |
| --- | --- | --- |
| `String` | `""` | 空串表示未声明，**不覆盖**下层结果 |
| `String`（`DocumentMeta#title` / `sheetName`） | `null` | `null` 表示**显式清空**，`""` 才是未声明 |
| `int` | `ExportColumn.UNSET` | |
| `ColumnType` / `HorizontalAlign` | `AUTO` | |

```java
Documents.of(rows).title("月报")   // 覆盖注解声明的标题
Documents.of(rows).title("")       // 未声明 → 保留注解里的标题
Documents.of(rows).title(null)     // 显式清空标题
```

### 3.2 三方注解

`ThirdPartyMetaResolver` 通过反射探测下列注解，**类不在 classpath 时静默跳过**：

- `cn.idev.excel.annotation.ExcelProperty`（FastExcel）
- `com.alibaba.excel.annotation.ExcelProperty`（EasyExcel）
- `cn.idev.excel.annotation.ExcelIgnore` / `com.alibaba.excel.annotation.ExcelIgnore`

**只有真正携带受支持注解的成员才会注册列**，不会凭空造列；普通 POJO 完全不受影响。

### 3.3 Map 行数据

解析器链无任何产出时（如行数据是 `Map`），`MetaResolvers.inferFromSample` 会按首次出现顺序
合并全部样本行的键来补全列，类型取首个非空值的运行时类型。

---

## 4. 模板语法

Excel 与 Word 使用**完全一致**的语法，均由 core 的 `Placeholders` 实现（不依赖任何第三方模板库）。
前后缀可通过 `ExportConfig#tagPrefix` / `tagSuffix` 配置，默认 `{{ }}`。

| 语法 | 含义 |
| --- | --- |
| `{{key}}` | 标量占位符，取自 `variables()` |
| `{{a.b}}` | 嵌套路径 |
| `{{#tag}}` … `{{/tag}}` | 循环块，可跨行 / 跨单元格 |
| `{{@index}}` / `{{@first}}` / `{{@last}}` | 循环内元信息 |

### Excel（行块）

```
| {{title}}           |                        |
| {{#orders}}         |   ← 标记行，填充后移除  |
| {{orderNo}}|{{status}}|  ← 模板行，复制 N 次  |
| {{/orders}}         |   ← 标记行，填充后移除  |
```

### Word（表格行块）

```
| 订单号      | 状态       |   ← 表头
| {{#rows}}   |            |   ← 标记行，填充后移除
| {{orderNo}} | {{status}} |   ← 模板行，复制 N 次
| {{/rows}}   |            |   ← 标记行，填充后移除
```

两行标记本身不产出内容，填充后被移除；无数据时整块消失，不留空行。

### PDF（AcroForm）

PDF 模板走 **AcroForm 表单域**，而非文本占位符：

- 文本域 `PDTextField`
- 复选框 `PDCheckBox`（`true` → 勾选）
- 单选框 `PDRadioButton`（取值匹配 on 值）
- 下拉框 `PDComboBox`（无匹配项时追加后选中）

变量查找顺序：表单域全名 → 域短名 → 变量表（忽略大小写）。
**模板默认值本身也可以写 `{{tag}}`**，未提供对应变量时会自动替换。

> 模板不含 AcroForm 时抛出可读异常，提示改用其他格式。

---

## 5. 引擎选择

`Engines` 负责按 `(format, engine)` 查表，未显式指定 `engine` 时取该格式的默认实现。

```java
Engines.describe();               // 列出全部可用引擎与格式
Engines.generatorFormats();       // [pdf, xlsx, docx]
Engines.fillerFormats();          // [pdf, xlsx, docx]
Engines.generator("xlsx", null).engine();  // poi
```

新增引擎只需实现 `DocumentGenerator` / `TemplateFiller` 并在
`META-INF/extensions/<接口全限定名>` 中注册 `id=实现类全名`，
`@Spi(value = {...}, order = ...)` 决定多实现时的优先级。

---

## 6. 迁移说明

旧的模板替换链路（`TemplateFileSystem` / `TextTemplateFileSystem` /
`WriteBuilder#withTemplate` / `Word·PdfWriteBuilder#resolveTemplate()`）已删除 ——
它从未注册过 SPI 实现，运行时必然 NPE。

- 需要 **模板替换** → 改用 `Documents.of(rows).template(...)`
- 需要 **数据写文件** → 继续用 `WriteBuilder` 子类
- HTML / Markdown 文档导出（`DocumentExporter` / `DocumentTemplate`）不受影响

---

## 7. 验收测试

| 测试类 | 覆盖 | 用例数 |
| --- | --- | --- |
| `MetaResolverTest` | 分层覆盖、哨兵语义、三方注解纯反射解析 | 8 |
| `DocumentsFacadeTest` | 门面 / 构建器链 / 列限定 / 模式切换 / `Path` 重载 / `autoCloseStream` / 旧格式提示 | 14 |
| `ExcelExportAcceptanceTest` | xlsx 生成、Map 行、拆分工作表、xlsx 模板循环、路径模板、流托管、嵌套占位符、`.xls` 明确报错 | 14 |
| `WordExportAcceptanceTest` | docx 生成、分页重复表头、docx 模板循环、路径模板、流托管、嵌套占位符 | 9 |
| `PdfExportAcceptanceTest` | pdf 生成、分页、AcroForm 填充、路径模板、流托管、嵌套占位符 | 11 |

运行：

```bash
mvn -o test -pl utils-support-core-parent/utils-support-common-starter \
  "-Dtest=MetaResolverTest,DocumentsFacadeTest" -DskipTests=false

mvn -o test -pl utils-support-filesystem-parent/utils-support-excel-starter,utils-support-filesystem-parent/utils-support-word-starter,utils-support-filesystem-parent/utils-support-pdf-starter \
  "-Dtest=*ExportAcceptanceTest" -DskipTests=false
```

> PowerShell 下 `-D` 参数需用引号包裹。
> 父 POM 默认 `skipTests=true`，需显式覆盖。
> PDF 中文渲染依赖系统中文字体，可用 `-Dchua.export.pdf.font=/path/to/font.ttf` 指定。