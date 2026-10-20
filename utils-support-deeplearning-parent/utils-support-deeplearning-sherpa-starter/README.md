# utils-support-deeplearning-sherpa-starter

sherpa-onnx **官方 JNI** 集成模块：直接调用 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
的 C++ 实现，完成语音识别（离线 + 流式）、语音合成与语音活动检测。

与 `utils-support-deeplearning-onnx-starter` 里 `audio/**` 那些「用纯 Java 复刻 sherpa-onnx
推理流程」的翻译器（`ParaformerTokenizer`、`SenseVoiceTranslator`、`ZipformerStreamingTranslator`、
`VitsTtsTranslator` 等）相比，本模块的定位不同：

| 维度 | onnx-starter（纯 Java 复刻） | 本模块（官方 JNI） |
|---|---|---|
| 推理实现 | Java 逐行复刻 fbank / 解码 / 状态传递 | sherpa-onnx 原生层 |
| 新增模型族 | 每族都要写一份推理代码 | 换模型目录即可 |
| 支持模型 | zipformer / paraformer / sensevoice / whisper / vits / pocket-tts | 上述全部 + Matcha / Kokoro / FireRedASR / Moonshine / NeMo / Dolphin / Qwen3-ASR 等 |
| 依赖 | onnxruntime + DJL | `sherpa-onnx-jvm` + 平台 native-lib（JitPack） |
| 时间戳 / 语种 / 情感 / 事件 | 部分手写 | 原生直接返回 |

两者互不替代，可按「能否接受 JNI 依赖」二选一，也可以并存（SPI 名称不同）。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.chua</groupId>
    <artifactId>utils-support-deeplearning-sherpa-starter</artifactId>
    <version>${project.version}</version>
</dependency>
```

本模块会自动引入：

- `com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:v1.13.8`（JVM API，来自 JitPack）
- 按 `os.name` / `os.arch` **自动激活**的对应平台 native 库
  （`sherpa-onnx-native-lib-win-x64` 等，内含 `sherpa-onnx-jni` + `onnxruntime`）

跨平台打包（把全部平台打进一个 fat jar）时显式加 profile：

```bash
mvn -Psherpa-native-all clean package
```

### 2. 准备模型

模型不走 Maven 坐标，放在任意目录即可。推荐用系统属性统一指定根目录，每个模型一个子目录：

```bash
-Dsherpa.onnx.model.dir=/opt/models/sherpa
```

```
/opt/models/sherpa
├── sensevoice/            # model.int8.onnx + tokens.txt
├── paraformer-zh/         # model.int8.onnx + tokens.txt
├── whisper-tiny/          # tiny-encoder.int8.onnx + tiny-decoder.int8.onnx + tiny-tokens.txt
├── streaming-zipformer/   # encoder/decoder/joiner.int8.onnx + tokens.txt
├── vits-zh/               # model.onnx + tokens.txt + lexicon.txt + speakers.txt
└── silero_vad.onnx
```

模型来源：`https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models` 与
`sherpa-onnx` 官方 HuggingFace 仓库；国内可用 `hf-mirror.com` 镜像。

### 3. 语音识别（离线）

```java
try (SherpaRecognizer asr = SherpaRecognizer.builder()
        .model("/opt/models/sherpa/sensevoice")   // 或模型名 "sensevoice"
        .vad("/opt/models/sherpa/silero_vad.onnx") // 可选：VAD 分段，长音频更稳
        .build()) {

    SherpaRecognitionResult result = asr.recognize(Path.of("a.wav"));
    System.out.println(result.getText());
    result.getSegments().forEach(s ->
            System.out.printf("[%.2f-%.2f] %s%n", s.getStart(), s.getEnd(), s.getText()));
    System.out.println(result.getLanguage() + " / " + result.getEmotion() + " / " + result.getEvents());
}
```

模型族（`PARAFORMER` / `SENSEVOICE` / `WHISPER` / `TRANSDUCER` / `ZIPFORMER_CTC`）默认按目录内
文件命名自动判定；命名不规范时可用 `类型:路径` 显式指定：

```java
.model("sensevoice:/opt/models/whatever")
```

### 4. 语音识别（流式 / 麦克风）

```java
try (SherpaStreamingRecognizer asr = SherpaStreamingRecognizer.builder()
        .model("/opt/models/sherpa/streaming-zipformer")
        .endpointEnabled(true)
        .endpointTrailingSilence(1.0f)
        .build()) {

    while (recording) {
        asr.accept(mic.readChunk());        // float[]，建议 1600~3200 采样（100~200ms）
        String delta = asr.partialText();   // 增量文本
        if (!delta.isEmpty()) {
            System.out.print(delta);
        }
        if (asr.isEndpoint()) {             // 一句话说完
            asr.reset();                    // 开始下一句
        }
    }
    String finalText = asr.finish();
}
```

### 5. 语音合成

```java
try (SherpaSynthesizer tts = SherpaSynthesizer.builder()
        .model("/opt/models/sherpa/vits-zh")
        .speaker(3)          // 多说话人模型可指定音色
        .speed(1.0f)
        .build()) {

    byte[] wav = tts.synthesizeToWav("你好，世界");            // 16-bit mono PCM WAV
    tts.synthesizeToFile("落盘", Path.of("out.wav"));
    System.out.println("采样率 " + tts.sampleRate() + "，说话人数 " + tts.numSpeakers());
}
```

模型族按目录名/文件判定（`vits` / `matcha` / `kokoro`），也可 `model("kokoro:/path")` 显式指定。

### 6. 统一 SPI 门面

模块已登记到现有 AI 能力 SPI，可与云端 ASR/TTS 共用同一套调用方式：

```java
// 语音识别（别名 sherpa / sherpa-onnx / sherpa-asr）
String text = VirtualClient.create("sherpa", "")
        .model("sensevoice:/opt/models/sherpa/sensevoice")
        .transcribe(Path.of("a.wav"));

// 语音合成（别名 sherpa / sherpa-onnx / sherpa-tts）
byte[] wav = TextToAudioClient.create("sherpa", "")
        .model("/opt/models/sherpa/vits-zh")
        .voice("3")
        .synthesize("你好世界");
```

---

## 配置项

| 系统属性 | 说明 | 默认 |
|---|---|---|
| `sherpa.onnx.model.dir` | 模型根目录（每个模型一个子目录） | 无 |
| `sherpa.onnx.num-threads` | 推理线程数 | `min(4, CPU/2)` |
| `sherpa.onnx.provider` | 推理 provider（`cpu` / `cuda`，取决于 native 编译选项） | `cpu` |
| `sherpa.onnx.register-models` | 是否把模型登记进 `ModelRegistry`（见下方说明） | `false` |
| `sherpa_onnx.native.path` | 指定同时含 `sherpa-onnx-jni` 与 `onnxruntime` 的目录（官方支持） | 无 |
| `java.library.path` | 同上，JVM 级回落 | 无 |

> **为什么 `register-models` 默认关闭**：本模块没有 `ITranslator` 实现（推理在原生层），
> 而 `ModelRegistry` 会把 `.onnx` 条目按扩展名归到 `onnx` 引擎名下——一旦登记，这些条目
> 会混入 onnx 的模型清单，被选中时因缺 translator 而失败。默认只做「目录枚举」：
> 两个 SPI 客户端的 `models()` 直接扫描 `sherpa.onnx.model.dir`，既准确又不污染其它引擎。

native 库查找顺序（由官方 `LibraryUtils` 决定）：`sherpa_onnx.native.path` → jar 内
`sherpa-onnx/native/<os-arch>/` 资源 → `java.library.path`。

---

## 关键类

| 类 | 说明 |
|---|---|
| `SherpaRecognizer` | 离线识别器（Paraformer / SenseVoice / Whisper / Transducer / Zipformer-CTC） |
| `SherpaStreamingRecognizer` | 流式识别器（增量喂入 + 端点检测） |
| `SherpaSynthesizer` | 语音合成器（VITS / Matcha / Kokoro） |
| `SherpaVadSegmenter` | Silero VAD 分段器（长音频切分 / 端点检测） |
| `SherpaModelResolver` | 模型目录解析 + 模型族自动判定 + 官方配置组装 |
| `SherpaWavUtils` | WAV 解码、声道合并、重采样、WAV 封装（仅 JDK，无额外依赖） |
| `SherpaNativeLoader` | 原生库加载与失败原因翻译 |
| `SherpaAudioClient` / `SherpaTextToAudioClient` | 现有 `VirtualClient` / `TextToAudioClient` SPI 适配 |
| `SherpaModelRegistrar` | 向 `ModelRegistry` 登记常用模型元数据与下载地址 |

---

## 依赖关系

```
utils-support-deeplearning-sherpa-starter
├── utils-support-common-starter          (SPI / AI 能力接口 / NativeLoader)
├── utils-support-deeplearning-starter    (ModelRegistry / 能力标签)
├── com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:v1.13.8
└── sherpa-onnx-native-lib-<os-arch>:v1.13.8   (按平台自动激活)
```

---

## 排障

**`SherpaException: sherpa-onnx 原生库加载失败（平台 win-x64）`**
1. 确认依赖树里有 `sherpa-onnx-native-lib-win-x64`（`mvn dependency:tree | findstr sherpa`）；
2. 或 `-Dsherpa_onnx.native.path=<目录>` 指向含 `sherpa-onnx-jni.dll` + `onnxruntime.dll` 的目录；
3. Linux 下需确认 `libsherpa-onnx-jni.so` 与 `libonnxruntime.so` 在同一目录。

**`无法判定模型族`**
目录内既没有 `encoder`/`decoder`/`joiner` 三件套，目录名也看不出族名。用
`model("sensevoice:/path")` 显式指定，或把目录名改成官方模型名。

**识别结果为空**
先确认采样率（本模块统一按 16kHz 处理并自动重采样），再确认模型与语言匹配
（SenseVoice 用 `language("zh")`，Whisper 用 `language("en")`）；长音频建议开 VAD。
