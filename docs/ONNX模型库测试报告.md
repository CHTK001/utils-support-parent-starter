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

## 六、遗留事项（待拍板）

| 项 | 现状 | 建议 |
|---|---|---|
| `tinaface` | 空。官方仅 mxnet 权重，DCN 算子无法转标准 ONNX | 删除模块，或改放 buffalo_l 包内 `det_10g.onnx`（SCRFD 高精度人脸检测 16.9MB，已在手） |
| `mt5-zh` | 空。pom 描述自相矛盾（名字 mT5/描述 MarianMT/160MB 无对应真实模型） | 三选一：删除；按描述放 opus-mt-zh-en int8（与现有模块重复）；放 mT5-XLSum 中文摘要（1.2GB） |
| 19 个空目录 | 无 pom、未注册 module，不影响构建 | 可直接删除整理 |
| ~~wespeaker 语义验证~~ | 已用 YESNO 真人语料补测通过（2026-08-26） | 已关闭 |
