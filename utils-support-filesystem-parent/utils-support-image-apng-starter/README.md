# utils-support-image-apng-starter

APNG 动画图片处理模块，提供 APNG（Animated PNG）格式图片的处理功能。
        主要功能：
        - APNG 解析：解析 APNG 动画图片格式
        - 帧提取：提取 APNG 中的各个动画帧
        - 时间控制：获取和设置帧的显示时间
        - 格式转换：APNG 与其他动画格式的转换
        - 元数据：读取 APNG 的元数据信息
        - 图像处理：对 APNG 进行基本的图像处理
        支持的操作：
        - APNG 文件读取和写入
        - 动画帧的分离和合并
        - 帧延迟时间设置
        - 循环次数控制
        - 透明度处理
        - 颜色空间转换
        适用场景：
        - 动画图片处理
        - Web 动画制作
        - 游戏资源处理
        - 移动应用开发
        - 图像编辑工具
        - 多媒体内容管理

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-image-apng-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

---

## 功能概览

| 类/接口 | 说明 |
|---------|------|
| `ApngToGifConvertFileSystem` | APNG 转 GIF 转换器 将 APNG（Animated PNG）格式转换为 GIF 格式，支持： 1. 动画帧提取和转换 2. 帧延迟时间处理 3. 透明 (SPI: `apng2gif`) |
| `GifToApngConvertFileSystem` | GIF 转 APNG 转换器 将 GIF 格式转换为 APNG（Animated PNG）格式，支持： 1. 动画帧提取和转换 2. 帧延迟时间处理 3. 透明 (SPI: `gif2apng`) |
| `ApngUtils` | APNG 工具类 提供 APNG 图像处理的通用工具方法，包括： 1. APNG 文件读取和写入 2. 动画帧提取和合并 3. 元数据处理 4. 格式检测和验证 |

---

## 配置说明

本模块为零配置模块，引入依赖后即可使用。

---

## 依赖关系

```
utils-support-image-apng-starter
├── utils-support-common-starter
```
