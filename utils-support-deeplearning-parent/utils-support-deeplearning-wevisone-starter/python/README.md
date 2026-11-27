# WeVisOne-VL 推理服务

`utils-support-deeplearning-wevisone-starter` 的 Java 侧只做协议映射，真正的模型推理跑在本目录的
Python 服务里。两者接口一一对应：

| Java | 服务端 |
| --- | --- |
| `WevisoneHttpClient#infer(byte[], VlmRequest)` | `POST /infer` |
| `WevisoneHttpClient#isReady()` | `GET /status` |

## 接口

### `POST /infer`

```jsonc
{
  "image": "<base64，可带 data:image/png;base64, 前缀>",
  "task": "detection",                  // 官方 11 个任务键之一
  "query": "cat\nperson",               // 类别（换行分隔）/ 指代描述 / 待定位文字 / 自由指令
  "prompt": "",                         // 非空时直接作为提示词（优先级最高）
  "reference_boxes": [[0.1,0.2,0.5,0.6]], // 归一化 0..1，仅 visual_prompt 使用
  "decode_matting": false               // 是否解码 alpha matte
}
```

```jsonc
{
  "ok": true,
  "task": "detection",
  "text": "<ref>cat</ref><box><10><20><300><400></box>…",  // 模型原始响应
  "prompt": "实际使用的提示词",
  "objects": [{"label": "cat", "box": [0.01,0.02,0.3,0.4]}, {"label": "cat", "point": [0.5,0.5]}],
  "masks": [{"width": 640, "height": 480, "png": "<base64 8 位灰度 PNG>"}],
  "image_width": 640,
  "image_height": 480,
  "latency_s": 12.3
}
```

任务键（官方 `wevisone_vl/prompts.py` 的 `TASKS`）：
`detection` / `referring` / `point` / `count_box` / `count_point` / `segmentation` / `matting` /
`ocr` / `ocr_grounding` / `visual_prompt` / `freeform`。提示词由服务端调官方 `build_prompt` 生成，
本仓库不重写任何任务措辞；`prompt` 非空时整体覆盖。

坐标口径：**请求与响应里的框/点都是归一化 0..1**。服务端拿到图片后才知道宽高，所以
`reference_boxes` 由它换算成模型需要的像素坐标；模型输出的 1/1000 token 坐标也由它还原成 0..1，
Java 侧不做任何图像解码。

失败：参数错误返回 400、模型未加载或推理异常返回 500，两者都是 `{"ok": false, "error": "..."}`。

### `GET /status`

```json
{"model_loaded": true, "loading": false, "model": "/models/WeVisOne-VL-2B", "device": "cpu", "dtype": "bfloat16", "backend": "transformers"}
```

加载在启动后的守护线程里进行：期间 `loading=true`、`model_loaded=false`，`/status` 照常 200，
`/infer` 返回 500 并带原因；`model_loaded=false` 且 `error` 非空表示加载失败（此后不会自动重试，重启进程即可）。
容器健康检查读的也是这个字段，所以「进程起来了」不等于「容器健康」。

## 环境变量

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `WEVISONE_MODEL` | `tencent/WeVisOne-VL-2B` | 权重目录或 HF 仓库 id |
| `WEVISONE_DEVICE` | `auto` | `cpu` / `cuda` / `auto` |
| `WEVISONE_DTYPE` | `bfloat16` | `bfloat16` / `float16` / `float32` |
| `WEVISONE_BACKEND` | `transformers` | `transformers` / `vllm`（vllm 需 GPU） |
| `WEVISONE_MIN_PIXELS` | `16384` | 官方默认 `16*32*32` |
| `WEVISONE_MAX_PIXELS` | `2073600` | 官方默认 `2025*32*32` |
| `WEVISONE_DECODER_BATCH` | `16` | mask decoder 批大小 |

## 运行

### 本地 pip

```bash
# 1) 装官方包（含 wevisone_vl 与 mask_decoder.pt 的加载逻辑）
pip install -r requirements.txt            # CPU 机器请把 torch 换成 +cpu 轮子
pip install -e /path/to/WeVisOne-VL

# 2) 起服务
WEVISONE_MODEL=/path/to/WeVisOne-VL-2B WEVISONE_DEVICE=cpu \
    uvicorn server:app --host 0.0.0.0 --port 8765
```

### Docker（CPU）

```bash
# 构建上下文需要官方源码：把 WeVisOne-VL-main 放成 python/WeVisOne-VL
docker build -t wevisone-vl-2b:latest .
docker run -d --name wevisone-vl -p 8765:8765 \
    -v /host/WeVisOne-VL-2B:/models/WeVisOne-VL-2B:ro \
    --memory 12g wevisone-vl-2b:latest
```

内存口径：2B 权重 bf16 约 5GB、float32 约 10GB，容器 `--memory` 低于 8g 必然 OOM；
CPU 单图推理分钟级，生产请用 GPU（`WEVISONE_DEVICE=cuda`）。

## Java 侧

```java
VlmClient client = VlmClient.create("wevisone");            // SPI 名 = wevisone
WevisoneVlClient vl = ((WevisoneVlClient) client)
        .baseUrl("http://192.168.50.192:8765")
        .timeoutSeconds(900);

if (!vl.available()) { /* /status 未就绪：模型仍在加载或服务未起 */ }

VlmResult r = vl.infer(VlmRequest.of(pngBytes, VlmTask.DETECTION, "cat\nperson"));
r.text();            // 模型原始响应
r.objects();         // 归一化 box/point + label
r.targetMissing();   // 模型回答 None
```

`understand(byte[], UnderstandTask)` 是旧契约兼容入口：只映射 `OCR`/`OCR_WITH_REGION`（→ `ocr`）
与三种 caption（→ `freeform`），其余 Florence-2 专有任务直接抛 `UnsupportedOperationException`，
不静默降级成「猜一个相近任务」。
