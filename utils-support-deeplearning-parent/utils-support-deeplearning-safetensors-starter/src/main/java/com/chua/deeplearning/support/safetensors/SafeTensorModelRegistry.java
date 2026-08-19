package com.chua.deeplearning.support.safetensors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SafeTensor 模型注册表。
 * <p>
 * 镜像 Python safetensor_models.py 中 MODEL_REGISTRY 的完整模型清单。
 * 提供 Java 侧对 safetensor 托管模型的统一发现与查询能力。
 * </p>
 *
 * @since 4.0.0.42
 */
public final class SafeTensorModelRegistry {

    /** 创建 SafeTensorModelRegistry 实例 */
    private SafeTensorModelRegistry() {
    }

    /**
     * 模型描述条目。
     *
     * @param id 模型标识
     * @param type 模型类型（llm / image_gen / asr / tts / ocr / detection / face_detection 等）
     * @param source 下载来源（modelscope / huggingface / local）
      * @author CH
     * @param description 模型描述
     */
    public record ModelEntry(String id, String type, String source, String description) {
    }

    /**
     * 所有注册模型的不可变列表。
     */
    private static final List<ModelEntry> ALL_MODELS = buildRegistry();

    /**
     * 获取所有注册的模型。
     *
     * @return 模型列表
     */
    public static List<ModelEntry> allModels() {
        return ALL_MODELS;
    }

    /**
     * 按类型获取模型。
     *
     * @param type 模型类型
     * @return 匹配的模型列表
     */
    public static List<ModelEntry> byType(String type) {
        return ALL_MODELS.stream().filter(e -> e.type.equals(type)).toList();
    }

    /**
     * 按 ID 查找模型。
     *
     * @param id 模型标识
     * @return 匹配的模型条目
     */
    public static Optional<ModelEntry> byId(String id) {
        return ALL_MODELS.stream().filter(e -> e.id.equals(id)).findFirst();
    }

    /**
     * 获取所有不重复的模型类型。
     *
     * @return 类型集
     */
    public static Set<String> allTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (ModelEntry e : ALL_MODELS) {
            types.add(e.type());
        }
        return types;
    }

    /**
     * 根据模型 ID 解析对应的 type。
     *
     * @param modelId 模型 ID
     * @return 模型类型，未找到时返回 "llm"
     */
    public static String resolveType(String modelId) {
        if (modelId == null) {
            return "llm";
        }
        return byId(modelId).map(e -> e.type).orElseGet(() -> guessType(modelId));
    }

    /** GuessType */
    private static String guessType(String name) {
        if (name.contains("qwen") || name.contains("llama") || name.contains("minimind") || name.contains("phi")) {
            if (name.contains("minimind-3v")) {
                return "vlm";
            }
            if (name.contains("minimind-3o")) {
                return "asr";
            }
            if (name.contains("embedding")) {
                return "text_embedding";
            }
            return "llm";
        }
        if (name.contains("sd") || name.contains("diffusion") || name.contains("flux") || name.contains("kolors")) {
            return "image_gen";
        }
        if (name.contains("whisper") || name.contains("paraformer") || name.contains("sensevoice")) {
            return "asr";
        }
        if (name.contains("kokoro") || name.contains("tts") || name.contains("facade-tts")) {
            return "tts";
        }
        if (name.contains("retinaface") || name.contains("scrfd") || name.contains("mtcnn") || name.contains("ulfd")) {
            return "face_detection";
        }
        if (name.contains("yolo") || name.contains("detect") || name.contains("damoyolo") || name.contains("dino")) {
            return "detection";
        }
        if (name.contains("ocr") || name.contains("ppocr")) {
            return "ocr";
        }
        if (name.contains("gpen") || name.contains("nafnet") || name.contains("rrdb") || name.contains("lama")) {
            return "image_enhance";
        }
        if (name.contains("matting") || name.contains("unet-matting")) {
            return "matting";
        }
        if (name.contains("resnest") || name.contains("vit-") || name.contains("beit") || name.contains("convnext")) {
            return "image_recognition";
        }
        if (name.contains("inswapper") || name.contains("reswapper")) {
            return "face_swap";
        }
        if (name.contains("banana") || name.contains("tryon") || name.contains("viton")) {
            return "tryon";
        }
        if (name.contains("musicgen") || name.contains("bark")) {
            return "music_gen";
        }
        if (name.contains("ovisocr2") || name.contains("ovis")) {
            return "document_ocr";
        }
        if (name.contains("florence") || name.contains("cogvlm") || name.contains("internvl")) {
            return "vlm";
        }
        if (name.contains("gte") || name.contains("text_embed")) {
            return "text_embedding";
        }
        return "llm";
    }

    /** 构建Registry */
    private static List<ModelEntry> buildRegistry() {
        List<ModelEntry> list = new ArrayList<>();

        // LLM
        list.add(new ModelEntry("facade-text", "llm", "huggingface", "SafeTensor 门面文本模型（回退/兜底）"));
        list.add(new ModelEntry("qwen2.5-0.5b", "llm", "modelscope", "Qwen2.5-0.5B-Instruct"));
        list.add(new ModelEntry("qwen2.5-1.5b", "llm", "modelscope", "Qwen2.5-1.5B-Instruct"));
        list.add(new ModelEntry("qwen2.5-3b", "llm", "modelscope", "Qwen2.5-3B-Instruct"));
        list.add(new ModelEntry("qwen2.5-7b", "llm", "modelscope", "Qwen2.5-7B-Instruct"));
        list.add(new ModelEntry("qwen2.5-14b", "llm", "modelscope", "Qwen2.5-14B-Instruct"));
        list.add(new ModelEntry("qwen2.5-coder-7b", "llm", "modelscope", "Qwen2.5-Coder-7B-Instruct"));
        list.add(new ModelEntry("qwen2-0.5b", "llm", "modelscope", "Qwen2-0.5B-Instruct"));
        list.add(new ModelEntry("qwen2-1.5b", "llm", "modelscope", "Qwen2-1.5B-Instruct"));
        list.add(new ModelEntry("qwen2-7b", "llm", "modelscope", "Qwen2-7B-Instruct"));
        list.add(new ModelEntry("llama3.2-1b", "llm", "modelscope", "Llama-3.2-1B-Instruct"));
        list.add(new ModelEntry("llama3.2-3b", "llm", "modelscope", "Llama-3.2-3B-Instruct"));
        list.add(new ModelEntry("minimind2-104m", "llm", "huggingface", "MiniMind2-104M"));
        list.add(new ModelEntry("minimind2-small", "llm", "huggingface", "MiniMind2-small"));

        // VLM
        list.add(new ModelEntry("facade-vlm", "vlm", "local", "SafeTensor VLM 门面（回退到 DJL 描述）"));
        list.add(new ModelEntry("minimind-3v", "vlm", "modelscope", "MiniMind-3V 图文理解"));
list.add(new ModelEntry("gpt-4o", "vlm", "openai", "GPT-4o 在线图文理解"));
        list.add(new ModelEntry("blip2-opt", "vlm", "modelscope", "BLIP2-OPT-2.7B 图像描述"));
        list.add(new ModelEntry("ofa-vqa", "vlm", "modelscope", "OFA 视觉问答"));
        list.add(new ModelEntry("ofa-caption", "vlm", "modelscope", "OFA 图像描述"));
        list.add(new ModelEntry("ofa-grounding", "vlm", "modelscope", "OFA 视觉定位"));
        list.add(new ModelEntry("qwen-vl-chat", "vlm", "modelscope", "Qwen-VL-Chat 图文对话"));
        list.add(new ModelEntry("qwen2-vl-2b", "vlm", "modelscope", "Qwen2-VL-2B"));
        list.add(new ModelEntry("qwen2-vl-7b", "vlm", "modelscope", "Qwen2-VL-7B"));
        list.add(new ModelEntry("internvl2-1b", "vlm", "modelscope", "InternVL2-1B"));
        list.add(new ModelEntry("internvl2-2b", "vlm", "modelscope", "InternVL2-2B"));
        list.add(new ModelEntry("internvl2-4b", "vlm", "modelscope", "InternVL2-4B"));
        list.add(new ModelEntry("moondream2", "vlm", "huggingface", "Moondream2 轻量 VLM"));

        // 文生图
        list.add(new ModelEntry("facade-image", "image_gen", "huggingface", "SafeTensor 文生图门面（tiny-sd）"));
        list.add(new ModelEntry("facade-image2image", "image_gen", "modelscope", "SafeTensor 图生图门面（sd1.5）"));
        list.add(new ModelEntry("tiny-sd", "image_gen", "huggingface", "Tiny-SD 轻量文生图"));
        list.add(new ModelEntry("sd1.5", "image_gen", "modelscope", "Stable Diffusion 1.5"));
        list.add(new ModelEntry("sdxl-turbo", "image_gen", "modelscope", "SDXL-Turbo 快速文生图"));
        list.add(new ModelEntry("sd3.5", "image_gen", "modelscope", "Stable Diffusion 3.5"));
        list.add(new ModelEntry("flux1-schn", "image_gen", "modelscope", "FLUX.1-schnell"));
        list.add(new ModelEntry("flux1-dev", "image_gen", "modelscope", "FLUX.1-dev"));
        list.add(new ModelEntry("kolors", "image_gen", "modelscope", "Kolors 文本到图像生成"));
        list.add(new ModelEntry("florence2-base", "image_gen", "modelscope", "Florence-2-base 多模态理解"));

        // TTS
        list.add(new ModelEntry("facade-tts", "tts", "local", "SafeTensor TTS 门面（Kokoro）"));
        list.add(new ModelEntry("facade-audio", "tts", "local", "SafeTensor 音频门面"));
        list.add(new ModelEntry("kokoro", "tts", "local", "Kokoro ONNX TTS"));

        // ASR
        list.add(new ModelEntry("whisper-large-v3", "asr", "modelscope", "Whisper-large-v3 语音识别"));
        list.add(new ModelEntry("paraformer", "asr", "modelscope", "Paraformer 中文 ASR"));
        list.add(new ModelEntry("sensevoice", "asr", "modelscope", "SenseVoiceSmall 语音识别"));
        list.add(new ModelEntry("minimind-3o", "asr", "huggingface", "MiniMind-3o Omni（ASR + 文本）"));

        // 文本嵌入
        list.add(new ModelEntry("qwen3-embedding-8b", "text_embedding", "modelscope", "Qwen3-Embedding-8B"));
        list.add(new ModelEntry("text_embed", "text_embedding", "modelscope", "Corom 中文句嵌入"));
        list.add(new ModelEntry("gte-large", "text_embedding", "modelscope", "GTE 中文大向量嵌入"));
        list.add(new ModelEntry("gte-base", "text_embedding", "modelscope", "GTE 中文基础向量嵌入"));

        // 人脸交换
        list.add(new ModelEntry("inswapper_128", "face_swap", "huggingface", "InSwapper128 人脸替换"));
        list.add(new ModelEntry("reswapper_256", "face_swap", "huggingface", "ReSwapper256 高精度人脸替换"));

        // 人脸检测
        list.add(new ModelEntry("facade-face", "face_detection", "local", "SafeTensor 人脸检测门面"));
        list.add(new ModelEntry("retinaface-r50", "face_detection", "modelscope", "RetinaFace-R50"));
        list.add(new ModelEntry("ddsar", "face_detection", "modelscope", "DDSAR 人脸检测"));
        list.add(new ModelEntry("ddsar-2.5g", "face_detection", "modelscope", "DDSAR 2.5G"));
        list.add(new ModelEntry("ddsar-10g", "face_detection", "modelscope", "DDSAR 10G"));
        list.add(new ModelEntry("ddsar-34g", "face_detection", "modelscope", "DDSAR 34G"));
        list.add(new ModelEntry("scrfd10gkps", "face_detection", "modelscope", "SCRFD 10G 关键点"));
        list.add(new ModelEntry("scrfd34gkps", "face_detection", "modelscope", "SCRFD 34G 关键点"));
        list.add(new ModelEntry("mtcnn", "face_detection", "modelscope", "MTCNN 人脸检测"));
        list.add(new ModelEntry("nanodet-face-human-hand", "face_detection", "modelscope", "NanoDet 人/手/脸检测"));
        list.add(new ModelEntry("vidt-face", "face_detection", "modelscope", "ViDT 人脸检测"));
        list.add(new ModelEntry("mogface", "face_detection", "modelscope", "MogFace 人脸检测"));
        list.add(new ModelEntry("ulfd", "face_detection", "modelscope", "ULFD 轻量人脸检测"));
        list.add(new ModelEntry("tinymog", "face_detection", "modelscope", "TinyMog 轻量人脸检测"));

        // 检测
        list.add(new ModelEntry("facade-detect", "detection", "local", "SafeTensor 检测门面"));
        list.add(new ModelEntry("yolox-nano-coco", "detection", "modelscope", "YOLOX-Nano COCO 检测"));
        list.add(new ModelEntry("yolox", "detection", "modelscope", "YOLOX 通用检测"));
        list.add(new ModelEntry("damoyolo", "detection", "modelscope", "DAMO-YOLO 检测"));
        list.add(new ModelEntry("damoyolo-m", "detection", "modelscope", "DAMO-YOLO-M"));
        list.add(new ModelEntry("damoyolo-t", "detection", "modelscope", "DAMO-YOLO-T"));
        list.add(new ModelEntry("dino-swinl", "detection", "modelscope", "DINO-SwinL 检测"));
        list.add(new ModelEntry("vit-det-coco", "detection", "modelscope", "ViT COCO 检测"));
        list.add(new ModelEntry("human-detection", "detection", "modelscope", "人体检测"));
        list.add(new ModelEntry("head-detection", "detection", "modelscope", "头部检测"));
        list.add(new ModelEntry("hand-detection", "detection", "modelscope", "手部检测"));
        list.add(new ModelEntry("facemask", "detection", "modelscope", "口罩检测"));
        list.add(new ModelEntry("safety-helmet", "detection", "modelscope", "安全帽检测"));
        list.add(new ModelEntry("phone-detection", "detection", "modelscope", "手机检测"));
        list.add(new ModelEntry("traffic-sign", "detection", "modelscope", "交通标志检测"));
        list.add(new ModelEntry("cigarette-detection", "detection", "modelscope", "香烟检测"));
        list.add(new ModelEntry("smokefire-detection", "detection", "modelscope", "烟雾火焰检测"));
        list.add(new ModelEntry("yolox-auto", "detection", "modelscope", "YOLOX-Auto 自动规模"));
        list.add(new ModelEntry("tinynas-detection", "detection", "modelscope", "TinyNAS 检测"));
        list.add(new ModelEntry("resnet50-maskscoring", "detection", "modelscope", "ResNet50 Mask Scoring"));
        list.add(new ModelEntry("vidt-item", "detection", "modelscope", "ViDT 物品检测"));
        list.add(new ModelEntry("vidt-logo", "detection", "modelscope", "ViDT Logo 检测"));
        list.add(new ModelEntry("vidt-text", "detection", "modelscope", "ViDT 文本检测"));
        list.add(new ModelEntry("human-detection-damoyolo", "detection", "modelscope", "人体检测 DAMO-YOLO"));
        list.add(new ModelEntry("uav-detection", "detection", "modelscope", "无人机检测"));
        list.add(new ModelEntry("license-plate", "detection", "modelscope", "车牌检测"));
        list.add(new ModelEntry("card-correction", "detection", "modelscope", "证件矫正"));
        list.add(new ModelEntry("card-detection", "detection", "modelscope", "证件检测"));

        // 图像增强/修复
        list.add(new ModelEntry("gpen-portrait", "image_enhance", "modelscope", "GPEN 人像增强"));
        list.add(new ModelEntry("gpen-portrait-hires", "image_enhance", "modelscope", "GPEN 高分辨率人像增强"));
        list.add(new ModelEntry("face-fusion", "image_enhance", "modelscope", "人脸融合"));
        list.add(new ModelEntry("cartoon-handdrawn", "image_enhance", "modelscope", "手绘卡通化"));
        list.add(new ModelEntry("ddcolor", "image_enhance", "modelscope", "DDColor 图像上色"));
        list.add(new ModelEntry("skin-retouching", "image_enhance", "modelscope", "皮肤美化"));
        list.add(new ModelEntry("nafnet-deblur", "image_enhance", "modelscope", "NAFNet 去模糊"));
        list.add(new ModelEntry("nafnet-denoise", "image_enhance", "modelscope", "NAFNet 去噪"));
        list.add(new ModelEntry("rrdb-super-res", "image_enhance", "modelscope", "RRDB 超分辨率"));
        list.add(new ModelEntry("lama-inpainting", "image_enhance", "modelscope", "LaMa 图像修复"));
        list.add(new ModelEntry("sd-inpainting", "image_enhance", "modelscope", "SD 图像修复"));
        list.add(new ModelEntry("csrnet-color", "image_enhance", "modelscope", "CSRNet 色彩增强"));
        list.add(new ModelEntry("adaint-color", "image_enhance", "modelscope", "AdaInt 色彩增强"));
        list.add(new ModelEntry("deeplpf-color", "image_enhance", "modelscope", "DeepLPF 色彩增强"));
        list.add(new ModelEntry("ecbsr-super-res", "image_enhance", "modelscope", "ECBSR 移动端超分"));
        list.add(new ModelEntry("rrdb-debanding", "image_enhance", "modelscope", "RRDB 去色带"));

        // 图像分类/识别
        list.add(new ModelEntry("resnest-general", "image_recognition", "modelscope", "ResNeSt 通用分类"));
        list.add(new ModelEntry("resnest-animal", "image_recognition", "modelscope", "ResNeSt 动物分类"));
        list.add(new ModelEntry("vit-dailylife", "image_recognition", "modelscope", "ViT 日用品分类"));
        list.add(new ModelEntry("vit-imagenet", "image_recognition", "modelscope", "ViT ImageNet 分类"));
        list.add(new ModelEntry("beitv2-base", "image_recognition", "modelscope", "BEiTv2 基础分类"));
        list.add(new ModelEntry("beitv2-large", "image_recognition", "modelscope", "BEiTv2 大模型分类"));
        list.add(new ModelEntry("bnext-small", "image_recognition", "modelscope", "BNexT 小模型分类"));
        list.add(new ModelEntry("nextvit-small", "image_recognition", "modelscope", "NextViT 小模型分类"));
        list.add(new ModelEntry("tinynas", "image_recognition", "modelscope", "TinyNAS 分类"));
        list.add(new ModelEntry("resnet50-cc", "image_recognition", "modelscope", "ResNet50 CC 分类"));
        list.add(new ModelEntry("convnext-garbage", "image_recognition", "modelscope", "ConvNeXt 垃圾分类"));
        list.add(new ModelEntry("face-quality-fqa", "image_recognition", "modelscope", "人脸质量评估 FQA"));
        list.add(new ModelEntry("face-liveness", "image_recognition", "modelscope", "RGB 活体检测"));
        list.add(new ModelEntry("face-liveness-ir", "image_recognition", "modelscope", "IR 活体检测"));
        list.add(new ModelEntry("face-liveness-xc", "image_recognition", "modelscope", "XC 活体检测"));
        list.add(new ModelEntry("facial-expression", "image_recognition", "modelscope", "表情识别"));
        list.add(new ModelEntry("face-attribute", "image_recognition", "modelscope", "人脸属性识别"));
        list.add(new ModelEntry("live-category", "image_recognition", "modelscope", "直播场景分类"));
        list.add(new ModelEntry("pedestrian-attr", "image_recognition", "modelscope", "行人属性识别"));
        list.add(new ModelEntry("ofa-classification", "image_recognition", "modelscope", "OFA ImageNet 多模态分类"));

        // 抠图
        list.add(new ModelEntry("unet-matting", "matting", "modelscope", "UNet 人像抠图"));
        list.add(new ModelEntry("unet-universal-matting", "matting", "modelscope", "UNet 通用抠图"));

        // OCR
        list.add(new ModelEntry("facade-ocr", "ocr", "local", "SafeTensor OCR 门面"));
        list.add(new ModelEntry("convnext-ocr-doc", "ocr", "modelscope", "ConvNeXt 文档 OCR"));
        list.add(new ModelEntry("convnext-ocr-general", "ocr", "modelscope", "ConvNeXt 通用 OCR"));
        list.add(new ModelEntry("convnext-ocr-scene", "ocr", "modelscope", "ConvNeXt 场景 OCR"));
        list.add(new ModelEntry("convnext-ocr-handwritten", "ocr", "modelscope", "ConvNeXt 手写体 OCR"));
        list.add(new ModelEntry("convnext-ocr-plate", "ocr", "modelscope", "ConvNeXt 车牌 OCR"));
        list.add(new ModelEntry("crnn-ocr-general", "ocr", "modelscope", "CRNN 通用 OCR"));
        list.add(new ModelEntry("damo-ocr-doc", "ocr", "modelscope", "DAMO 文档 OCR"));
        list.add(new ModelEntry("ofa-ocr-doc", "ocr", "modelscope", "OFA 文档 OCR"));
        list.add(new ModelEntry("ofa-ocr-handwritten", "ocr", "modelscope", "OFA 手写体 OCR"));
        list.add(new ModelEntry("ofa-ocr-general", "ocr", "modelscope", "OFA 通用 OCR"));
        list.add(new ModelEntry("ofa-ocr-web", "ocr", "modelscope", "OFA 网页 OCR"));
        list.add(new ModelEntry("lite-ocr", "ocr", "modelscope", "LightweightEdge 轻量 OCR"));
        list.add(new ModelEntry("ppocrv6-tiny", "ocr", "huggingface", "PP-OCRv6 Tiny 多语言 OCR"));
        list.add(new ModelEntry("ppocrv6-small", "ocr", "huggingface", "PP-OCRv6 Small 多语言 OCR"));
        list.add(new ModelEntry("ppocrv6-medium", "ocr", "huggingface", "PP-OCRv6 Medium 多语言 OCR"));

        // 虚拟试穿
        list.add(new ModelEntry("nano_banana", "tryon", "modelscope", "Nano-Banana 虚拟试穿"));

        // 音乐生成
        list.add(new ModelEntry("musicgen-small", "music_gen", "huggingface", "MusicGen Small 音乐生成"));
        list.add(new ModelEntry("bark", "music_gen", "huggingface", "Bark Small 文本到音频"));

        // 文档版面解析
        list.add(new ModelEntry("unlimited-ocr", "document_ocr", "huggingface", "百度 Unlimited-OCR 端到端文档→Markdown 解析 (3B, R-SWA, CPU可行)"));
        list.add(new ModelEntry("ovisocr2", "document_ocr", "modelscope", "OvisOCR2 端到端文档→Markdown 解析 (Qwen3.5-0.8B)"));

        // LLM (HuggingFace 额外补充)
        list.add(new ModelEntry("mistral-7b", "llm", "huggingface", "Mistral-7B Instruct"));
        list.add(new ModelEntry("mistral-7b-instruct", "llm", "huggingface", "Mistral-7B Instruct v0.1"));
        list.add(new ModelEntry("gemma-2b", "llm", "huggingface", "Gemma 2B Instruct"));
        list.add(new ModelEntry("gemma-7b", "llm", "huggingface", "Gemma 7B Instruct"));
        list.add(new ModelEntry("phi-2", "llm", "huggingface", "Phi-2 2.7B Instruct"));
        list.add(new ModelEntry("tinyllama-1.1b", "llm", "huggingface", "TinyLlama 1.1B Chat"));
        // LLM (ModelScope 额外补充)
        list.add(new ModelEntry("minimind-1b", "llm", "modelscope", "MiniMind-1B Instruct"));
        list.add(new ModelEntry("minimind-3b", "llm", "modelscope", "MiniMind-3B Instruct"));
        list.add(new ModelEntry("minimind-7b", "llm", "modelscope", "MiniMind-7B Instruct"));

        // 图像生成 (HuggingFace 额外补充)
        list.add(new ModelEntry("stable-diffusion-2-1", "image_gen", "huggingface", "Stable Diffusion 2.1"));
        list.add(new ModelEntry("stable-diffusion-xl-base-1.0", "image_gen", "huggingface", "SDXL Base 1.0"));

        // 语音识别 (ASR) - HuggingFace
        list.add(new ModelEntry("wav2vec2-large-960h", "asr", "huggingface", "Wav2Vec2 Large 960h"));
        list.add(new ModelEntry("distil-whisper-large-v2", "asr", "huggingface", "Distil-Whisper Large v2"));

        // 文本嵌入 (HuggingFace)
        list.add(new ModelEntry("bert-base-uncased", "text_embedding", "huggingface", "BERT Base 768 dim"));
        list.add(new ModelEntry("bert-large-uncased", "text_embedding", "huggingface", "BERT Large 1024 dim"));
        list.add(new ModelEntry("roberta-base", "text_embedding", "huggingface", "RoBERTa Base 768 dim"));
        list.add(new ModelEntry("roberta-large", "text_embedding", "huggingface", "RoBERTa Large 1024 dim"));
        list.add(new ModelEntry("sentence-transformers/all-MiniLM-L6-v2", "text_embedding", "huggingface", "Sentence Transformers MiniLM L6 v2"));
        list.add(new ModelEntry("intfloat/multilingual-e5-large", "text_embedding", "huggingface", "Multilingual E5 Large"));

        return Collections.unmodifiableList(list);
    }
}
