package com.chua.deeplearning.support.onnx.audio.denoise;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * DFSMN 语音降噪（单麦 48k 实时近场，PSM）。
 * <p>
   * 复刻 模型scope {@code speech_dfsmn_ans_psm_48k_causal} pipeline：输入带噪 48khz 单声道
 * wav/pcm 字节，输出降噪后音频字节（与输入封装格式一致）。处理链路：kaldi fbank(120 维)
 * → ONNX mask(961 维) → STFT 谱乘 mask → librosa ISTFT 重建。
 * </p>
 * <p>
 * 模型 {@code audio/denoise/dfsmn_ans/model.onnx} 由 jar
 * {@code utils-support-models-onnx-dfsmn-ans} 提供（ONNX 由 ModelScope PyTorch 权重导出）。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class DfsmnAnsTranslator implements ITranslator<byte[], byte[]> {

    /** 采样率 */
    private static final int SAMPLE_RATE = 48000;

    /** mel 数量 */
    private static final int N_MELS = 120;

    /** mask 维度（FFT/2+1） */
    private static final int N_MASK = 961;

    private static final String RESOURCE_BASE = "audio/denoise/dfsmn_ans/"; // RESOURCE_基础
    private static final String MODEL_FILE = "model.onnx"; // 模型文件
    private static final String MODEL_ID = "dfsmn-ans"; // 模型标识

    private OrtEnvironment ortEnv; // ortenv
    private OrtSession session; // 会话
    private final DfsmnStftIStft stft = new DfsmnStftIStft(); // stft
    private DfsmnKaldiFbank fbank; // fbank
    private volatile boolean loaded; // 加载

    private static volatile DfsmnAnsTranslator shared; // 共享

    /**
     * 获取共享实例。
     *
     * @return 实例
     */
    public static DfsmnAnsTranslator getInstance() {
        if (shared == null) {
            synchronized (DfsmnAnsTranslator.class) {
                if (shared == null) {
                    shared = new DfsmnAnsTranslator();
                }
            }
        }
        return shared;
    }

    /**
      * 构造 dfsmnanstranslator 实例（dither=1.0，模型scope 默认）。
     */
    public DfsmnAnsTranslator() {
        this.fbank = new DfsmnKaldiFbank();
    }

    /**
      * 构造 dfsmnanstranslator 实例。
     *
     * @param dither fbank dither 系数（0 关闭，供确定性测试）
     */
    DfsmnAnsTranslator(float dither) {
        this.fbank = new DfsmnKaldiFbank(dither);
    }

    private void prepare() {
        if (loaded) {
            return;
        }
        synchronized (this) {
            if (loaded) {
                return;
            }
            try {
                Path cache = Path.of(System.getProperty("java.io.tmpdir"))
                        .resolve("chua-models").resolve(MODEL_ID);
                if (!Files.isRegularFile(cache.resolve(MODEL_FILE))) {
                    Files.createDirectories(cache);
                    NativeLoader.of(MODEL_ID)
                            .from(getClass().getClassLoader())
                            .basePath(RESOURCE_BASE)
                            .toTarget(cache)
                            .glob("*.onnx")
                            .withMd5(true)
                            .extractOnly(true)
                            .load();
                }
                this.ortEnv = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
                this.session = ortEnv.createSession(cache.resolve(MODEL_FILE).toString(), opts);
                this.loaded = true;
                log.info("DFSMN ANS 降噪模型加载完成");
            } catch (Exception e) {
                throw new RuntimeException("DFSMN ANS 模型加载失败", e);
            }
        }
    }

    @Override
    public String name() {
        return MODEL_ID;
    }

    @Override
    public byte[] translate(byte[] audioData) {
        prepare();
        boolean hasWav = isWav(audioData);
        float[] samples = WavDecoder.decodeToFloat(audioData, SAMPLE_RATE);
        if (samples.length < 1920) {
            throw new IllegalStateException("音频太短，至少需要 " + 1920f / SAMPLE_RATE + " 秒");
        }
        // fbank (frames, 120)
        float[] fbankFeatures = fbank.extract(samples);
        int frames = DfsmnKaldiFbank.numFrames(samples.length);
        float[] enhanced = runInference(samples, fbankFeatures, frames);
        // 转 int16
        short[] pcm = new short[enhanced.length];
        for (int i = 0; i < enhanced.length; i++) {
            int v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) Math.round(enhanced[i])));
            pcm[i] = (short) v;
        }
        byte[] pcmBytes = toLeBytes(pcm);
        if (hasWav) {
            byte[] header = WavDecoder.buildWavHeader(pcmBytes.length, SAMPLE_RATE, 1, 16);
            byte[] out = new byte[header.length + pcmBytes.length];
            System.arraycopy(header, 0, out, 0, header.length);
            System.arraycopy(pcmBytes, 0, out, header.length, pcmBytes.length);
            return out;
        }
        return pcmBytes;
    }

    /**
     * 执行 fbank → ONNX mask → STFT → mask 应用 → ISTFT 的完整推理。
     *
     * @param samples      48k 时域样本（±32768 级）
     * @param fbankFeatures fbank 特征
     * @param frames       帧数
     * @return 增强后时域信号
     */
    private float[] runInference(float[] samples, float[] fbankFeatures, int frames) {
        try {
            long[] shape = {1, frames, N_MELS};
            float[][][] spectrum = stft.stft(samples);
            int stftFrames = spectrum.length;
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(fbankFeatures), shape)) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input", inputTensor);
                float[][] masks;
                try (OrtSession.Result result = session.run(inputs)) {
                    masks = readMasks((OnnxTensor) result.get("output").orElseThrow(), frames);
                }
 // mask[帧][961]，与 stft帧 对齐（分帧一致）
                float[][] mask2 = new float[stftFrames][N_MASK];
                for (int f = 0; f < Math.min(stftFrames, frames); f++) {
                    System.arraycopy(masks[f], 0, mask2[f], 0, N_MASK);
                }
                return stft.istft(spectrum, mask2, samples.length);
            }
        } catch (Exception e) {
            throw new RuntimeException("DFSMN ANS 推理失败", e);
        }
    }

    /**
      * 读取 (1, 帧, 961) mask 张量为 [帧][961]。
     * @param tensor tensor
     * @param frames 帧
     * @return 读取masks的结果
     */
    private float[][] readMasks(OnnxTensor tensor, int frames) throws Exception {
        float[] flat = tensor.getFloatBuffer().array();
        int total = frames * N_MASK;
        float[][] out = new float[frames][N_MASK];
        if (flat.length < total) {
            total = flat.length;
        }
        for (int i = 0; i < total; i++) {
            out[i / N_MASK][i % N_MASK] = flat[i];
        }
        return out;
    }

    /**
     * 判断是否为 RIFF WAV 音频。
     *
     * @param data 音频字节
     * @return true 表示 wav
     */
    static boolean isWav(byte[] data) {
        return data.length > 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /**
     * int16 数组转小端字节。
     *
     * @param pcm pcm 样本
     * @return 字节数组
     */
    private static byte[] toLeBytes(short[] pcm) {
        byte[] out = new byte[pcm.length * 2];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) {
            bb.putShort(s);
        }
        return out;
    }
}