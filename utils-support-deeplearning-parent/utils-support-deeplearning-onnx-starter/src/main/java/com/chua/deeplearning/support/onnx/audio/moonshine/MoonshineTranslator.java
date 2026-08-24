package com.chua.deeplearning.support.onnx.audio.moonshine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moonshine ONNX 推理器（音频文件 → 转写文本）。
 *
 * <p>复刻 sherpa-onnx 官方四模型贪心解码流程：</p>
 * <ol>
 *   <li>preprocess.onnx：原始 PCM [1,N] → 特征 [1,T,D]</li>
 *   <li>encode.int8.onnx：特征 + 长度 → 隐层 [1,T,D]</li>
 *   <li>uncached_decode.int8.onnx：首步 &lt;s&gt;=1 与 seq_len=1 解码，
 *       产出 logits 与全部 KV states</li>
 *   <li>cached_decode.int8.int8.onnx：逐步喂入最新 token、递增 seq_len
 *       与上一步 states 迭代解码</li>
 * </ol>
 *
 * <p>词表 vocab.json 为 id→token 映射；'▁' 还原为空格。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MoonshineTranslator {

    /**
     * 采样率
     */
    private static final int SAMPLE_RATE = 16000;

    /**
     * 句首 token id
     */
    private static final int SOS = 1;

    /**
     * 句末 token id
     */
    private static final int EOS = 2;

    /**
     * 最大生成长度系数（sherpa 公式：帧数*384/16000*6）
     */
    private static final int TOKEN_LEN_FACTOR = 384;

    private OrtEnvironment ortEnv;
    private OrtSession preprocessSession;
    private OrtSession encoderSession;
    private OrtSession uncachedDecoderSession;
    private OrtSession cachedDecoderSession;
    /** id → token 词表 */
    private Map<Integer, String> vocab;
    /** cached_decoder 的 KV state 输入名 */
    private List<String> kvInputNames;
    /** 是否已就绪 */
    private boolean prepared;

    /**
     * 使用解压后的模型目录初始化。
     *
     * @param modelDir 模型目录（含 4 个 onnx 与 vocab.json）
     * @throws Exception 初始化失败
     */
    public synchronized void prepare(Path modelDir) throws Exception {
        if (prepared) {
            return;
        }
        Path prepPath = resolve(modelDir, "preprocess.onnx");
        Path encPath = resolve(modelDir, "encode.int8.onnx", "encode.onnx");
        Path uncPath = resolve(modelDir, "uncached_decode.int8.onnx", "uncached_decode.onnx");
        Path cachePath = resolve(modelDir, "cached_decode.int8.onnx", "cached_decode.onnx");
        Path vocabPath = modelDir.resolve("vocab.json");
        for (Path p : new Path[]{prepPath, encPath, uncPath, cachePath, vocabPath}) {
            if (!Files.exists(p)) {
                throw new IOException("Moonshine 模型文件缺失: " + p);
            }
        }

        this.ortEnv = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
        this.preprocessSession = ortEnv.createSession(prepPath.toString(), opts);
        this.encoderSession = ortEnv.createSession(encPath.toString(), opts);
        this.uncachedDecoderSession = ortEnv.createSession(uncPath.toString(), opts);
        this.cachedDecoderSession = ortEnv.createSession(cachePath.toString(), opts);

        loadVocab(vocabPath);
        probeKvInputs();

        this.prepared = true;
    }

    /** 是否已初始化 */
    public boolean isPrepared() {
        return prepared;
    }

    /** 解析候选文件名中的第一个存在项 */
    private static Path resolve(Path dir, String... names) {
        for (String n : names) {
            Path p = dir.resolve(n);
            if (Files.exists(p)) {
                return p;
            }
        }
        return dir.resolve(names[0]);
    }

    /** 加载 id→token 词表 */
    private void loadVocab(Path vocabPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(vocabPath.toFile());
        this.vocab = new HashMap<>(root.size());
        var fields = root.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            try {
                this.vocab.put(Integer.parseInt(e.getKey()), e.getValue().asText());
            } catch (NumberFormatException ignore) {
                // 忽略非法键
            }
        }
    }

    /** 探测 cached_decoder 的 KV state 输入名（跳过前 3 个基础输入） */
    private void probeKvInputs() throws ai.onnxruntime.OrtException {
        this.kvInputNames = new ArrayList<>();
        List<ai.onnxruntime.NodeInfo> infos = new ArrayList<>(
                cachedDecoderSession.getInputInfo().values());
        for (int i = 3; i < infos.size(); i++) {
            kvInputNames.add(infos.get(i).getName());
        }
        // 输入名按 args_0..args_N 顺序排列，KV 从 args_3 开始
        kvInputNames.sort((a, b) -> Integer.compare(extractArgIndex(a), extractArgIndex(b)));
    }

    /** 提取 args_N 序号 */
    private static int extractArgIndex(String name) {
        try {
            return Integer.parseInt(name.replace("args_", "").trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 转写音频文件。
     *
     * @param audioPath WAV 音频路径
     * @return 转写文本
     * @throws Exception 推理失败
     */
    public String transcribe(Path audioPath) throws Exception {
        if (!prepared) {
            throw new IllegalStateException("请先调用 prepare()");
        }
        float[] samples = loadAudio(audioPath);
        OrtSession.Result current = null;
        try {
            // Step1: preprocess → 特征
            float[] featFlat;
            long[] featShape;
            try (OnnxTensor in = OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(samples), new long[]{1, samples.length});
                 OrtSession.Result r = preprocessSession.run(Map.of("args_0", in))) {
                OnnxTensor t = (OnnxTensor) r.get(0);
                featShape = t.getInfo().getShape();
                featFlat = toFloatArray(t);
            }

            // Step2: encode → 隐层
            float[] hiddenFlat;
            long[] hiddenShape;
            try (OnnxTensor ft = OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(featFlat), featShape);
                 OnnxTensor len = scalar((int) featShape[1]);
                 OrtSession.Result r = encoderSession.run(Map.of("args_0", ft, "args_1", len))) {
                OnnxTensor t = (OnnxTensor) r.get(0);
                hiddenShape = t.getInfo().getShape();
                hiddenFlat = toFloatArray(t);
            }

            // Step3: uncached decode 首步（<s>=1，seq_len=1）
            int vocabSize;
            try (OnnxTensor tok = tensor2D(SOS);
                 OnnxTensor hid = OnnxTensor.createTensor(ortEnv,
                         FloatBuffer.wrap(hiddenFlat), hiddenShape);
                 OnnxTensor slen = scalar(1)) {
                Map<String, OnnxTensor> feed = new HashMap<>();
                feed.put("args_0", tok);
                feed.put("args_1", hid);
                feed.put("args_2", slen);
                OrtSession.Result r = uncachedDecoderSession.run(feed);
                current = r;
                OnnxTensor logits = (OnnxTensor) r.get(0);
                vocabSize = (int) logits.getInfo().getShape()[2];
                float[] logitBuf = toFloatArray(logits);
                int first = argmax(logitBuf, vocabSize);
                if (first == EOS) {
                    return "";
                }
                List<Integer> tokens = decodeLoop(hiddenFlat, hiddenShape, first, current);
                current = null;
                return detokenize(tokens);
            } finally {
                closeQuietly(current);
                current = null;
            }
        } finally {
            closeQuietly(current);
        }
    }

    /**
     * cached decode 主循环。
     *
     * @param hiddenFlat  编码器隐层展平数据
     * @param hiddenShape 隐层形状
     * @param firstToken  uncached 步产出的首个 token
     * @param firstResult uncached 步的 Result（持有初始 states）
     * @return token 序列
     * @throws Exception 推理失败
     */
    private List<Integer> decodeLoop(float[] hiddenFlat, long[] hiddenShape,
                                     int firstToken, OrtSession.Result firstResult) throws Exception {
        List<Integer> tokens = new ArrayList<>();
        tokens.add(firstToken);
        OrtSession.Result prev = firstResult;
        int maxLen = Math.max(16,
                (int) (hiddenShape[1] * TOKEN_LEN_FACTOR / SAMPLE_RATE * 6));
        try {
            for (int step = 0; step < maxLen; step++) {
                Map<String, OnnxTensor> feed = new HashMap<>();
                OnnxTensor tok = null;
                OnnxTensor hid = null;
                OnnxTensor slen = null;
                try {
                    tok = tensor2D(tokens.get(tokens.size() - 1));
                    hid = OnnxTensor.createTensor(ortEnv,
                            FloatBuffer.wrap(hiddenFlat), hiddenShape);
                    slen = scalar(tokens.size() + 1);
                    feed.put("args_0", tok);
                    feed.put("args_1", hid);
                    feed.put("args_2", slen);
                    for (int i = 0; i < kvInputNames.size(); i++) {
                        feed.put(kvInputNames.get(i), (OnnxTensor) prev.get(i + 1));
                    }
                    OrtSession.Result r = cachedDecoderSession.run(feed);
                    tok = null;
                    hid = null;
                    slen = null;
                    OnnxTensor logits = (OnnxTensor) r.get(0);
                    float[] buf = toFloatArray(logits);
                    int nid = argmax(buf, (int) logits.getInfo().getShape()[2]);
                    if (nid == EOS) {
                        r.close();
                        break;
                    }
                    tokens.add(nid);
                    closeQuietly(prev);
                    prev = r;
                } finally {
                    closeQuietly(tok);
                    closeQuietly(hid);
                    closeQuietly(slen);
                }
            }
        } finally {
            closeQuietly(prev);
        }
        return tokens;
    }

    /** 创建 [1,1] int32 张量 */
    private OnnxTensor tensor2D(int v) throws ai.onnxruntime.OrtException {
        return OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(new int[]{v}), new long[]{1, 1});
    }

    /** 创建标量 int32 张量 */
    private OnnxTensor scalar(int v) throws ai.onnxruntime.OrtException {
        return OnnxTensor.createTensor(ortEnv, IntBuffer.wrap(new int[]{v}), new long[]{1});
    }

    /** 张量转一维数组 */
    private static float[] toFloatArray(OnnxTensor t) {
        FloatBuffer fb = t.getFloatBuffer();
        float[] arr = new float[fb.remaining()];
        fb.get(arr);
        return arr;
    }

    /** argmax */
    private static int argmax(float[] arr, int n) {
        int best = 0;
        float max = arr[0];
        for (int i = 1; i < n; i++) {
            if (arr[i] > max) {
                max = arr[i];
                best = i;
            }
        }
        return best;
    }

    /** id 序列转文本：'▁'→空格并跳过特殊 token */
    private String detokenize(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            String tk = vocab.get(id);
            if (tk == null || "<unk>".equals(tk) || "<s>".equals(tk) || "</s>".equals(tk)) {
                continue;
            }
            sb.append(tk.replace('▁', ' '));
        }
        return sb.toString().trim();
    }

    /** 加载音频为 16kHz 单声道 [-1,1] 采样（支持 16bit PCM / 8bit / 32bit float） */
    public static float[] loadAudio(Path path) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new File(path.toUri()))) {
            AudioFormat fmt = in.getFormat();
            float sampleRate = fmt.getSampleRate();
            int channels = fmt.getChannels();
            int bits = fmt.getSampleSizeInBits();
            boolean bigEndian = fmt.isBigEndian();

            byte[] bytes;
            try (var baos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
                bytes = baos.toByteArray();
            }

            int bps = bits / 8;
            int total = bytes.length / bps;
            int monoLen = total / channels;
            float[] mono = new float[monoLen];
            for (int i = 0; i < monoLen; i++) {
                float sum = 0F;
                for (int c = 0; c < channels; c++) {
                    int off = (i * channels + c) * bps;
                    float s;
                    if (bits == 16) {
                        int lo = bytes[off] & 0xFF;
                        int hi = bytes[off + 1];
                        short v = (short) (bigEndian ? ((lo << 8) | hi) : ((hi << 8) | lo));
                        s = v / 32768.0F;
                    } else if (bits == 8) {
                        s = (bytes[off] - 128) / 128.0F;
                    } else if (bits == 32 && fmt.getEncoding() == AudioFormat.Encoding.PCM_FLOAT) {
                        s = Float.intBitsToFloat(java.nio.ByteBuffer.wrap(bytes, off, 4)
                                .order(bigEndian ? java.nio.ByteOrder.BIG_ENDIAN
                                        : java.nio.ByteOrder.LITTLE_ENDIAN).getInt());
                    } else {
                        s = 0F;
                    }
                    sum += s;
                }
                mono[i] = sum / channels;
            }

            if (Math.abs(sampleRate - SAMPLE_RATE) < 1F) {
                return mono;
            }
            int newLen = (int) Math.round(mono.length * (double) SAMPLE_RATE / sampleRate);
            float[] out = new float[newLen];
            for (int i = 0; i < newLen; i++) {
                double pos = i * (double) sampleRate / SAMPLE_RATE;
                int lo = (int) pos;
                int hi = Math.min(lo + 1, mono.length - 1);
                float frac = (float) (pos - lo);
                out[i] = mono[lo] * (1 - frac) + mono[hi] * frac;
            }
            return out;
        }
    }

    /** 关闭会话 */
    public synchronized void close() {
        closeQuietly(preprocessSession);
        closeQuietly(encoderSession);
        closeQuietly(uncachedDecoderSession);
        closeQuietly(cachedDecoderSession);
        prepared = false;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
                // 忽略关闭异常
            }
        }
    }
}
