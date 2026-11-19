# utils-support-zxing-starter

> 基于 ZXing 与 qrcode-plugin 的二维码**生成**模块：唯一入口 `DefaultQrCode#out(String, OutputStream)`，把 `QrSetting` 描述的样式（码点、码眼、背景、Logo、前置图）渲染成 PNG 二维码。

| 项 | 值 |
|---|---|
| Maven 坐标 | `com.chua:utils-support-zxing-starter` |
| 版本 | `4.0.0.51`（父模块 `utils-support-filesystem-parent`） |
| JDK | 25（`maven.compiler.source/target=25`，源码编码 UTF-8） |
| 根包 | `com.chua.zxing.support.qr` |
| 对外契约 | `com.chua.common.support.lang.qr.AbstractQrCode`（定义在 `utils-support-common-starter`） |

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [核心 API 速查](#核心-api-速查)
- [快速开始](#快速开始)
- [配置项](#配置项)
- [SPI 扩展点](#spi-扩展点)
- [构建与依赖](#构建与依赖)
- [边界与注意事项](#边界与注意事项)

## 它是什么 / 不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| QR 码 PNG **生成**器：`out(content, outputStream)` 直接向输出流写 PNG | 二维码/条码**解析识别**器（源码只调用 zxing 的 `Encoder.encode`，没有任何解码路径） |
| 样式化二维码：6 种码点样式、码眼颜色/图片、渐进色背景、Logo、前置图 | 通用条码工具（源码未使用 zxing 其它 `BarcodeFormat` 做输出） |
| 纯 Java SPI 模块：`@Spi` 注解 + `META-INF/extensions` 注册文件 + 同包扫描 | Spring Boot Starter（pom 无任何 Spring 依赖，无自动装配，无 application 配置文件） |
| `utils-support` 体系成员：契约类（`QrSetting`、各 `*Setting`、枚举）来自 `utils-support-common-starter` | 自带配置体系的独立 SDK（配置全部通过 POJO 在代码中设置） |

## 核心 API 速查

| 你想做的事 | 入口 | 说明 |
|---|---|---|
| 生成二维码并写出 | `AbstractQrCode#out(String content, OutputStream outputStream)` | 唯一对外方法，输出 PNG；失败统一抛 `RuntimeException` |
| 创建实现 | `new DefaultQrCode(QrSetting)` | 直接实例化；或经 SPI：`ServiceProvider.of(AbstractQrCode.class).getNewExtension("zxing", setting)` |
| 尺寸与纠错 | `QrSetting#setWidth / setHeight / setCodeLevel(CodeLevel.L/M/Q/H)` | 必须设置，否则编码阶段报错 |
| 码点样式 | `CodePointSetting#setCodePoint(CodePointStyle)` | `MINI_RECT` / `RECTANGLE` / `CIRCLE` / `ROUND_DOT` / `TEXT` / `IMAGE` |
| 码眼样式与图片 | `CodeEyeSetting#setCodeEye(CodeEyeStyle)`、`setCodeEyeColor`、`setCodeEyeImage`、`setLtCodeEyeImage` 等 | 任一码眼图片非空时强制按 `IMAGE` 处理 |
| 背景样式 | `BackgroundSetting#setBgImgStyle("PROGRESSIVE")`、`setFromColor`、`setToColor`、`setBgAlpha`、`setBackgroundImage` | 背景渲染器按 `bgImgStyle` 名称经 SPI 查找 |
| Logo / 前置图 | `LogoSetting#setLogoImage / setLogoShape(LogoStyle.CIRCLE/ROUND) / setLogoAlpha / setLogoBorderColor`；`FrontSetting#setFtImg / setFtFillColor / setFtStartX / setFtStartY` | 分别在 `QrSetting#setLogoSetting` / `setFrontSetting` 上生效 |
| 渲染工具方法 | `QrCodeRenderUtils`（静态方法，如 `drawQrInfo`、`drawLogo`、`drawBackground` 等） | 主要供模块内部与自定义扩展使用 |

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zxing-starter</artifactId>
    <version>4.0.0.51</version>
</dependency>
```

### 2. 最小可用示例

```java
import com.chua.common.support.lang.qr.*;
import com.chua.zxing.support.qr.DefaultQrCode;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class QrDemo {
    public static void main(String[] args) throws Exception {
        QrSetting setting = new QrSetting();
        setting.setWidth(300);
        setting.setHeight(300);
        setting.setCodeLevel(CodeLevel.H);

        CodePointSetting point = new CodePointSetting();
        point.setCodePoint(CodePointStyle.ROUND_DOT);   // 流体风格码点
        point.setCodePointColor("#1A73E8");
        setting.setCodePointSetting(point);

        CodeEyeSetting eye = new CodeEyeSetting();
        eye.setCodeEye(CodeEyeStyle.CIRCLE);            // 码眼样式：仅在 emptiness/渐进色背景下生效（见「SPI 扩展点」）
        eye.setCodeEyeColor("#1A73E8");
        setting.setCodeEyeSetting(eye);

        AbstractQrCode qrCode = new DefaultQrCode(setting);
        try (OutputStream os = new FileOutputStream("qr.png")) {
            qrCode.out("https://example.com", os);      // 写出 PNG
        }
    }
}
```

> `width`、`height`、`codeLevel`、`codePointSetting`、`codeEyeSetting` 都必须设置：
> `codeLevel` 为 `null` 时会在 `ErrorCorrectionLevel.valueOf(...)` 处抛 NPE，
> 两个 Setting 为 `null` 时会在渲染阶段抛 NPE。

### 3. 渐变背景 + Logo

```java
BackgroundSetting bg = new BackgroundSetting();
bg.setBgImgStyle("PROGRESSIVE");          // 背景解析器的 SPI 名称
bg.setFromColor("#0F2027");
bg.setToColor("#2C5364");
bg.setBgAlpha(1.0f);
setting.setBackgroundSetting(bg);         // 非 null 即启用背景解析

LogoSetting logo = new LogoSetting();
logo.setLogoImage("/path/to/logo.png");   // 支持 BufferedImage / File / InputStream / String(路径或 URL)
logo.setLogoShape(LogoStyle.ROUND);       // CIRCLE 圆形 / ROUND 圆角
logo.setLogoAlpha(0.9f);
logo.setLogoBorderColor("#FFFFFF");
setting.setLogoSetting(logo);
```

### 4. 通过 SPI 获取实现

```java
import com.chua.common.support.spi.ServiceProvider;

AbstractQrCode qrCode = ServiceProvider.of(AbstractQrCode.class)
        .getNewExtension("zxing", setting);   // 名称 "zxing" 或 "default"
qrCode.out("https://example.com", outputStream);
```

`getNewExtension(name, args)` 的可变参数会传给 `DefaultQrCode(QrSetting)` 构造器。

## 配置项

本模块**不依赖 Spring、没有 application 配置文件**（`src/main/resources` 下只有 `META-INF/extensions` 与 native-image 配置）。所有配置通过 `QrSetting` 及其子配置对象在代码中设置；这些配置类定义在 `utils-support-common-starter` 的 `com.chua.common.support.lang.qr` 包中，均为 Lombok `@Data` POJO。

### QrSetting（总配置）

| 字段 | 类型 | 说明 |
|---|---|---|
| `width` / `height` | `int` | 画布宽高（像素），必须设置 |
| `codeLevel` | `CodeLevel` | 纠错等级：`L`(约7%) / `M`(约15%) / `Q`(约25%) / `H`(约30%) |
| `codePointSetting` | `CodePointSetting` | 码点配置，必须设置 |
| `codeEyeSetting` | `CodeEyeSetting` | 码眼（定位图案）配置，必须设置 |
| `backgroundSetting` | `BackgroundSetting` | 背景配置；非 `null` 时启用背景解析（`hasBackgroundStyle()`） |
| `logoSetting` | `LogoSetting` | Logo 配置 |
| `frontSetting` | `FrontSetting` | 前置图配置 |

### CodePointSetting（码点）

| 字段 | 类型 | 说明 |
|---|---|---|
| `codePoint` | `CodePointStyle` | `MINI_RECT` 迷你矩形 / `RECTANGLE` 矩形 / `CIRCLE` 圆形 / `ROUND_DOT` 流体圆点（相邻点胶囊连通）/ `TEXT` 文字 / `IMAGE` 图片 |
| `codePointColor` | `String` | 码点颜色，支持十六进制字符串（如 `#000000`） |
| `drawEnableScale` | `boolean` | 是否启用多像素合并绘制（`CIRCLE` 样式会强制开启） |
| `codePointImage` | `String` | 码点图片路径；非空时强制按 `IMAGE` 样式处理 |
| `codePointText` | `String` | `TEXT` 样式必填，为空时抛 `IllegalArgumentException("CodePointText is null")` |

### CodeEyeSetting（码眼 / 定位图案）

| 字段 | 类型 | 说明 |
|---|---|---|
| `codeEye` | `CodeEyeStyle` | `NONE` / `CIRCLE` / `IMAGE` |
| `codeEyeColor` | `String` | 码眼颜色，同时作用于外圈与内圈 |
| `codeEyeImage` | `String` | 通用码眼图片；非空时强制按 `IMAGE` 处理 |
| `ltCodeEyeImage` | `String` | 左上角码眼图片；非空时强制按 `IMAGE` 处理 |
| `lbCodeEyeImage` | `String` | 左下角码眼图片；非空时强制按 `IMAGE` 处理 |
| `rtCodeEyeImage` | `String` | 右上角码眼图片；非空时强制按 `IMAGE` 处理 |

> `codeEye` 枚举仅在 emptiness/渐进色背景下经 SPI 选择 `CodeEyeResolver` 改变码眼画法（如 `CIRCLE` → `CircleCodeEyeResolver`）；默认绘制路径下生效的是 `codeEyeColor` 与各码眼图片（任一图片非空时强制按 `IMAGE` 处理）。

### BackgroundSetting（背景）

| 字段 | 类型 | 说明 |
|---|---|---|
| `backgroundImage` | `Object` | 背景图片 |
| `bgAlpha` | `float` | 背景透明度，0.0 ~ 1.0 |
| `bgImgStyle` | `String` | 背景渲染器 SPI 名称，模块内置 `PROGRESSIVE`（渐进色） |
| `fromColor` / `toColor` | `String` | 渐变起始/结束颜色 |

### LogoSetting（Logo）

| 字段 | 类型 | 说明 |
|---|---|---|
| `logoImage` | `Object` | Logo 图片：`BufferedImage` / `File` / `InputStream` / `String`（路径或 URL） |
| `logoAlpha` | `Float` | 透明度 0.0 ~ 1.0；`null` 表示不透明处理 |
| `logoShape` | `LogoStyle` | `CIRCLE` 圆形 / `ROUND` 圆角 |
| `logoBorderColor` | `String` | Logo 边框颜色；为空不显示边框 |

### FrontSetting（前置图）

| 字段 | 类型 | 说明 |
|---|---|---|
| `ftImg` | `Object` | 前置图片对象 |
| `ftFillColor` | `String` | 画布填充颜色（如 `#000000`） |
| `ftStartX` / `ftStartY` | `int` | 前置图起始坐标 |

## SPI 扩展点

实现类的名称来自 `@Spi` 注解；未标注 `@Spi` 的**同包实现**会被 `SamePackageServiceResolver` 以「类名去掉接口名后的短名（转大写）」发现，例如 `CircleDrawEyeResolver → CIRCLE`、`DefaultCodePointRender → DEFAULT`。名称匹配不区分大小写。

| SPI 接口 | 实现 | 名称 | 发现方式 |
|---|---|---|---|
| `AbstractQrCode` | `DefaultQrCode` | `zxing`、`default` | `@Spi` + extensions 文件 |
| `DrawResolver` | `DefaultDrawResolver` | `MINI_RECT`、`RECT` | `@Spi` + extensions 文件 |
| `DrawResolver` | `ImageDrawResolver` | `IMAGE_V2` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `MiniRectDrawPointResolver` | `MINI_RECT` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `RectDrawPointResolver` | `RECTANGLE` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `CircleDrawPointResolver` | `CIRCLE` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `RoundedCircleDrawPointResolver` | `ROUND_DOT` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `TxtDrawPointResolver` | `TEXT` | `@Spi` + extensions 文件 |
| `DrawPointResolver` | `ImageDrawPointResolver` | `image` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `RectDrawEyeResolver` | `RECTANGLE` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `RoundRectangleDrawEyeResolver` | `ROUND_RECTANGLE` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `RoundRectDotDrawEyeResolver` | `ROUND_RECTANGLE_DOT` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `AirBubbleDrawEyeResolver` | `AIR_BUBBLE` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `EyeShapeRectangleDrawEyeResolver` | `EYE_SHAPE` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `SingleSidedFilletRectangleDrawEyeResolver` | `SINGLE_SIDED_FILLET` | `@Spi` + extensions 文件 |
| `DrawEyeResolver` | `CircleDrawEyeResolver` | `CIRCLE` | 同包扫描 |
| `DrawEyeResolver` | `ImageDrawEyeResolver` | `IMAGE` | 同包扫描 |
| `CodeEyeResolver`（emptiness 背景） | `CircleCodeEyeResolver` | `CIRCLE` | 同包扫描 |
| `CodeEyeResolver`（emptiness 背景） | `RectangleCodeEyeResolver` | `RECTANGLE` | 同包扫描 |
| `CodeEyeResolver`（emptiness 背景） | `RoundRectangleCodeEyeResolver` | `ROUNDRECTANGLE` | 同包扫描 |
| `BackgroundResolver` | `ProgressiveEmptinessBackgroundResolver` | `PROGRESSIVE` | `@Spi` + extensions 文件 |
| `BackgroundResolver` | `DefaultBackgroundResolver` | `DEFAULT` | 同包扫描 |
| `BackgroundResolver` | `EmptinessBackgroundResolver` | `EMPTINESS` | 同包扫描 |
| `CodePointRender` | `DefaultCodePointRender` | `DEFAULT` | 同包扫描 |
| `CodeEyeRender` | `DefaultCodeEyeRender` | `DEFAULT` | 同包扫描 |
| `LogoResolver` | `DefaultLogoResolver` | `DEFAULT` | 同包扫描 |
| `FrontResolver` | `DefaultFrontResolver` | `DEFAULT` | 同包扫描 |
| `CodeProduct` | `DefaultCodeProduct` | `DEFAULT` | 同包扫描 |
| `CodeProduct` | `EmptinessCodeProduct` | `EMPTINESS` | 同包扫描 |

补充说明：

- 逐点绘制时，`DefaultDrawResolver` 按 `CodeEyeSetting.codeEye` 与 `CodePointSetting.codePoint` 的枚举名经 SPI 实例化码眼/码点渲染器；`DrawResolver` 本身由 `QrCodeRenderUtils` 按绘制风格直接实例化（`IMAGE_V2` → `ImageDrawResolver`，否则 `DefaultDrawResolver`）。
- `CodeEyeStyle` 枚举只提供 `NONE / CIRCLE / IMAGE` 三个取值；上表中另外 6 种码眼渲染器（`RECTANGLE`、`ROUND_RECTANGLE`、`ROUND_RECTANGLE_DOT`、`AIR_BUBBLE`、`EYE_SHAPE`、`SINGLE_SIDED_FILLET`）虽已注册 SPI 名称，但无法通过 `CodeEyeSetting.codeEye` 枚举选到。
- 名称未命中任何实现、且不存在默认实现时，`getNewExtension` 返回 `null` 并记录 WARN 日志。

自定义扩展：实现上述任一接口，加 `@Spi("名称")` 注解并放在接口同包（或写入 `META-INF/extensions/<接口全限定名>` 注册文件），即可被 `ServiceProvider` 发现。

## 构建与依赖

```bash
mvn -o -q compile -DskipTests
```

pom.xml 声明的依赖：

| 依赖 | 版本 | 用途 |
|---|---|---|
| `com.chua:utils-support-common-starter` | `${project.version}` | 契约类（`AbstractQrCode`、`QrSetting` 等）与 SPI 框架 |
| `com.github.liuyueyi.media:qrcode-plugin` | `3.0.1-beta` | `QrCodeGenWrapper`、`QrCodeOptions`、`BitMatrixEx` 等渲染封装（zxing core 为其传递依赖） |
| `org.iherus:qrext4j` | `1.3.1` | 声明依赖；本模块源码中未见直接引用 |

GraalVM native-image 元数据随包提供：`META-INF/native-image/com.chua/utils-support-zxing-starter/reflect-config.json` 与 `resource-config.json`。

变更记录见 [CHANGELOG.md](CHANGELOG.md)。

## 边界与注意事项

1. **只生成，不解析**：模块内没有任何二维码/条码识别代码。
2. **只输出 PNG**：`out` 固定以 `"png"` 格式写入输出流。
3. **异常语义**：`out` 内部捕获所有异常并统一包装为 `RuntimeException("QR 码图像写出失败: ...")`。
4. **必填配置**：`width`、`height`、`codeLevel`、`codePointSetting`、`codeEyeSetting` 缺一不可，否则运行时抛错。
5. **留白上限**：安静区（quiet zone）被限制在 `[0, 4]`（`QUIET_ZONE_SIZE = 4`）。
6. **TEXT 码点**：必须同时设置 `codePointText`，否则抛 `IllegalArgumentException`。
7. **码眼样式取值受限**：见「SPI 扩展点」一节的说明。
8. 本模块属于最底层 `utils-support` 工具库，不依赖任何上层业务 starter，也不感知 Spring 环境。
