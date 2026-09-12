package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.config.ModelSetting;
import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.engine.IdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;
import java.util.List;
import com.chua.common.support.spi.ServiceProvider;

/**
 * 音频指纹提取器接口，负责从音频信号中提取可用于比对的特征向量。
 *
 * <h2>功能概述</h2>
 * <p>音频指纹（Audio Fingerprint）是将一段音频映射为固定维度浮点向量的过程。
 * 相同或相似的音频内容会产出相近的特征向量，由此可实现音频匹配、重复检测、
 * 声纹识别等下游任务。</p>
 *
 * <h2>适用模型</h2>
 * <ul>
 *   <li><b>wav2vec2</b> — Facebook AI 预训练自监督语音模型，最后一层 hidden state 或
   * 通过 游泳池 聚合后可作为高质量音频特征向量（768/1024 维）。</li>
 *   <li><b>Hubert</b> — 类似 wav2vec2，由 Google 提出，同样适用于语音特征提取。</li>
 *   <li><b>Wespeaker</b> — 专门用于说话人验证的 ResNet+LM 架构，输出 512 维 x-vector。</li>
 * </ul>
 *
 * <h2>与 FeatureExtractor 的区别</h2>
 * <p>本接口与 {@link com.chua.deeplearning.support.feature.FeatureExtractor} 的职责相似，
 * 但专针对于<b>音频领域</b>：输入类型为 {@code byte[]}（音频原始字节，通常为 WAV/PCM），
   * 而非图像字节或文本字符串；内部自动处理采样率重采样（默认目标 16khz）。</p>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * // 提取指纹
 * AudioFingerprinter fingerprinter = AudioFingerprinter.create("wav2vec2-zh");
 * float[] vec1 = fingerprinter.extract(Path.of("audio1.wav"));
 * float[] vec2 = fingerprinter.extract(Path.of("audio2.wav"));
 *
 * // 余弦相似度比对（归一化后点积即余弦相似度）
 * float similarity = cosineSimilarity(vec1, vec2);
 * boolean matched = similarity > 0.75f;
 *
 * // 批量检索：以查询向量匹配数据库中的指纹库
 * List<Float> scores = fingerprintLibrary.batchCompare(queryVec);
 * }</pre>erprintLibrary.batchCompare(queryVec);
 * }</pre>
 *
 * <h2>特征归一化</h2>
 * <p>默认开启 L2 归一化（{@code normalize=true}），即将特征向量除以其欧氏范数。
 * 归一化后向量模长为 1，此时两个向量的点积等价于余弦相似度，
 * 避免了幅值差异对相似度计算的干扰。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface AudioFingerprinter {

    /**
      * 通过 SPI（服务 提供者 接口）创建实例。
     *
     * <p>系统会扫描 classpath 下 META-INF/extensions 中注册的实现类，
     * 选取与 {@code provider} 匹配的类进行实例化。</p>
     *
     * @param provider 引擎提供商标识，如 "onnx"、"pytorch"
     * @param apiKey   API 密钥；本地引擎（ONNX/pytorch）可传入空字符串
     * @return 新建的指纹提取器实例
     * @throws IllegalArgumentException 若未找到对应 提供者 的实现
     */
    static AudioFingerprinter create(String provider, String apiKey) {
        return ServiceProvider.of(AudioFingerprinter.class)
                .getNewExtension(provider, apiKey);
    }

    /**
      * 设置 SPI 提供者 名称（链式调用）。
     *
     * @param provider 提供者 标识
     * @return this
     */
    default AudioFingerprinter provider(String provider) {
        return this;
    }

    /**
      * 设置要使用的模型 标识（链式调用）。
     *
     * <p>模型 ID 须在 {@link com.chua.deeplearning.support.engine.ModelRegistry}
     * 中提前注册，可通过 {@link #listModels()} 查询可用列表。</p>
     *
     * @param model 模型标识，如 "wav2vec2-zh"
     * @return this
     */
    default AudioFingerprinter model(String model) {
        return this;
    }

    /**
     * 以默认配置创建指纹提取器实例，使用引擎单例查找指定模型。
     *
     * @param name 模型名称
     * @return 指纹提取器实例
     */
    static AudioFingerprinter create(String name) {
        return new DefaultAudioFingerprinter(AbstractIdentificationEngine.getInstance(), name, ModelSetting.builder().build());
    }

    /**
      * 查询当前引擎下所有已注册的音频指纹模型 标识 列表。
     *
     * <p>该方法通过 {@link com.chua.deeplearning.support.engine.ModelRegistry}
     * 按 {@link AudioFingerprinter} 能力接口过滤已注册模型，
     * 常用于前端下拉框或能力清单展示。</p>
     *
     * @return 模型 标识 列表，如 ["wav2vec2-zh", "wespeaker-resnet34"]
     */
    static List<String> listModels() {
        return com.chua.deeplearning.support.engine.ModelRegistry.getModelIdsByCapability(com.chua.deeplearning.support.audio.AudioFingerprinter.class);
    }

    /**
     * 以自定义配置创建指纹提取器实例。
     *
     * @param name    模型名称
     * @param setting 模型配置（路径、设备等）
     * @return 指纹提取器实例
     */
    static AudioFingerprinter create(String name, ModelSetting setting) {
        return new DefaultAudioFingerprinter(AbstractIdentificationEngine.getInstance(), name, setting);
    }

    /**
     * 设置模型文件的本地路径（绝对路径或相对路径）。
     *
     * <p>优先级高于引擎注册表中的默认路径，适用于自定义模型部署场景。</p>
     *
     * @param path 模型路径
     * @return this
     */
    AudioFingerprinter modelPath(String path);

    /**
     * 设置推理设备。
     *
     * <p>可选值："cpu"（默认）、"cuda"（NVIDIA GPU，需安装 CUDA 运行时）等。
      * ONNX Runtime 同时支持 "cpu"、"cuda"、"directml"（窗口 GPU）。</p>
     *
     * @param device 设备标识
     * @return this
     */
    AudioFingerprinter device(String device);

    /**
     * 设置期望的音频采样率（Hz）。
     *
     * <p>大多数语音模型要求输入为 16kHz 单声道 PCM。
     * 若原始音频采样率不同，提取器会自动进行重采样插值。</p>
     *
     * @param sampleRate 采样率，默认 16000
     * @return this
     */
    AudioFingerprinter sampleRate(int sampleRate);

    /**
     * 设置是否对输出特征向量进行 L2 归一化。
     *
     * <p>开启后（默认 true），特征向量模长被缩放到 1，
     * 两个向量的点积即为余弦相似度，适合直接用于相似度检索。</p>
     *
     * @param normalize {@code true} 启用归一化，{@code false} 关闭
     * @return this
     */
    AudioFingerprinter normalize(boolean normalize);

    /**
     * 从音频字节数据中提取指纹特征向量。
     *
     * <p>输入音频可以是以下任意格式：
     * <ul>
     *   <li>WAV 文件字节流（自动解析 RIFF 头，支持 16-bit/8-bit PCM 及 float32）</li>
     *   <li>纯 PCM 字节流（已为 16kHz 单声道 float -1~1）</li>
     * </ul>
      * 若输入非 16khz，将自动重采样至目标采样率。</p>
     *
     * @param audioData 音频原始字节数据
     * @return 特征向量，L2 归一化后模长为 1；若归一化关闭则返回原始向量
     * @throws IllegalStateException 若指定模型未在引擎中注册
     * @throws RuntimeException      若音频解码或模型推理失败
     */
    float[] extract(byte[] audioData);

    /**
     * 从音频文件路径中提取指纹特征向量。
     *
     * <p>内部先读取文件全部字节，再委托给 {@link #extract(byte[])} 执行提取。</p>
     *
     * @param path 音频文件路径
     * @return 特征向量
     * @throws RuntimeException 若文件读取失败
     */
    float[] extract(java.nio.file.Path path);
}
