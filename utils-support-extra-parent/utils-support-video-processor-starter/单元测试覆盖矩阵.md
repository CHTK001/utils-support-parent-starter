# 单元测试覆盖矩阵

> 本文档记录 `utils-support-video-processor-starter` 模块的单元测试覆盖情况。
> 远程集成测试（需上传视频文件并经 Rust native 库转码）标记为 `[MANUAL]`，需在动态库已编译部署时执行。

## 模块索引

| 模块 | artifactId | 测试类型 | 备注 |
|------|------------|---------|------|
| Video Processor | `utils-support-video-processor-starter` | 单元 | Rust JNI 视频转 HLS |

---

## Video Processor Starter 覆盖矩阵

### Bridge 加载与调用

| 维度 | 覆盖项 | 状态 |
|------|--------|------|
| 加载状态 | `VideoProcessorBridge.isLoaded()` | ✅ |
| 加载错误 | `VideoProcessorBridge.getLoadError()` | ✅ |
| 当前版本 | `getVersion()` → 非空字符串 | ✅ |
| 缺失输入 | `transcodeToHls("missing", "missing")` → `false` | ✅ |
| 完整转码 | 本地视频 → HLS 切片 | [MANUAL] |

### 静态 API 封装

| 维度 | 覆盖项 | 状态 |
|------|--------|------|
| 可用性 | `VideoProcessor.isAvailable()` | ✅ |
| 版本获取 | `VideoProcessor.getVersion()` | ✅ |
| 转码入口 | `VideoProcessor.transcodeToHls(in, out)` | ✅ |
| 命令行 | `VideoProcessor.main(args)` | ✅ |

### FileSource（URL 支持）

| 维度 | 覆盖项 | 状态 |
|------|--------|------|
| 路径创建 | `FileSource.of(String)` | ✅ |
| 类型推断 | path 后缀 → `getType()` | ✅ |
| 输入流 | `FileSource.of(InputStream, String)` | ✅ |
| 输出流 | `FileSource.of(OutputStream, String)` | ✅ |
| URL 创建 | `FileSource.of(URL)` 自动推断类型 | ✅ |
| URL 显式类型 | `FileSource.of(URL, String)` | ✅ |
| URL 协议 | `https://`、`file://` | ✅ |

### ConvertFileSystem（URL 重载）

| 维度 | 覆盖项 | 状态 |
|------|--------|------|
| 路径转路径 | `convert(String, String)` | ✅ |
| 文件转文件 | `convert(File, File)` | ✅ |
| URL 转路径 | `convert(URL, String)` | ✅ |
| URL 转文件 | `convert(URL, File)` | ✅ |
| 抽象基类 | `AbstractConvertFileSystem.doConvert` 委托 | ✅ |

---

## 运行方式

### 本地单元测试（无需 native 库）

```bash
mvn test -pl utils-support-network-parent/utils-support-video-processor-starter
```

或直接运行测试主类：

```bash
mvn exec:java -pl utils-support-network-parent/utils-support-video-processor-starter \
  -Dexec.mainClass=com.chua.video.processor.support.VideoProcessorTest
```

### 示例运行

```bash
# 演示 SPI 调用 + 静态 API
mvn exec:java -pl utils-support-network-parent/utils-support-video-processor-starter \
  -Dexec.mainClass=com.chua.video.processor.support.example.VideoProcessorExample
```

### 完整转码集成测试 [MANUAL]

> 需先编译 Rust 动态库并替换占位文件（见 `utils-support-native-video-processor` 模块 `src/main/rust/build.sh`）。

```bash
# 1. 编译 Rust native 库
cd G:/work/utils-support-native-parent/utils-support-native-video-processor/src/main/rust
export FFMPEG_LIBS_DIR=$HOME/ffmpeg-static/lib
./build.sh linux x86_64 release

# 2. 安装 native + starter
mvn install -pl utils-support-native-parent/utils-support-native-video-processor
mvn install -pl utils-support-network-parent/utils-support-video-processor-starter

# 3. 运行转码
java -cp target/classes com.chua.video.processor.support.VideoProcessor input.mp4 ./hls_output
```

---

## 维护说明

- 新增测试后请更新对应模块的矩阵行
- `[MANUAL]` 项需确保 Rust native 动态库已编译部署
- 每个 Sprint 结束后同步本文件
