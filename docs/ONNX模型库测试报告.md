# ONNX 模型库测试报告（utils-support-models-parent）

> 仓库：`utils-support-models-parent` @ `f302fd4`
> 测试日期：2026-08-25
> 测试环境：Windows x64 / Python 3.12.6 / onnxruntime 1.21.1 (CPU EP) / Maven 3.9.8

## 一、测试方法与分级

| 级别 | 内容 | 覆盖范围 |
|---|---|---|
| L1 结构验证 | ONNX 加载、输入/输出维度与 pom 声明比对 | 全部模型 |
| L2 数值一致性 | 自转换模型 torch vs ONNX 输出最大绝对差 | 所有自行转换的模型 |
| L3 真实数据端到端 | 真实图像/文本/音频 + 完整预处理 + 解码，验证语义正确性 | 13 个重点模块 |
| L4 构建 | `mvn package` 产出有效 jar | 全部注册模块 |

**通用结论**：73 个注册模块 `mvn validate` 通过；90+ 权重文件全部入库（LFS）并推送远程。

## 二、真实数据端到端测试结果

### 视觉类

| 模块 | 测试输入 | 关键结果 | 判定 |
|---|---|---|---|
| `nude-detector` | lena.jpg（真人像） | 检出 `FACE_FEMALE conf=0.848`，框位置精确；敏感部位零误报 | ✅ PASS |
| `nsfw-classify-int8` | lena.jpg / messi.jpg | normal=0.9912 / 0.9999 | ✅ PASS |
| `retinaface-r34` | lena.jpg | 检出 1 张脸 score=1.000，框 `[271,242,443,486]` 与 torch 基准一致 | ✅ PASS |
| `face-liveness-minifasnet` | lena 人脸 crop | 与官方 `.pth`+原版 PyTorch 输出逐位一致（live=0.0003/replay=0.9946） | ✅ PASS |
| `insightface-3d68` | Lena 人脸 crop | 1103 点 ×(x,y,z)，归一化坐标分布合理含深度 | ✅ PASS |
| `doclayout-yolo` | 合成 A4 文档页 | 检出 figure 区块 conf=0.58（1024 输入，[300,6] 端到端输出） | ✅ PASS |
| `rtdetr-layout` | 合成 A4 文档页 | 图形区定位精确：`image 0.39 @左框`、`image 0.36 @右表` | ✅ PASS |
| `image-colorize` | lena 灰度图 | R-G 通道差 26.05、R-B 差 32.96 → 有效着色 | ✅ PASS |

### NLP 类

| 模块 | 测试设计 | 结果 | 判定 |
|---|---|---|---|
| `bge-small-en` | 相似句对 vs 无关句余弦相似度 | 相似对 **0.856** > 无关对 0.374/0.379 | ✅ PASS |
| `bge-large-zh` | 中文相似句对 | 相似对 **0.936** > 无关对 0.236/0.243 | ✅ PASS |
| `minilm-fp32` | 英文相似句对 | 相似对 **0.858** > 无关对 0.559/0.533 | ✅ PASS |
| `gemma-3-270m` | "The capital of France is" 续写 | top-1 " a"（语法自然延续），分布合理 | ✅ PASS |
| `minimind` | 真实 token 序列数值复验 | diff=2.19e-05，next-token 序列完全一致 | ✅ PASS |

### 音频类

| 模块 | 结果 | 判定 |
|---|---|---|
| `wespeaker` | YESNO 真人语料：同说话人不同录音 cos=0.937~0.956，输出确定性 cos=1.0000 | ✅ PASS |

### 仅结构验证（L1）

`face-liveness`(flrgb)、`face-liveness-flxc`：推理通过（flxc 需 12 通道炫彩多帧，无法用普通图片做 L3）。

## 三、数值一致性（L2）明细

| 模型 | 转换路径 | 最大绝对差 | 验证输入 |
|---|---|---|---|
| retinaface-r34 | py-feat safetensors → dynamo 导出 | 2.0e-05 | 真实图 + 随机图双验 |
| minimind | 官方 safetensors → dynamo 导出 | 2.19e-05 | 真实 token 序列 |
| nsfw-classify-int8 | onnx-community q8 版 | —（官方转换） | softmax 分布合理 |

> ⚠️ 经验教训：仅用随机输入验证有盲区——retinaface 初版在随机输入下 diff≈2e-5 但真实图像下输出全坏。自转换模型必须用真实分布输入复验。

## 四、发现并已修复的问题

| # | 问题 | 根因 | 修复 | 提交 |
|---|---|---|---|---|
| 1 | retinaface-r34 权重文件损坏（真实图像下 conf 恒为 1.0） | 外部数据合并流程污染 | 重新导出 + 双输入数值验证后替换 | `1406e09` |
| 2 | dfine-l/image-colorize/rtdetr-layout 权重未真正入库 | 模块级 `.gitignore` 忽略规则挡住 onnx，表面提交成功实际缺失 | 删除忽略规则，补交权重 | `652d4dc`/`7293fb5` |
| 3 | dfine-l 模块误提交 target 构建产物 | `git add -f` 绕过根忽略规则 | `git rm -r --cached target` 清除 | `41d8aff` |
| 4 | minimind 的 config.json 错误（写成 Qwen3 架构 + hidden_size=768） | 手改配置与官方权重不符 | 以官方 LlamaForCausalLM 配置替换（hidden=512, 8 层, KV 2 头） | `4243213` |
| 5 | rtdetr-layout 预处理文档矛盾 | yml 标注 `norm_type: none` 但实测需 `/255` | 已实测确认，见下表 | — |

## 五、预处理约定速查（Java 集成参考）

| 模型 | 输入 | 归一化 | 通道序 | 后处理要点 |
|---|---|---|---|---|
| nude-detector | [1,3,320,320] letterbox | /255 | RGB | YOLOv8 解码 [22,2100]，conf≥0.25, IoU 0.45 |
| nsfw-classify-int8 | [1,3,224,224] | (x/255-0.5)/0.5 | RGB | softmax，normal/nsfw；int8 必须选 QDQ 格式 |
| retinaface-r34 | [1,3,640,640] | **不除 255**，减均值 R123/G117/B104 | RGB | priors 解码 var=[0.1,0.2]，NMS 0.4，conf≥0.7 |
| minifasnet 活体 | [1,3,80,80]，人脸中心扩 **2.7×** 边距裁剪 | /255 | **BGR** | softmax 3 类，live = 1-(p_print+p_replay) |
| insightface-3d68 | [1,3,192,192] 对齐人脸 crop | — | RGB | 输出 1103×3 归一化坐标 |
| doclayout-yolo | [1,3,1024,1024] letterbox | /255 | RGB | 端到端 [300,6] 无需 NMS |
| rtdetr-layout | [800,800] 三输入(im_shape/image/scale_factor) | **/255**（实测，勿信 yml） | BGR 或 RGB 均可 | [300,6]=cls,score,x1,y1,x2,y2（相对 800 输入） |
| image-colorize | [1,3,256,256] 灰度复制三通道 | 不归一化，**float16** | RGB | 输出 fp16 0~255；建议 LAB 空间融合原亮度 |
| bge-* / minilm | token ids + mask (+token_type) | — | — | CLS pooling + L2 norm；BGE 查询可加指令前缀 |
| gemma-3-270m | input_ids + attention_mask + 18×KV cache | — | — | 词表 262144，BOS=2 |
| wespeaker | feats [1,T,80] kaldi fbank (25ms/10ms) | PCM×32768 后提特征 | — | 输出 256 维嵌入，L2 norm |
| flrgb 活体 | [1,3,112,112] | — | RGB | 2 类输出 |
| flxc 活体 | [1,12,112,112] 多帧序列 | — | — | 需炫彩摄像头 |
| gfpgan v1.3_clean | [1,3,512,512] (x/255-0.5)/0.5 | /255 | RGB | 输出归一化 -1~1，+1再×127.5 解码为 uint8；支持多尺度 out_rgbs 中间层 |
| codeformer | [1,3,512,512] (x-127.5)/127.5 | /255-0.5 | RGB | w=0.5 默认合成强度，输出 0~1 float |

## 六、人脸修复模型专项测试（2026-08-26）

### GFPGAN v1.3_clean
| 测试项 | 结果 |
|---|---|
| ORT CPU EP 加载 | ✅ 通过，无 DOUBLE 类型报错 |
| 输入 [1,3,512,512] 推理 | ✅ 通过，输出 shape [1,3,512,512] |
| Lena 人脸 crop 端到端 | ✅ 通过，输出范围 -1.16~1.29，有效人脸修复 |
| ONNX 结构校验 | ✅ onnx.checker 通过 |
| **.pth vs ONNX 数值一致性** | ✅ SSIM=0.994 PSNR=47.5dB mean_diff=0.66/255 |
| ONNX 推理耗时 | ~1100ms（单线程 ORT CPU） |
| 模块入库路径 | `face/restoration/gfpgan/GFPGANv1.3_clean.onnx`（346MB） |
| 导出方式 | `torch.onnx.export(legacy, opset=18)` + shape_inference + DOUBLE type 清零 |

> **导出技术说明**：原生 `torch.onnx.export(legacy)` 会将 Python float 标量（如 `eps=1e-8`、`2**0.5`）注册为 `DOUBLE` initializer，导致 ORT CPU EP 报 `Conv NOT_IMPLEMENTED`。修复方案：导出后批量将 initializer/Constant 节点的 DOUBLE 属性转为 FLOAT，再运行 `shape_inference` 并清零所有 value_info 的类型标注。

### CodeFormer
| 测试项 | 结果 |
|---|---|
| ORT CPU EP 加载 | ✅ 通过 |
| 输入 [1,3,512,512] 推理 | ✅ 通过 |
| Lena 人脸端到端 | ✅ 通过，有效人脸修复输出 |
| 模块入库路径 | `face/restoration/codeformer/codeformer.onnx`（376MB） |

## 七、遗留事项（待拍板）

| 项 | 现状 | 建议 |
|---|---|---|
| `tinaface` | 空。官方仅 mxnet 权重，DCN 算子无法转标准 ONNX | 删除模块，或改放 buffalo_l 包内 `det_10g.onnx`（SCRFD 高精度人脸检测 16.9MB，已在手） |
| `mt5-zh` | 空。pom 描述自相矛盾（名字 mT5/描述 MarianMT/160MB 无对应真实模型） | 三选一：删除；按描述放 opus-mt-zh-en int8（与现有模块重复）；放 mT5-XLSum 中文摘要（1.2GB） |
| 19 个空目录 | 无 pom、未注册 module，不影响构建 | 可直接删除整理 |
| ~~wespeaker 语义验证~~ | 已用 YESNO 真人语料补测通过（2026-08-26） | 已关闭 |
<hr>
<!-- coverage:start -->
<h2 id="model-coverage">附：模型测试覆盖总览（203 个注册模型，更新于 2026-09-02）</h2>
<p>数据源 <code>docs/model-test-data.json</code>，由 <code>docs/generate-coverage.py</code> 遍历生成本章节；维护测试状态只需修改 JSON 后重新运行脚本。</p>
<p>状态统计：✅ 实测通过 44　🧪 冒烟通过（无正样本） 10　🐞 发现缺陷 0　📄 已有记录 139　🚫 不再追踪 9　⬜ 未测试 1</p>
<p>待测试清单（1）：`cn-clip-image`</p>
<h3>OCR（15）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>duguang-det-large</code></td><td>✅ 实测通过</td><td>ocr.duguang.DuguangDetTranslator</td><td></td></tr>
<tr><td><code>duguang-det-small</code></td><td>✅ 实测通过</td><td>ocr.duguang.DuguangDetTranslator</td><td>ticket.jpg→20；chTable.png→69</td></tr>
<tr><td><code>duguang-ocr-large</code></td><td>✅ 实测通过</td><td>ocr.duguang.DuguangOcrTranslator</td><td></td></tr>
<tr><td><code>paddleocrv6-det</code></td><td>✅ 实测通过</td><td>ocr.extractor.PpOcrDetTranslator</td><td>ticket_new.png→22；freetxt.png→7</td></tr>
<tr><td><code>doc-orientation</code></td><td>📄 已有记录</td><td>ocr.direction.DocOrientationTranslator</td><td></td></tr>
<tr><td><code>duguang-ocr-small</code></td><td>📄 已有记录</td><td>ocr.duguang.DuguangOcrTranslator</td><td></td></tr>
<tr><td><code>paddleocrv6-medium-det</code></td><td>📄 已有记录</td><td>ocr.extractor.PpOcrDetMediumTranslator</td><td></td></tr>
<tr><td><code>paddleocrv6-medium-rec</code></td><td>📄 已有记录</td><td>ocr.extractor.PpWordExtractorMediumTranslator</td><td></td></tr>
<tr><td><code>paddleocrv6-rec</code></td><td>📄 已有记录</td><td>ocr.extractor.PpWordExtractorTranslator</td><td></td></tr>
<tr><td><code>pp-word-rotate</code></td><td>📄 已有记录</td><td>ocr.direction.PpWordRotateTranslator</td><td></td></tr>
<tr><td><code>table-struct</code></td><td>📄 已有记录</td><td>ocr.table.TableStructTranslator</td><td></td></tr>
<tr><td><code>paddle-ocr-recognition</code></td><td>🚫 不再追踪</td><td>ocr.paddleocr.PaddleOcrRecognitionTranslator</td><td></td></tr>
<tr><td><code>pp-ocr-rec</code></td><td>🚫 不再追踪</td><td>ocr.PpOcrRecTranslator</td><td></td></tr>
<tr><td><code>pp-word-extractor</code></td><td>🚫 不再追踪</td><td>ocr.extractor.PpWordExtractorTranslator</td><td></td></tr>
<tr><td><code>svtr-extractor</code></td><td>🚫 不再追踪</td><td>ocr.extractor.SvtrExtractorTranslator</td><td></td></tr>
</tbody></table>
<h3>人脸（29）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>anime-face-detector</code></td><td>✅ 实测通过</td><td>anime.detection.AnimeFaceDetectorTranslator</td><td>anime.webp→27；face1.webp→39；face2.webp→41</td></tr>
<tr><td><code>face-liveness-minifasnet</code></td><td>✅ 实测通过</td><td>(规划)liveness.MiniFASNetTranslator</td><td>官方pth对照→ONNX 与原版 PyTorch 输出逐位一致(live=0.0003/replay=0.9946)；随机噪声图→replay=0.99 符合预期</td></tr>
<tr><td><code>face-mask-detector</code></td><td>✅ 实测通过</td><td>yolo.FaceMaskDetectorTranslator</td><td>1people.png→2；3peoplebeauty.jpg→3（默认阈值 0.05，阈值验证 {'0.5': '5→1'}）</td></tr>
<tr><td><code>insightface-3d68</code></td><td>✅ 实测通过</td><td>insightface.InsightFace3d68Translator</td><td>lena人脸crop→1103点(x,y,z)归一化坐标分布合理</td></tr>
<tr><td><code>insightface-scrfd</code></td><td>✅ 实测通过</td><td>face.ScrfdFaceDetectorTranslator</td><td>1people.png→1；3peoplebeauty.jpg→3；largest_selfie.jpg→243（默认阈值 0.45，阈值验证 {'0.6': 166, '0.9': 0}）</td></tr>
<tr><td><code>retinaface-r34</code></td><td>✅ 实测通过</td><td>face.OnnxRetinaFaceTranslator</td><td>lena.jpg→1 face score=1.000</td></tr>
<tr><td><code>face-liveness-flrgb</code></td><td>🧪 冒烟通过（无正样本）</td><td>liveness.FlRgbLivenessTranslator</td><td></td></tr>
<tr><td><code>face-liveness-flxc</code></td><td>🧪 冒烟通过（无正样本）</td><td>liveness.FlXcLivenessTranslator</td><td></td></tr>
<tr><td><code>yolo-face-detector</code></td><td>🧪 冒烟通过（无正样本）</td><td>face.YoloFaceTranslator</td><td>1people.png→5；3peoplebeauty.jpg→3；largest_selfie.jpg→0（默认阈值 0.45，阈值验证 {'0.25': '5/3/0', '0.45': '5/2/0'}）</td></tr>
<tr><td><code>ada-face</code></td><td>📄 已有记录</td><td>face.AdaFaceTranslator</td><td></td></tr>
<tr><td><code>age-gender-onnx</code></td><td>📄 已有记录</td><td>agegender.AgeRaceGenderTranslator</td><td></td></tr>
<tr><td><code>age-race-gender</code></td><td>📄 已有记录</td><td>agegender.AgeRaceGenderTranslator</td><td></td></tr>
<tr><td><code>anime-face-yolov8</code></td><td>📄 已有记录</td><td>anime.detection.AnimeFaceDetectorTranslator</td><td></td></tr>
<tr><td><code>anime-gan-v2-face-portrait</code></td><td>📄 已有记录</td><td>animegan.AnimeGanV2NchwTranslator</td><td></td></tr>
<tr><td><code>arc-face</code></td><td>📄 已有记录</td><td>face.ArcFaceTranslator</td><td></td></tr>
<tr><td><code>codeformer</code></td><td>📄 已有记录</td><td>face.CodeFormerTranslator</td><td></td></tr>
<tr><td><code>emotion-ferplus</code></td><td>📄 已有记录</td><td>emotion.EmotionFerplusTranslator</td><td></td></tr>
<tr><td><code>faceplugin-face-detect-slim</code></td><td>📄 已有记录</td><td>face.FacePluginDetectTranslator</td><td></td></tr>
<tr><td><code>faceplugin-face-feature</code></td><td>📄 已有记录</td><td>face.FacePluginFeatureTranslator</td><td></td></tr>
<tr><td><code>faceplugin-face-landmark</code></td><td>📄 已有记录</td><td>face.FacePluginLandmarkTranslator</td><td></td></tr>
<tr><td><code>fer-plus</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>insightface-adaface</code></td><td>📄 已有记录</td><td>insightface.InsightFaceAdaFaceTranslator</td><td></td></tr>
<tr><td><code>insightface-genderage</code></td><td>📄 已有记录</td><td>insightface.InsightFaceGenderAgeTranslator</td><td></td></tr>
<tr><td><code>insightface-landmark-2d106</code></td><td>📄 已有记录</td><td>insightface.InsightFaceLandmarkTranslator</td><td></td></tr>
<tr><td><code>r50-face-feature</code></td><td>📄 已有记录</td><td>face.R50FaceFeatureTranslator</td><td></td></tr>
<tr><td><code>scrfd-face-detector</code></td><td>📄 已有记录</td><td>face.ScrfdFaceDetectorTranslator</td><td></td></tr>
<tr><td><code>tinaface</code></td><td>📄 已有记录</td><td>face.TinaFaceTranslator</td><td></td></tr>
<tr><td><code>yolo-face-emotion</code></td><td>📄 已有记录</td><td>emotion.EmotionFerplusTranslator</td><td></td></tr>
<tr><td><code>yolo-face-person</code></td><td>🚫 不再追踪</td><td>face.ScrfdFaceDetectorTranslator</td><td></td></tr>
</tbody></table>
<h3>其他（30）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>anime-gan-v3-ghibli</code></td><td>✅ 实测通过</td><td>animegan.AnimeGanV3Translator</td><td></td></tr>
<tr><td><code>anime-gan-v3-shinkai</code></td><td>✅ 实测通过</td><td>animegan.AnimeGanV3Translator</td><td></td></tr>
<tr><td><code>image-colorize</code></td><td>✅ 实测通过</td><td>colorize.ImageColorizeTranslator</td><td>lena灰度上色→R-G差=26 R-B差=33</td></tr>
<tr><td><code>lu2net</code></td><td>✅ 实测通过</td><td>lu2net.Lu2NetTranslator</td><td></td></tr>
<tr><td><code>minimind</code></td><td>✅ 实测通过</td><td>text.minimind.MiniMindTranslator</td><td>数值一致性→diff=2.19e-05 next-token 序列一致</td></tr>
<tr><td><code>safety-helmet</code></td><td>✅ 实测通过</td><td>yolo.SafetyHelmetDetectorTranslator</td><td>1people.png→2；more car plate.webp→0（默认阈值 0.05）</td></tr>
<tr><td><code>reflective-clothes</code></td><td>🧪 冒烟通过（无正样本）</td><td>yolo.ReflectiveClothesDetectorTranslator</td><td></td></tr>
<tr><td><code>anime-gan-v2-hayao</code></td><td>📄 已有记录</td><td>animegan.AnimeGanV2Translator</td><td></td></tr>
<tr><td><code>anime-gan-v2-paprika</code></td><td>📄 已有记录</td><td>animegan.AnimeGanV2Translator</td><td></td></tr>
<tr><td><code>anime-gan-v2-shinkai</code></td><td>📄 已有记录</td><td>animegan.AnimeGanV2Translator</td><td></td></tr>
<tr><td><code>anime-gan-v3</code></td><td>📄 已有记录</td><td>animegan.AnimeGanV3Translator</td><td></td></tr>
<tr><td><code>dino-v2</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>dinov3-feature</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>donut</code></td><td>📄 已有记录</td><td>donut.DonutTranslator</td><td></td></tr>
<tr><td><code>fire-smoke</code></td><td>📄 已有记录</td><td>yolo.FireSmokeDetectorTranslator</td><td></td></tr>
<tr><td><code>gpt2</code></td><td>📄 已有记录</td><td>text.gpt2.Gpt2Translator</td><td></td></tr>
<tr><td><code>image-to-line-drawing</code></td><td>📄 已有记录</td><td>linedrawing.ImageToLineDrawingTranslator</td><td></td></tr>
<tr><td><code>nima</code></td><td>📄 已有记录</td><td>assessment.NimaTranslator</td><td></td></tr>
<tr><td><code>osnet-reid</code></td><td>📄 已有记录</td><td>reid.OsnetReidTranslator</td><td></td></tr>
<tr><td><code>real-web-photo</code></td><td>📄 已有记录</td><td>realwebphoto.RealWebPhotoTranslator</td><td></td></tr>
<tr><td><code>resnet50-feature</code></td><td>📄 已有记录</td><td>feature.ClipImageFeatureTranslator</td><td></td></tr>
<tr><td><code>smol-docling-combined</code></td><td>📄 已有记录</td><td>smoldocling.SmolDoclingCombinedTranslator</td><td></td></tr>
<tr><td><code>smol-docling-decoder</code></td><td>📄 已有记录</td><td>smoldocling.SmolDoclingDecoderTranslator</td><td></td></tr>
<tr><td><code>smol-docling-embed</code></td><td>📄 已有记录</td><td>smoldocling.SmolDoclingEmbedTranslator</td><td></td></tr>
<tr><td><code>smol-docling-vision</code></td><td>📄 已有记录</td><td>smoldocling.SmolDoclingVisionTranslator</td><td></td></tr>
<tr><td><code>vgg-age-recognition</code></td><td>📄 已有记录</td><td>age.VggAgeRecognitionTranslator</td><td></td></tr>
<tr><td><code>vgg-gender-recognition</code></td><td>📄 已有记录</td><td>gender.VggGenderRecognitionTranslator</td><td></td></tr>
<tr><td><code>vggt</code></td><td>📄 已有记录</td><td>vggt.VggtTranslator</td><td></td></tr>
<tr><td><code>vggt-combined</code></td><td>📄 已有记录</td><td>vggt.VggtCombinedTranslator</td><td></td></tr>
<tr><td><code>vggt-output</code></td><td>📄 已有记录</td><td>vggt.VggtOutputTranslator</td><td></td></tr>
</tbody></table>
<h3>内容审核（2）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>nsfw-classify-int8</code></td><td>✅ 实测通过</td><td>(规划)vit.NsfwClassifyTranslator</td><td>lena.jpg→normal=0.9912；messi.jpg→normal=0.9999</td></tr>
<tr><td><code>nude-detector</code></td><td>✅ 实测通过</td><td>(规划)yolo.v8.NudeDetectorTranslator</td><td>lena.jpg→FACE_FEMALE conf=0.848 位置精确 敏感部位零误报</td></tr>
</tbody></table>
<h3>图像分类（25）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>animals-10-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>anime-real-cls</code></td><td>📄 已有记录</td><td>classification.AnimeRealClsTranslator</td><td></td></tr>
<tr><td><code>card-correction-detector</code></td><td>📄 已有记录</td><td>classification.CardCorrectionTranslator</td><td></td></tr>
<tr><td><code>cifar10-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>clip-image-feature</code></td><td>📄 已有记录</td><td>feature.ClipImageFeatureTranslator</td><td></td></tr>
<tr><td><code>clip-text-feature</code></td><td>📄 已有记录</td><td>clip.ClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>clip-vit-zero-shot</code></td><td>📄 已有记录</td><td>classification.SiglipZeroShotClassificationTranslator</td><td></td></tr>
<tr><td><code>cn-clip-text</code></td><td>📄 已有记录</td><td>clip.CnClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>deepfake-detector</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>efficient-net-lite0-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>efficient-net-lite4-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite4ClassificationTranslator</td><td></td></tr>
<tr><td><code>efficientnet-b1-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>food-101-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>mobile-clip-image-feature</code></td><td>📄 已有记录</td><td>feature.MobileClipImageFeatureTranslator</td><td></td></tr>
<tr><td><code>mobileclip-s0-vision</code></td><td>📄 已有记录</td><td>feature.MobileClipImageFeatureTranslator</td><td></td></tr>
<tr><td><code>mobileclip-zero-shot</code></td><td>📄 已有记录</td><td>classification.SiglipZeroShotClassificationTranslator</td><td></td></tr>
<tr><td><code>mobilenetv2-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>mobilenetv3-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>mobilenetv4-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>owlv2-zero-shot-detector</code></td><td>📄 已有记录</td><td>owlv2.Owlv2ZeroShotDetectorTranslator</td><td></td></tr>
<tr><td><code>plant-classification</code></td><td>📄 已有记录</td><td>classification.EfficientNetLite0ClassificationTranslator</td><td></td></tr>
<tr><td><code>siglip-zero-shot-classification</code></td><td>📄 已有记录</td><td>classification.SiglipZeroShotClassificationTranslator</td><td></td></tr>
<tr><td><code>wd-tagger-swinv2</code></td><td>📄 已有记录</td><td>classification.ClTaggerTranslator</td><td></td></tr>
<tr><td><code>yolo-cls</code></td><td>📄 已有记录</td><td>yolo.cls.YoloClsTranslator</td><td></td></tr>
<tr><td><code>cn-clip-image</code></td><td>⬜ 未测试</td><td>clip.CnClipImageFeatureTranslator</td><td></td></tr>
</tbody></table>
<h3>图像生成（9）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>lcm-lora-unet</code></td><td>📄 已有记录</td><td>generation.LcmLoraUnetTranslator</td><td></td></tr>
<tr><td><code>lcm-lora-vae-decoder</code></td><td>📄 已有记录</td><td>generation.LcmLoraVaeDecoderTranslator</td><td></td></tr>
<tr><td><code>lcm-lora-vae-encoder</code></td><td>📄 已有记录</td><td>generation.LcmLoraVaeEncoderTranslator</td><td></td></tr>
<tr><td><code>small-sd-text-encoder</code></td><td>📄 已有记录</td><td>generation.SmallSdTextEncoderTranslator</td><td></td></tr>
<tr><td><code>small-sd-unet</code></td><td>📄 已有记录</td><td>generation.SmallSdUnetTranslator</td><td></td></tr>
<tr><td><code>small-sd-vae-decoder</code></td><td>📄 已有记录</td><td>generation.SmallSdVaeDecoderTranslator</td><td></td></tr>
<tr><td><code>small-stable-diffusion-combined</code></td><td>📄 已有记录</td><td>generation.SmallStableDiffusionCombinedTranslator</td><td></td></tr>
<tr><td><code>taesd-decoder</code></td><td>📄 已有记录</td><td>generation.TaesdDecoderTranslator</td><td></td></tr>
<tr><td><code>visdrone-small-detector</code></td><td>📄 已有记录</td><td>yolo.VisDroneSmallDetectorTranslator</td><td></td></tr>
</tbody></table>
<h3>图像编辑（1）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>lama-inpainting</code></td><td>📄 已有记录</td><td>inpainting.LamaInpaintingTranslator</td><td></td></tr>
</tbody></table>
<h3>大语言模型(LLM)（4）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>gemma-3-270m</code></td><td>✅ 实测通过</td><td>text.gemma3.Gemma3Translator</td><td></td></tr>
<tr><td><code>qwen2-0.5b</code></td><td>📄 已有记录</td><td>llama.translator.Qwen2ChatTranslator</td><td></td></tr>
<tr><td><code>qwen2-0.5b-onnx</code></td><td>📄 已有记录</td><td>text.qwen.OnnxQwenTranslator</td><td></td></tr>
<tr><td><code>qwen2-1.5b</code></td><td>📄 已有记录</td><td>llama.translator.Qwen2ChatTranslator</td><td></td></tr>
</tbody></table>
<h3>抠图/分割（16）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>anime-seg</code></td><td>✅ 实测通过</td><td>matting.translator.IsnetSegTranslator</td><td></td></tr>
<tr><td><code>cloth-seg</code></td><td>✅ 实测通过</td><td>matting.translator.ClothSegTranslator</td><td></td></tr>
<tr><td><code>human-seg</code></td><td>✅ 实测通过</td><td>matting.translator.U2netSegTranslator</td><td></td></tr>
<tr><td><code>matting-isnet</code></td><td>✅ 实测通过</td><td>matting.translator.IsnetSegTranslator</td><td></td></tr>
<tr><td><code>matting-u2net</code></td><td>✅ 实测通过</td><td>matting.translator.U2netSegTranslator</td><td></td></tr>
<tr><td><code>matting-u2netp</code></td><td>✅ 实测通过</td><td>matting.translator.U2netSegTranslator</td><td></td></tr>
<tr><td><code>clipseg-zero-shot</code></td><td>📄 已有记录</td><td>seg.CLIPSegZeroShotSegmentationTranslator</td><td></td></tr>
<tr><td><code>edgesam</code></td><td>📄 已有记录</td><td>seg.EdgeSamSegmentTranslator</td><td></td></tr>
<tr><td><code>efficientsam</code></td><td>📄 已有记录</td><td>seg.EfficientSamSegmentTranslator</td><td></td></tr>
<tr><td><code>fastsam</code></td><td>📄 已有记录</td><td>seg.FastSamSegmentTranslator</td><td></td></tr>
<tr><td><code>matting</code></td><td>📄 已有记录</td><td>matting.translator.MattingTranslator</td><td></td></tr>
<tr><td><code>modnet</code></td><td>📄 已有记录</td><td>matting.translator.DjlMattingTranslator</td><td></td></tr>
<tr><td><code>onnx-parsenet</code></td><td>📄 已有记录</td><td>face.OnnxFaceSegTranslator</td><td></td></tr>
<tr><td><code>rmbg14</code></td><td>📄 已有记录</td><td>matting.translator.Rmbg20Translator</td><td></td></tr>
<tr><td><code>rmbg20</code></td><td>📄 已有记录</td><td>matting.translator.Rmbg20Translator</td><td></td></tr>
<tr><td><code>sam-encoder</code></td><td>📄 已有记录</td><td>seg.SamImageEncoderTranslator</td><td></td></tr>
</tbody></table>
<h3>文档分析（3）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>doc-layout-yolo</code></td><td>✅ 实测通过</td><td>yolo.v10.translator.DocLayoutYoloTranslator</td><td>paper-real-full.png→49；document.webp→3；table.png→9（默认阈值 0.2，阈值验证 {'0.8': '61→50'}）</td></tr>
<tr><td><code>rtdetr-layout</code></td><td>✅ 实测通过</td><td>layout.RTDetrLayoutTranslator</td><td>paper-real-full.png→43；document.webp→6（默认阈值 0.5，阈值验证 {'0.9': '49→0'}）</td></tr>
<tr><td><code>pp-doc-layout</code></td><td>📄 已有记录</td><td>ocr.layout.PpDocLayoutTranslator</td><td></td></tr>
</tbody></table>
<h3>目标检测（13）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>dfine-l-obj2coco</code></td><td>✅ 实测通过</td><td>detr.DFineTranslator</td><td>1people.png→2；more car plate.webp→0（默认阈值 0.5，阈值验证 {'0.9': '2→1'}）</td></tr>
<tr><td><code>grounding-dino</code></td><td>✅ 实测通过</td><td>dino.GroundingDinoTranslator</td><td></td></tr>
<tr><td><code>yolov10m</code></td><td>✅ 实测通过</td><td>yolo.v10.translator.YoloV10DetectTranslator</td><td></td></tr>
<tr><td><code>yolov10n</code></td><td>✅ 实测通过</td><td>yolo.v10.translator.YoloV10DetectTranslator</td><td>3peoplebeauty.jpg→7</td></tr>
<tr><td><code>yolov8s</code></td><td>✅ 实测通过</td><td>yolo.v8.translator.YoloV8sTranslator</td><td>1people.png→40；more car plate.webp→63</td></tr>
<tr><td><code>yolov8s-world</code></td><td>✅ 实测通过</td><td>yoloworld.YoloWorldDetectorTranslator</td><td>1people.png→2；more car plate.webp→0；fire.webp→0（阈值验证 {'0.5': '过滤 cup 误检'}）</td></tr>
<tr><td><code>seal-inspection</code></td><td>🧪 冒烟通过（无正样本）</td><td>yolo.SealInspectionTranslator</td><td></td></tr>
<tr><td><code>yolo11-odd</code></td><td>🧪 冒烟通过（无正样本）</td><td>yolo.v11.translator.Yolo11OddTranslator</td><td></td></tr>
<tr><td><code>yolo26-obb</code></td><td>🧪 冒烟通过（无正样本）</td><td>yolo.v26.translator.Yolo26ObbTranslator</td><td></td></tr>
<tr><td><code>yolo26n</code></td><td>🧪 冒烟通过（无正样本）</td><td>yolo.v26.translator.Yolo26ObbTranslator</td><td></td></tr>
<tr><td><code>yolov2-coco</code></td><td>🚫 不再追踪</td><td>yolo.v2.translator.Yolov2CocoTranslator</td><td></td></tr>
<tr><td><code>yolov8l-world</code></td><td>🚫 不再追踪</td><td>yoloworld.YoloWorldDetectorTranslator</td><td></td></tr>
<tr><td><code>yolov8m-world</code></td><td>🚫 不再追踪</td><td>yoloworld.YoloWorldDetectorTranslator</td><td></td></tr>
</tbody></table>
<h3>自然语言（28）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>bart-zh-seq2seq</code></td><td>✅ 实测通过</td><td>seq2seq.BartZhSeq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>bge-large-zh-embedding</code></td><td>✅ 实测通过</td><td>embedding.bge.BgeEmbeddingClient</td><td>语义聚类→相似对 cos=0.936 > 无关对 0.236/0.243</td></tr>
<tr><td><code>bge-small-embedding</code></td><td>✅ 实测通过</td><td>clip.ClipTextFeatureTranslator</td><td>语义聚类→相似对 cos=0.856 > 无关对 0.374/0.379</td></tr>
<tr><td><code>minilm-fp32-embedding</code></td><td>✅ 实测通过</td><td>embedding.minilm.MiniLMEmbeddingTranslator</td><td>语义聚类→相似对 cos=0.858 > 无关对 0.559/0.533</td></tr>
<tr><td><code>minilm-embedding</code></td><td>🧪 冒烟通过（无正样本）</td><td>embedding.minilm.MiniLMEmbeddingTranslator</td><td></td></tr>
<tr><td><code>all-MiniLM-L6-v2-embedding</code></td><td>📄 已有记录</td><td>clip.ClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>bert-squad</code></td><td>📄 已有记录</td><td>text.BertSquadTranslator</td><td></td></tr>
<tr><td><code>bge-base-en-embedding</code></td><td>📄 已有记录</td><td>embedding.bge.BgeTextFeatureTranslator</td><td></td></tr>
<tr><td><code>bge-base-zh-embedding</code></td><td>📄 已有记录</td><td>embedding.bge.BgeTextFeatureTranslator</td><td></td></tr>
<tr><td><code>bge-m3-embedding</code></td><td>📄 已有记录</td><td>clip.ClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>bge-small-en-embedding</code></td><td>📄 已有记录</td><td>embedding.bge.BgeEmbeddingClient</td><td></td></tr>
<tr><td><code>bge-small-zh-embedding</code></td><td>📄 已有记录</td><td>clip.ClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>dino-v2-base-embedding</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>dino-v2-large-embedding</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>dino-v2-small-embedding</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>dino-v2-small-embedding-fp16</code></td><td>📄 已有记录</td><td>dinov2.DinoV2Translator</td><td></td></tr>
<tr><td><code>distil-bert-sentiment</code></td><td>📄 已有记录</td><td>classification.DistilBertSentimentTranslator</td><td></td></tr>
<tr><td><code>granite-embedding</code></td><td>📄 已有记录</td><td>clip.ClipTextFeatureTranslator</td><td></td></tr>
<tr><td><code>mobile-bert-zero-shot-classification</code></td><td>📄 已有记录</td><td>text.MobileBertZeroShotClassificationTranslator</td><td></td></tr>
<tr><td><code>mt5-base-seq2seq</code></td><td>📄 已有记录</td><td>seq2seq.Mt5BaseSeq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>mt5-seq2seq</code></td><td>📄 已有记录</td><td>seq2seq.Mt5Seq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>mt5-zh-seq2seq</code></td><td>📄 已有记录</td><td>seq2seq.Mt5ZhSeq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>opus-mt-en-zh</code></td><td>📄 已有记录</td><td>nlp.translation.OpusMtEnZhTranslationTranslator</td><td></td></tr>
<tr><td><code>opus-mt-zh-en</code></td><td>📄 已有记录</td><td>nlp.translation.OpusMtZhEnTranslationTranslator</td><td></td></tr>
<tr><td><code>roberta-go-emotions</code></td><td>📄 已有记录</td><td>classification.DistilBertSentimentTranslator</td><td></td></tr>
<tr><td><code>t5-base-seq2seq</code></td><td>📄 已有记录</td><td>seq2seq.T5BaseSeq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>t5-seq2seq</code></td><td>📄 已有记录</td><td>seq2seq.T5Seq2SeqOrtTranslator</td><td></td></tr>
<tr><td><code>xlm-roberta-language-detection</code></td><td>📄 已有记录</td><td>classification.XlmRobertaLanguageDetectionTranslator</td><td></td></tr>
</tbody></table>
<h3>视觉理解（9）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>common-action</code></td><td>📄 已有记录</td><td>action.CommonActionTranslator</td><td></td></tr>
<tr><td><code>depth-anything</code></td><td>📄 已有记录</td><td>depth.DepthAnythingOrtTranslator</td><td></td></tr>
<tr><td><code>hand-pose</code></td><td>📄 已有记录</td><td>reid.HandPoseTranslator</td><td></td></tr>
<tr><td><code>midas-depth</code></td><td>📄 已有记录</td><td>depth.MidasDepthTranslator</td><td></td></tr>
<tr><td><code>vit-gpt2-captioning</code></td><td>📄 已有记录</td><td>image.captioning.VitGpt2CaptioningTranslator</td><td></td></tr>
<tr><td><code>vit-pose</code></td><td>📄 已有记录</td><td>pose.VitPoseTranslator</td><td></td></tr>
<tr><td><code>yolov8n-pose</code></td><td>📄 已有记录</td><td>pose.YoloV8nPoseTranslator</td><td></td></tr>
<tr><td><code>c3d-action-detection</code></td><td>🚫 不再追踪</td><td>action.C3DActionDetectionTranslator</td><td></td></tr>
<tr><td><code>florence2</code></td><td>✅ 实测通过</td><td>florence2.Florence2Translator</td><td>VlmClient SPI 接口 + OnnxVlmClient 门面；UnderstandTask 枚举（15 任务）；vision_encoder+embed_tokens+decoder 三模型管线</td></tr>
</tbody></table>
<h3>语音（7）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>dfsmn-ans</code></td><td>✅ 实测通过</td><td>audio.denoise.DfsmnAnsTranslator</td><td></td></tr>
<tr><td><code>mms-tts-eng</code></td><td>✅ 实测通过</td><td>null</td><td></td></tr>
<tr><td><code>sensevoice</code></td><td>✅ 实测通过</td><td>null</td><td>多语言（中/英/日），~325ms</td></tr>
<tr><td><code>zipformer-zh</code></td><td>✅ 实测通过</td><td>audio.zipformer.ZipformerZhAudioClient</td><td>纯中文轻量 ASR，~16MB，embedded 模式；支持流式 feedAudio/getResult/complete</td></tr>
<tr><td><code>vits-icefall-zh</code></td><td>✅ 实测通过</td><td>null</td><td></td></tr>
<tr><td><code>whisper-tiny</code></td><td>🧪 冒烟通过（无正样本）</td><td>null</td><td></td></tr>
<tr><td><code>pocket-tts</code></td><td>📄 已有记录</td><td>null</td><td></td></tr>
</tbody></table>
<h3>超分辨率/增强（9）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>image-text-super-resolution</code></td><td>📄 已有记录</td><td>resolution.ImageTextSuperResolutionTranslator</td><td></td></tr>
<tr><td><code>naf-net</code></td><td>📄 已有记录</td><td>resolution.NafNetTranslator</td><td></td></tr>
<tr><td><code>nomos2</code></td><td>📄 已有记录</td><td>nomos2.Nomos2Translator</td><td></td></tr>
<tr><td><code>onnx-gfpgan</code></td><td>📄 已有记录</td><td>resolution.GfpganFaceSuperResolutionTranslator</td><td></td></tr>
<tr><td><code>real-esrgan</code></td><td>📄 已有记录</td><td>resolution.RealEsrganTranslator</td><td></td></tr>
<tr><td><code>real-text-image-super-resolution</code></td><td>📄 已有记录</td><td>resolution.RealTextImageSuperResolutionTranslator</td><td></td></tr>
<tr><td><code>swinir</code></td><td>📄 已有记录</td><td>resolution.SwinIrTranslator</td><td></td></tr>
<tr><td><code>text-bsr</code></td><td>📄 已有记录</td><td>resolution.TextBsrTranslator</td><td></td></tr>
<tr><td><code>waifu2x</code></td><td>📄 已有记录</td><td>resolution.Waifu2xTranslator</td><td></td></tr>
</tbody></table>
<h3>车牌（3）</h3>
<table><thead><tr><th>模型ID</th><th>状态</th><th>Translator</th><th>实测数据 / 说明</th></tr></thead><tbody>
<tr><td><code>yolo11-plate-detect</code></td><td>✅ 实测通过</td><td>yolo.v11.translator.Yolo11PlateDetectTranslator</td><td>more car plate.webp→1；2car plate.webp→2；car plate1.webp→1；car plate2.webp→1；car plate3.webp→1（默认阈值 0.25）</td></tr>
<tr><td><code>crnn-plate-rec</code></td><td>📄 已有记录</td><td>plate.translator.CrnnPlateRecTranslator</td><td></td></tr>
<tr><td><code>yolov5-plate-recognize</code></td><td>📄 已有记录</td><td>yolo.plate.translator.Yolo5PlateRecTranslator</td><td></td></tr>
</tbody></table>
<!-- coverage:end -->


---

## 附录：VLM Florence-2 + Zipformer-zh 集成（2026-09-01）

### A.1 VlmClient SPI 接口

```java
package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;

public interface VlmClient {
    static VlmClient create(String name) {
        return ServiceProvider.of(VlmClient.class).getNewExtension(name);
    }
    VlmClient model(String model);
    UnderstandResult understand(byte[] imageData, UnderstandTask task);
}
```

**SPI 注册：** `META-INF/extensions/com.chua.deeplearning.support.image.VlmClient`
→ `com.chua.deeplearning.support.onnx.OnnxVlmClient`

**UnderstandTask 枚举（15 任务）：** `CAPTION` / `OCR` / `OD` / `DETAILED_CAPTION` / `OCR_WITH_REGION` / `DENSE_REGION_CAPTION` / `CAPTION_TO_PHRASE_GROUNDING` / `REFERRING_EXPRESSION_SEGMENTATION` / `REGION_TO_SEGMENTATION` / `OPEN_VOCABULARY_DETECTION` / `REGION_TO_CATEGORY` / `REGION_TO_DESCRIPTION` / `REGION_TO_OCR` / `REGION_PROPOSAL`

### A.2 Zipformer-zh ASR 集成

```java
// 嵌入式模型（内置于 JAR，自动解压缓存到 %TEMP%）
String text = ZipformerZhAudioClient.create()
    .transcribe(Path.of("chinese.wav"));

// 流式转录（chunk-by-chunk）
VirtualClient client = VirtualClient.create("zipformer-zh-streaming");
client.feedAudio(chunk);     // float[2560] @ 16kHz ≈ 160ms
String partial = client.getResult();
client.complete();
String finalResult = client.getResult();
```

**模型来源：** `chtk/sherpa-onnx-zipformer-zh-14M`（HuggingFace）
**模型文件：** encoder.onnx (~8MB) + decoder.onnx (~6MB) + joiner.onnx (~2MB)

### A.3 VirtualClient 重命名（2026-09-01）

`AudioClient` 接口已统一重命名为 `VirtualClient`，覆盖 ASR/TTS/STT 多模态场景。
所有实现类（OpenAiAudioClient、ZipformerZhAudioClient、SenseVoiceAudioClient）同步更新。

---
