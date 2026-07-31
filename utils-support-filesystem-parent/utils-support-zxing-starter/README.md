# Utils Support ZXing Starter

基于 ZXing 的二维码/条码生成与解析模块，支持艺术码点与圆角环形码眼样式。

---

## 功能概览

| 功能 | 说明 |
|------|------|
| 二维码生成 | 支持 QR Code、Data Matrix、Aztec 等格式 |
| 条码生成 | 支持 UPC-A、EAN-13、Code 128 等格式 |
| 图片解析 | 从图片中识别二维码/条码内容 |
| 样式化 | 支持艺术码点（ROUND_DOT）与圆角环形码眼（ROUND_RECTANGLE_DOT） |
| 自定义样式 | 前景色、背景色、边距、大小等参数可配置 |

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-zxing-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

### 2. 生成二维码

```java
import com.chua.common.support.qrcode.QrCode;

// 基础二维码
QrCode qrCode = QrCode.of("https://example.com")
    .size(300)
    .generate();

// 保存为文件
qrCode.toFile("qrcode.png");

// 转为 Base64
String base64 = qrCode.toBase64();
```

### 3. 带样式的二维码

```java
import com.chua.common.support.qrcode.QrCode;
import com.chua.common.support.qrcode.style.RoundDotStyle;

QrCode qrCode = QrCode.of("https://example.com")
    .size(300)
    .style(new RoundDotStyle())  // 艺术码点样式
    .foreColor(Color.BLACK)
    .backgroundColor(Color.WHITE)
    .margin(10)
    .generate();
```

### 4. 解析二维码

```java
import com.chua.common.support.qrcode.QrCodeReader;

// 从文件解析
String content = QrCodeReader.read("qrcode.png");

// 从 BufferedImage 解析
String content = QrCodeReader.read(bufferedImage);
```

---

## 配置说明

本模块为零配置模块，通过 API 参数控制生成选项。

### QrCode API 参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `size` | `int` | `300` | 图片大小（像素） |
| `foreColor` | `Color` | `Color.BLACK` | 前景色（码点颜色） |
| `backgroundColor` | `Color` | `Color.WHITE` | 背景色 |
| `margin` | `int` | `10` | 边距（像素） |
| `style` | `Style` | 默认样式 | 码点样式（RoundDotStyle 等） |

### 支持的样式

| 样式类 | 说明 |
|--------|------|
| `DefaultStyle` | 默认方形码点 |
| `RoundDotStyle` | 圆形艺术码点 |
| `RoundRectangleDotStyle` | 圆角环形码眼 |

---

## 依赖关系

```
utils-support-zxing-starter
├── utils-support-common-starter  # 核心基础
└── com.google.zxing:core         # ZXing 核心库
```
