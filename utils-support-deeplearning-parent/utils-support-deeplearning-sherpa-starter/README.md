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
├── sensevoice/            # 离线：model.int8.onnx + tokens.txt
├── paraformer-zh/         # 离线：model.int8.onnx + tokens.txt
├── whisper-tiny/          # 离线：tiny-encoder/decoder.int8.onnx + tiny-tokens.txt
├── streaming-zipformer/   # 实时：encoder/decoder/joiner.int8.onnx + tokens.txt
├── vits-zh/               # 合成：model.onnx + tokens.txt + lexicon.txt + speakers.txt
└── silero_vad.onnx
```

#### ⚠️ 离线模型 vs 实时（流式）模型 —— 必须选对

sherpa-onnx 的模型在**导出阶段**就分成了两类，**不可互换**：

| 你的场景 | 用哪类模型 | 驱动类 | 模型标识前缀 |
|---|---|---|---|
| 录音文件转写（要准确率） | **离线（offline）**：Paraformer / SenseVoice / Whisper / 离线 Zipformer | `SherpaAsrPipeline`、`SherpaRecognizer` | `sherpa-offline-*` |
| 麦克风实时识别（要低延迟） | **实时（streaming）**：名字带 `streaming` 的 Zipformer | `SherpaStreamingRecognizer` | `sherpa-streaming-*` |
| 文本转语音 | **合成**：VITS / Matcha / Kokoro | `SherpaSynthesizer`、`SherpaTextToAudioClient` | `sherpa-tts-*` |

**判别口诀**：目录名／官方仓库名里带 `streaming` → 实时模型；否则是离线模型。

**不要只看文件名**：两类模型的目录里都可能是
`encoder-epoch-99-avg-1.int8.onnx` / `decoder-...` / `joiner-...`，文件名完全一样。

**选错的后果**：把流式模型交给离线识别器，sherpa-onnx 在原生层会创建失败或输出乱码。
本模块已在 `SherpaRecognizer` 里按目录名做了拦截并提示改用 `SherpaStreamingRecognizer`。

**能力差异**：

| | 离线 | 实时（流式） |
|---|---|---|
| 解码方式 | 整段音频一次解码 | 按 chunk 增量解码，边喂边出字 |
| 上下文 | 全句可见，准确率更高 | 每 chunk 只见有限上下文，准确率略低 |
| 延迟 | 需等音频结束 | 100~200ms 级 |
| 典型模型 | SenseVoice / Paraformer / Whisper | Zipformer-Transducer（streaming） |

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
| `SherpaAsrPipeline` | **一站式门面**：解码 → VAD/切块 → 推理 → 纠错 → 后处理（推荐入口） |
| `SherpaRecognizer` | 离线识别器（Paraformer / SenseVoice / Whisper / Transducer / Zipformer-CTC） |
| `SherpaStreamingRecognizer` | 流式识别器（增量喂入 + 端点检测） |
| `SherpaSynthesizer` | 语音合成器（VITS / Matcha / Kokoro / Pocket-TTS） |
| `SherpaVadSegmenter` | Silero VAD 分段器（长音频切分 / 端点检测） |
| `SherpaModelResolver` | 模型目录解析 + 模型族自动判定 + 官方配置组装 |
| `SherpaWavUtils` | WAV 解码、声道合并、重采样、WAV 封装（仅 JDK，无额外依赖） |
| `SherpaNativeLoader` | 原生库加载与失败原因翻译 |
| `SherpaAudioClient` / `SherpaTextToAudioClient` | 现有 `VirtualClient` / `TextToAudioClient` SPI 适配 |
| `SherpaModelRegistrar` | 向 `ModelRegistry` 登记常用模型元数据与下载地址 |

---

## 门面用法（推荐入口）

`SherpaAsrPipeline` 与 onnx 模块的 `AsrPipeline` 同定位，把「解码 → 分段 → 推理 → 拼接 → 后处理」
合成一次调用：

```java
try (SherpaAsrPipeline asr = SherpaAsrPipeline.builder()
        .model("/opt/models/sherpa/sensevoice")
        .vadModel("/opt/models/sherpa/silero_vad.onnx")   // 可选；长音频强烈建议
        .language("zh")
        .maxSegmentSeconds(28F)                            // 无 VAD 时的切块上限
        .postProcess(true)                                 // 压缩空白 + 英文句首大写
        .build()) {

    String text = asr.transcribe(Path.of("meeting.wav"));

    SherpaRecognitionResult detail = asr.recognize(Path.of("meeting.wav"));
    detail.getSegments().forEach(s ->
            System.out.printf("[%.2f-%.2f] %s%n", s.getStart(), s.getEnd(), s.getText()));

    // 也可直接转成标准异步任务响应，复用上层「识别任务」模型
    AudioResponse resp = asr.recognizeAsResponse(Path.of("meeting.wav"), "task-1");
}
```

分段策略：**配了 VAD** → 按语音段切分（时间戳用真实采样偏移，段间静音不累积误差）；
**没配 VAD 但音频不超上限** → 整段一次识别；**没配 VAD 且超长** → 按上限切块并累加偏移。

---

## 验收

### 自动化测试

模块自带 3 个测试类共 23 个用例，分「契约层」与「推理层」两类：

```bash
# 仅契约层（无需模型/无需下载，CI 可直接跑）
mvn test -f utils-support-parent-starter/utils-support-deeplearning-parent/utils-support-deeplearning-sherpa-starter/pom.xml

# 含真实推理（需先备好模型，见下）
mvn test -f .../utils-support-deeplearning-sherpa-starter/pom.xml \
    -Dsherpa.onnx.model.dir=<模块>/.acceptance-models/sensevoice
```

未提供 `-Dsherpa.onnx.model.dir` 时，推理层用例会 `Assumptions.assumeTrue` **显式跳过**
（报告中计入 Skipped，不会伪装成通过）。

### 本地真实模型验收（已实测通过）

把模型放到模块下 `.acceptance-models/`（已 gitignore，约定见该目录注释）：

```
.acceptance-models/
├── sensevoice/       model.int8.onnx + tokens.txt          （离线识别，~228MB）
├── zipformer-zh-en/  encoder/decoder/joiner.int8.onnx + tokens.txt（流式识别，~193MB）
└── vits-zh/          model.onnx + tokens.txt + lexicon.txt + speakers.txt（TTS，~31MB）
```

实测结果（Windows 10 / JDK 25 / sherpa-onnx v1.13.8 native win-x64）：

| 场景 | 结果 |
|---|---|
| **中文 → 音频（TTS）** | VITS-icefall-zh：`sampleRate=8000`、`numSpeakers=174`；Kokoro-multi-lang-zh：`sampleRate=24000`、`numSpeakers=54` |
| **音频 → 中文（ASR）** | SenseVoice：已知文本合成语音 5.01s → `今天天气真不错，我们一起去公园散步吧。`，**17/17 字符全对**，语种 `zh`、情感 `NEUTRAL`、事件 `Speech`，耗时 2.6s |
| **双向闭环（中文→音频→中文）** | 两族平均召回 89.0% / 88.9%，详见下方「闭环可懂度」 |
| 离线识别（SAPI 真人级合成音对照） | 同一句 `语音识别与语音合成是两项不同的技术。` **100% 正确**，证明 ASR 链路本身无问题 |
| 流式识别（Zipformer-Transducer） | 增量喂入同一音频可出文本；整段离线回放时会出现相邻重复（`公公园散散步`）——这是**离线音频整段塞进流式模型**的边界效应，麦克风实时输入 + 端点重置不会触发 |
| 自动化用例 | `mvn test -Dsherpa.onnx.model.dir=...` → **Tests run: 43, Failures: 0, Errors: 0, Skipped: 0** |
| 中文纠错（macbert4csc） | `因该→应该`、`讨轮→讨论`、`语因→语言`（阈值 0.3）均能修复；正确文本不误改 |

### 闭环可懂度（中文 → TTS → ASR → 比对）

同一批 5 句中文，两个 TTS 模型族各自做「文本 → 音频 → 文本」闭环
（`noiseScale=0` 关闭随机噪声以保证可复现）：

| 句子 | VITS-icefall-zh（8kHz） | Kokoro-multi-lang（24kHz） |
|---|---|---|
| 今天天气真不错，我们一起去公园散步吧。 | 100% | 100% |
| 请把这份文件转交给财务部门。 | 100% | 100% |
| 明天上午九点在三楼会议室召开项目评审会。 | 89.5%（评审→庭审） | 94.7% |
| 语音识别与语音合成是两项不同的技术。 | 82.4% | 76.5% |
| 中华人民共和国成立于一九四九年。 | 73.3%（数字转 1949，ITN 正常行为） | 73.3% |
| **平均** | **89.0%** | **88.9%** |

> **结论修正**：两个族的可懂度**基本持平**（89.0% vs 88.9%）。
> 也就是说，8kHz 采样率<b>并不是</b>这条链路的瓶颈——之前"换 16kHz+ 就能明显改善"
> 的判断缺乏数据支撑，已更正。残余误差主要来自中文同音字（评审/庭审、合成/和城市），
> 属 ASR 模型能力，与 TTS 采样率无强相关。

> **重要**：VITS 默认 `noise_scale=0.667` 会注入随机噪声，同一文本每次合成的音频都不同，
> 可懂度也随之波动（实测未关噪声时同一模型均值仅 76%，关噪声后稳定在 89%）。
> 需要可复现结果时请设 `noiseScale(0)`。

### 模型选型实测对照

| 模型族 | 采样率 | 说话人数 | 额外依赖 | 闭环平均召回 |
|---|---|---|---|---|
| VITS-icefall-zh | 8000 | 174 | tokens/lexicon | 89.0% |
| Kokoro-multi-lang-zh | 24000 | 54 | 需 `espeak-ng-data/`（含 phondata/phontab/phonindex/cmn_dict） | 88.9% |
| Pocket-TTS | 24000 | 1 | 需 `lm_flow`+`lm_main`+`decoder`+**`text_conditioner`** | 本地模型包缺 text_conditioner，无法验收 |

> **Kokoro 必读**：缺 `espeak-ng-data/` 时 sherpa-onnx 原生层只报
> `Invalid OfflineTtsConfig`，非常难排查。本模块已提前拦截并提示补该目录。
> **Pocket-TTS 必读**：官方 sherpa-onnx 导出必须带 `text_conditioner`，
> 缺失时报 `Please provide --pocket-text-conditioner`。



---

## 中文纠错（复用 OCR 侧 macbert4csc）

ASR 的同音字误判（实测「评审→庭审」「语音→语因」）与 OCR 的错别字是同类问题，
因此本模块**直接复用 OCR 管线已在用的 `macbert4csc` 纠错模型**，签名与
`OcrPipeline.Builder.corrector(String)` 完全一致：

```java
try (SherpaAsrPipeline asr = SherpaAsrPipeline.builder()
        .model("/models/sensevoice")
        .language("zh")
        .corrector("macbert4csc")   // 可选：留空则关闭
        .build()) {
    String text = asr.transcribe(wav);   // 已纠错
}
```

### 三个必须知道的点

**1. 依赖是 optional，需宿主显式引入**

纠错翻译器与注册器都在 `utils-support-deeplearning-onnx-starter` 里，本模块把它声明为
`<optional>true</optional>`：只用 ASR/TTS 的宿主不必被 onnxruntime/DJL 的依赖树绑架。
需要纠错时由宿主显式引入 onnx-starter + macbert4csc 模型包，否则自动降级为不纠错。

**2. 默认阈值对 ASR 不适配，本模块已自动下调**

纠错模型默认阈值 `0.7` 是为 OCR 错别字调的；ASR 同音字的纠正置信度普遍更低。
实测同一句 `语音识别语因和城市…`：

| 阈值 | 结果 |
|---|---|
| 0.7（模型默认） | `语因` **不改** —— 表现像纠错没生效 |
| 0.3（本模块 ASR 默认） | `语因` → `语言` ✅ |

因此 `SherpaAsrPipeline` 未显式指定时用 `0.3`，并可用 `correctorThreshold(float)` 调整。
注意：该阈值通过临时改写系统属性 `deeplearning.csc.threshold` 传递，**调用后立即恢复**，
不会影响同进程内 OCR 链路的既有行为。

**3. 纠错能力有边界**

实测 `我今天因该早点回家 → 我今天应该早点回家`、`讨轮 → 讨论` 均能修复；
但 `语音识别原因和城市…` 这类「原因」本身是合法词的场景，模型判定无需修改。
纠错是**减少**同音字错误，不能替代更高精度的 ASR 模型。

### 自检

```java
// 配置了 corrector 但模型未就绪时会静默降级，用这个显式确认
ITranslator<?, ?> c = asr.peekCorrector();   // null 表示不可用
```

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
