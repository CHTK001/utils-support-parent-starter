package com.chua.deeplearning.support.onnx.audio.moss;

import com.chua.deeplearning.support.onnx.audio.AudioUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * MOSS-TTS-nano 多语言 TTS 翻译器（0.1B，48 khz 输出）。
 *
 * <p>基于 OpenMOSS 官方 browser_onnx 导出的多图编排管线：
 * <ol>
 *   <li>SentencePiece BPE 文本编码</li>
 *   <li>prefill 全局 Transformer 预填充（输出 12 层 KV cache）</li>
 *   <li>逐帧循环：local_fixed_sampled_frame 采样 16 码本音频 token，
 *       decode_step 推进全局状态</li>
 *   <li>Audio Tokenizer decode_full 将帧序列解码为波形</li>
 * </ol>
 *
 * <p>参考实现：OpenMOSS/MOSS-TTS-Nano Android 示例 MossOnnxDemoEngine.kt。
 *
 * @author chua
 * @since 4.0.0.42
 */
@Slf4j
public class MossTtsTranslator implements AutoCloseable {

    private static final String DEFAULT_VOICE = "Junhao"; // 默认voice
    private static final int DEFAULT_MAX_FRAMES = 250; // 默认最大帧

    private final ObjectMapper mapper = new ObjectMapper(); // 映射器
    private final OrtEnvironment env = OrtEnvironment.getEnvironment(); // env

    private OrtSession prefillSession; // prefill会话
    private OrtSession decodeSession; // decode会话
    private OrtSession localFrameSession; // 本地帧会话
    private OrtSession codecSession; // codec会话
    private OrtSession codecEncodeSession; // codecencode会话

    private MossSentencePieceBpe tokenizer = new MossSentencePieceBpe(); // tokenizer

    private List<Integer> userPromptPrefix; // 用户提示符前缀
    private List<Integer> userPromptAfterReference; // 用户提示符之后引用
    private List<Integer> assistantPromptPrefix; // assistant提示符前缀

    private int nVq; // nvq
    private int audioPadTokenId; // 音频pad令牌标识
    private int audioStartTokenId; // 音频启动令牌标识
    private int audioEndTokenId; // 音频结束令牌标识
    private int audioUserSlotTokenId; // 音频用户slot令牌标识
    private int audioAssistantSlotTokenId; // 音频assistantslot令牌标识
    private int audioCodebookSize; // 音频codebook大小
    private int maxNewFramesLimit; // 最大新帧限制
    private final Map<String, int[][]> voicePrompts = new HashMap<>(); // voice提示符

    /**
     * 加载模型。
     *
     * @param ttsDir   MOSS-TTS-nano-100M-ONNX 目录
     * @param codecDir MOSS-音频-Tokenizer-nano-ONNX 目录
     * @throws Exception 加载异常
     * @param manifest manifest
     */
    public void prepare(Path ttsDir, Path codecDir) throws Exception {
        JsonNode manifest = mapper.readTree(ttsDir.resolve("browser_poc_manifest.json").toFile());
        loadConfig(manifest);

        tokenizer.load(ttsDir.resolve("tokenizer.model"));

        prefillSession = env.createSession(
                ttsDir.resolve("moss_tts_prefill.onnx").toString(),
                new OrtSession.SessionOptions());
        decodeSession = env.createSession(
                ttsDir.resolve("moss_tts_decode_step.onnx").toString(),
                new OrtSession.SessionOptions());
        localFrameSession = env.createSession(
                ttsDir.resolve("moss_tts_local_fixed_sampled_frame.onnx").toString(),
                new OrtSession.SessionOptions());
        codecSession = env.createSession(
                codecDir.resolve("moss_audio_tokenizer_decode_full.onnx").toString(),
                new OrtSession.SessionOptions());
        Path encodeModel = codecDir.resolve("moss_audio_tokenizer_encode.onnx");
        if (Files.exists(encodeModel)) {
            codecEncodeSession = env.createSession(encodeModel.toString(),
                    new OrtSession.SessionOptions());
        }
    }

    /**
     * 加载配置。
     *
     * @param manifest 方法入参 manifest
     */
    private void loadConfig(JsonNode manifest) {
        JsonNode templates = manifest.get("prompt_templates");
        userPromptPrefix = toIntList(templates.get("user_prompt_prefix_token_ids"));
        userPromptAfterReference = toIntList(templates.get("user_prompt_after_reference_token_ids"));
        assistantPromptPrefix = toIntList(templates.get("assistant_prompt_prefix_token_ids"));

        JsonNode config = manifest.get("tts_config");
        nVq = config.get("n_vq").asInt();
        audioPadTokenId = config.get("audio_pad_token_id").asInt();
        audioStartTokenId = config.get("audio_start_token_id").asInt();
        audioEndTokenId = config.get("audio_end_token_id").asInt();
        audioUserSlotTokenId = config.get("audio_user_slot_token_id").asInt();
        audioAssistantSlotTokenId = config.get("audio_assistant_slot_token_id").asInt();
        audioCodebookSize = config.get("audio_codebook_sizes").get(0).asInt();

        maxNewFramesLimit = manifest.get("generation_defaults").get("max_new_frames").asInt();

        for (JsonNode voiceNode : manifest.get("builtin_voices")) {
            String name = voiceNode.get("voice").asText();
            JsonNode codesNode = voiceNode.get("prompt_audio_codes");
            int[][] codes = new int[codesNode.size()][];
            for (int i = 0; i < codesNode.size(); i++) {
                JsonRow row = new JsonRow(codesNode.get(i));
                codes[i] = row.values();
            }
            voicePrompts.put(name, codes);
        }
    }

    /**
     * JSON 行数组的轻量包装。
     *
     * @param node 节点
     * @return 转为int列表的结果
     */
    private static final class JsonRow {
        private final JsonNode node;

        /**
         * JsonRow。
         * @param node 节点
         * @return JsonRow的结果
         */
        private JsonRow(JsonNode node) {
            this.node = node;
        }

        int[] values() {
            int[] values = new int[node.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = node.get(i).asInt();
            }
            return values;
        }
    }

    /**
     * 转为Int列出。
     *
     * @param node 节点，不允许为 null
     * @return 结果列表，无数据时为空列表
     */
    private List<Integer> toIntList(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asInt());
        }
        return values;
    }

    /**
     * 合成语音（默认音色 Junhao）。
     *
     * @param text 待合成文本
     * @return WAV 字节流（48 khz 单声道 PCM16）
     * @throws Exception 推理异常
     */
    public byte[] synthesize(String text) throws Exception {
        return synthesize(text, DEFAULT_VOICE, DEFAULT_MAX_FRAMES);
    }

    /**
     * 声音克隆合成：以参考音频的音色朗读文本。
     *
     * <p>参考音频经 Audio Tokenizer 编码为提示码序列，
     * 替代内置音色的 manifest 提示码，其余管线不变。</p>
     *
     * @param text      待合成文本（长文本自动分句）
     * @param refWav    参考音频（任意采样率，建议 5~10 秒干净人声）
     * @param maxFrames 单段最大帧数
     * @return WAV 字节流（48 khz）
     * @throws Exception 推理异常
     */
    public byte[] synthesizeWithReference(String text, Path refWav, int maxFrames)
            throws Exception {
        if (codecEncodeSession == null) {
            throw new IllegalStateException("编码器未加载：codec 目录缺少 "
                    + "moss_audio_tokenizer_encode.onnx");
        }
        List<int[]> promptCodes = encodeReference(refWav);
        List<String> chunks = splitChunks(text);
        return synthesizeChunks(chunks, promptCodes);
    }

    /**
     * 参考音频 → 提示码。任意采样率/声道统一转为 48khz 双声道。
     * @param stereo 立体
     * @param refWav refwav
     * @return 立体flat的结果
     */
    private List<int[]> encodeReference(Path refWav) throws Exception {
        float[][] stereo = loadStereo48k(refWav);
        int n = stereo[0].length;
        try (OnnxTensor wav = OnnxTensor.createTensor(env,
                        FloatBuffer.wrap(stereoFlat(stereo)), new long[]{1, 2, n});
             OnnxTensor lens = OnnxTensor.createTensor(env,
                     IntBuffer.wrap(new int[]{n}), new long[]{1});
             OrtSession.Result result = codecEncodeSession.run(Map.of(
                     "waveform", wav,
                     "input_lengths", lens))) {
            int[][][] raw = (int[][][]) ((OnnxTensor) result.get("audio_codes").get())
                    .getValue();
            int frames = raw[0].length;
            List<int[]> codes = new ArrayList<>(frames);
            for (int t = 0; t < frames; t++) {
                int[] row = new int[nVq];
                for (int q = 0; q < nVq; q++) {
                    row[q] = raw[0][t][q];
                }
                codes.add(row);
            }
            log.info("[MossTTS] 克隆参考: {} 帧 ({:.1f}s)", frames, n / 48000.0);
            return codes;
        }
    }

    /**
     * stereoFlat。
     *
     * @param stereo 方法入参 stereo
     * @return 结果值
     */
    private static float[] stereoFlat(float[][] stereo) {
        int n = stereo[0].length;
        float[] flat = new float[2 * n];
        System.arraycopy(stereo[0], 0, flat, 0, n);
        System.arraycopy(stereo[1], 0, flat, n, n);
        return flat;
    }

    /**
     * 加载stereo48k。任意 WAV → 48k 双声道 float[2][N]。
     * @param path 路径
     * @return 加载stereo48k的结果
     * @param in 入
     * @param srcRate srcrate
     * @param dstRate dstrate
     */
    private float[][] loadStereo48k(Path path) throws Exception {
        try (var ais = javax.sound.sampled.AudioSystem.getAudioInputStream(path.toFile())) {
            var fmt = ais.getFormat();
            byte[] bytes = ais.readAllBytes();
            int bytesPer = fmt.getSampleSizeInBits() / 8;
            int ch = fmt.getChannels();
            int total = bytes.length / (bytesPer * ch);
            float[] mono = new float[total];
            for (int i = 0; i < total; i++) {
                float sum = 0;
                for (int c = 0; c < ch; c++) {
                    int idx = (i * ch + c) * bytesPer;
                    int v = (short) (((bytes[idx + 1]) << 8) | (bytes[idx] & 0xFF));
                    sum += v / 32768f;
                }
                mono[i] = sum / ch;
            }
            float[] at48 = fmt.getSampleRate() == 48000f
                    ? mono : resampleLinear(mono, fmt.getSampleRate(), 48000f);
            return new float[][]{at48, at48.clone()};
        }
    }

    /**
     * resampleLinear。
     *
     * @param in 方法入参 in
     * @param srcRate src速率，不允许为 null
     * @param dstRate dst速率，不允许为 null
     * @return 结果值
     */
    private static float[] resampleLinear(float[] in, float srcRate, float dstRate) {
        long newLenLong = (long) in.length * (long) dstRate / (long) srcRate;
        int newLen = (int) Math.min(newLenLong, Integer.MAX_VALUE - 1);
        float[] out = new float[Math.max(1, newLen)];
        for (int i = 0; i < out.length; i++) {
            float pos = i * srcRate / dstRate;
            int lo = (int) pos;
            int hi = Math.min(lo + 1, in.length - 1);
            out[i] = in[lo] + (in[hi] - in[lo]) * (pos - lo);
        }
        return out;
    }

    /**
     * 合成语音。
     *
     * @param text      待合成文本
     * @param voice     内置音色名（如 Junhao/Zhiming/Xiaoyu）
     * @param maxFrames 最大生成帧数（约 12.5 fps）
     * @return WAV 字节流（48 khz 单声道 PCM16）
     * @throws Exception 推理异常
     */
    public byte[] synthesize(String text, String voice, int maxFrames) throws Exception {
        int[] textTokens = tokenizer.encode(text);
        List<int[]> promptCodes = selectVoicePrompt(voice);
        int[][] inputIds = buildInputRows(promptCodes, textTokens);
        int cappedMaxFrames = Math.min(Math.max(maxFrames, 1), maxNewFramesLimit);

        float[] pcm;
        try (PrefillState state = runPrefill(inputIds)) {
            List<int[]> audioTokens = generateFrames(state, cappedMaxFrames);
            pcm = decodeAudio(audioTokens);
        }
        return AudioUtils.toWavBytes(pcm, 48000);
    }

    /**
     * 长文本合成入口：按标点分句逐段合成，段间插入短停顿后拼接。
     *
     * @param text  待合成文本（任意长度）
     * @param voice 内置音色名
     * @return WAV 字节流（48 khz 单声道 PCM16）
     * @throws Exception 推理异常
     */
    public byte[] synthesizeText(String text, String voice) throws Exception {
        return synthesizeChunks(splitChunks(text), selectVoicePrompt(voice));
    }

    /** 分段循环合成的公共实现（克隆与内置音色共用）。 */
    private byte[] synthesizeChunks(List<String> chunks, List<int[]> promptCodes)
            throws Exception {
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文本无有效内容");
        }

        List<float[]> segments = new ArrayList<>();
        int totalSamples = 0;
        int pauseSamples = Math.round(48000 * PAUSE_SECONDS);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            int frames = Math.min(maxNewFramesLimit, 40 + chunk.length() * 8);
            int[] textTokens = tokenizer.encode(chunk);
            int[][] inputIds = buildInputRows(promptCodes, textTokens);

            float[] pcm;
            try (PrefillState state = runPrefill(inputIds)) {
                List<int[]> audioTokens =
                        generateFrames(state, Math.min(frames, maxNewFramesLimit));
                pcm = decodeAudio(audioTokens);
            }
            segments.add(pcm);
            totalSamples += pcm.length;
            if (i < chunks.size() - 1) {
                segments.add(new float[pauseSamples]);
                totalSamples += pauseSamples;
            }
            logChunk(i + 1, chunks.size(), chunk, pcm.length);
        }

        float[] all = new float[totalSamples];
        int offset = 0;
        for (float[] seg : segments) {
            System.arraycopy(seg, 0, all, offset, seg.length);
            offset += seg.length;
        }
        return AudioUtils.toWavBytes(all, 48000);
    }

    /** 句末标点（在此处切分并保留标点）。 */
    private static final String SENTENCE_END = "。！？!?；;\n";
    /** 句内标点（超长句的次级切分点）。 */
    private static final String CLAUSE_SPLIT = "，,、：:—…";
    /** 分段间静音秒数。 */
    private static final float PAUSE_SECONDS = 0.32f;
    /** 单段字符上限（超出则按句内标点二次切分）。 */
    private static final int MAX_CHUNK_CHARS = 55;

    /**
    * 日志chunk。
    * @param index 索引
    * @param total total
    * @param chunk chunk
    * @param samples 样本
    */
    private void logChunk(int index, int total, String chunk, int samples) {
        log.info("[MossTTS] 段 {}/{} ({}字, {:.2fs}: {}",
                index, total, chunk.length(), samples / 48000.0,
                chunk.length() > 20 ? chunk.substring(0, 20) + "…" : chunk);
    }

     /**
      * 分割chunks。按句末标点切分，超长句再按句内标点二次切分并合并碎段。
      * @param text 文本
      * @return 分割chunks的结果
      * @param list 列表
      * @param s s
      */
    private List<String> splitChunks(String text) {
        List<String> sentences = splitBy(text, SENTENCE_END);
        List<String> chunks = new ArrayList<>();
        for (String sentence : sentences) {
            if (sentence.length() <= MAX_CHUNK_CHARS) {
                appendNonEmpty(chunks, sentence);
            } else {
                List<String> clauses = splitBy(sentence, CLAUSE_SPLIT);
                StringBuilder buf = new StringBuilder();
                for (String clause : clauses) {
                    if (buf.length() + clause.length() > MAX_CHUNK_CHARS
                            && buf.length() > 0) {
                        appendNonEmpty(chunks, buf.toString());
                        buf.setLength(0);
                    }
                    buf.append(clause);
                }
                appendNonEmpty(chunks, buf.toString());
            }
        }
        return chunks;
    }

    /**
     * 追加NonEmpty。
     *
     * @param list 列出，不允许为 null
     * @param s 方法入参 s
     */
    private void appendNonEmpty(List<String> list, String s) {
        String trimmed = s.trim();
        if (!trimmed.isEmpty()) {
            list.add(trimmed);
        }
    }

    /**
     * 在给定标点集合的每个字符之后切分（保留标点在前段尾部）。
     *
     * @param text 文本
     * @param delims delims
     * @return 分割by的结果
     */
    private List<String> splitBy(String text, String delims) {
        List<String> parts = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            if (delims.indexOf(c) >= 0) {
                parts.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            parts.add(buf.toString());
        }
        return parts;
    }

    /**
     * 从预切分的文本 令牌 合成语音（调试用）。
     *
     * @param textTokens 文本 令牌 序列
     * @param voice      内置音色名
     * @param maxFrames  最大帧数
     * @return WAV 字节流
     * @throws Exception 推理异常
     */
    public byte[] synthesizeFromTokens(int[] textTokens, String voice, int maxFrames) throws Exception {
        List<int[]> promptCodes = selectVoicePrompt(voice);
        int[][] inputIds = buildInputRows(promptCodes, textTokens);
        int cappedMaxFrames = Math.min(Math.max(maxFrames, 1), maxNewFramesLimit);
        float[] pcm;
        try (PrefillState state = runPrefill(inputIds)) {
            List<int[]> audioTokens = generateFrames(state, cappedMaxFrames);
            log.debug("generated frames: {}", audioTokens.size());
            pcm = decodeAudio(audioTokens);
        }
        return AudioUtils.toWavBytes(pcm, 48000);
    }

    /**
     * selectVoice提示词。
     *
     * @param voice 方法入参 voice
     * @return 结果列表，无数据时为空列表
     */
    private List<int[]> selectVoicePrompt(String voice) {
        int[][] codes = voicePrompts.get(voice);
        if (codes == null && !voicePrompts.isEmpty()) {
            codes = voicePrompts.values().iterator().next();
        }
        if (codes == null || codes.length == 0) {
            throw new IllegalStateException("清单中没有可用的内置音色提示码");
        }
        List<int[]> rows = new ArrayList<>(codes.length);
        Collections.addAll(rows, codes);
        return rows;
    }

    /**
     * 构建请求行序列：文本前缀 + 音色提示音频行 + 后缀文本行。
     *
     * <p>行宽 n_vq+1=17：通道 0 承载文本/slot token，通道 1..16 承载音频码。</p>
     * @param rows rows
     * @param promptCodes 提示符编码
     * @param textTokens 文本令牌
     * @param codes 编码
     * @param rowWidth rowwidth
     * @return 构建输入rows的结果
     */
    private int[][] buildInputRows(List<int[]> promptCodes, int[] textTokens) {
        int rowWidth = nVq + 1;
        List<int[]> rows = new ArrayList<>();

        List<Integer> prefix = new ArrayList<>(userPromptPrefix);
        prefix.add(audioStartTokenId);
        appendTextRows(rows, prefix, rowWidth);
        appendAudioRows(rows, promptCodes, rowWidth);

        List<Integer> suffix = new ArrayList<>();
        suffix.add(audioEndTokenId);
        suffix.addAll(userPromptAfterReference);
        for (int token : textTokens) {
            suffix.add(token);
        }
        suffix.addAll(assistantPromptPrefix);
        suffix.add(audioStartTokenId);
        appendTextRows(rows, suffix, rowWidth);
        return rows.toArray(new int[0][]);
    }

    /**
     * 追加文本Rows。
     *
     * @param rows 方法入参 rows
     * @param tokens 方法入参 tokens
     * @param rowWidth 行宽度，不允许为 null
     */
    private void appendTextRows(List<int[]> rows, List<Integer> tokens, int rowWidth) {
        for (int token : tokens) {
            int[] row = new int[rowWidth];
            row[0] = token;
            java.util.Arrays.fill(row, 1, rowWidth, audioPadTokenId);
            rows.add(row);
        }
    }

    /**
     * 追加AudioRows。
     *
     * @param rows 方法入参 rows
     * @param codes 方法入参 codes
     * @param rowWidth 行宽度，不允许为 null
     */
    private void appendAudioRows(List<int[]> rows, List<int[]> codes, int rowWidth) {
        for (int[] codeRow : codes) {
            int[] row = new int[rowWidth];
            row[0] = audioUserSlotTokenId;
            for (int q = 0; q < Math.min(codeRow.length, nVq); q++) {
                row[q + 1] = codeRow[q];
            }
            for (int q = codeRow.length; q < nVq; q++) {
                row[q + 1] = audioPadTokenId;
            }
            rows.add(row);
        }
    }

    /**
     * prefill 输出状态（全局_hidden + KV 缓存），可关闭。
     *
     * @param session 会话
     * @param raw raw
     * @return 创建tensor的结果
     * @param tensor tensor
     * @param inputIds 输入标识
     * @author CH
     * @since 4.0.0
     */
    private final class PrefillState implements AutoCloseable {
        private OnnxTensor globalHidden; // 全局hidden
        private int pastValidLengths; // pastvalid长度
        private OrtSession.Result pastResult; // past结果
/**
 * 关闭。
 * @param inputIds 输入标识
 * @return 运行prefill的结果
 */

        PrefillState(OnnxTensor globalHidden, int pastValidLengths, OrtSession.Result pastResult) {
            this.globalHidden = globalHidden;
            this.pastValidLengths = pastValidLengths;
            this.pastResult = pastResult;
        }

        @Override
        public void close() {
            if (globalHidden != null) {
                globalHidden.close();
                globalHidden = null;
            }
            if (pastResult != null) {
                pastResult.close();
                pastResult = null;
            }
        }
    }

    /**
     * 运行Prefill。
     *
     * @param inputIds 方法入参 inputIds
     * @return Prefill状态 对象
     * @throws OrtException 当执行过程不满足前置条件时
     */
    private PrefillState runPrefill(int[][] inputIds) throws OrtException {
        int seqLen = inputIds.length;
        int rowWidth = inputIds[0].length;
        int[] flat = new int[seqLen * rowWidth];
        int offset = 0;
        for (int[] row : inputIds) {
            for (int value : row) {
                flat[offset++] = value;
            }
        }
        int[] mask = new int[seqLen];
        java.util.Arrays.fill(mask, 1);

        OnnxTensor idsTensor = OnnxTensor.createTensor(env,
                IntBuffer.wrap(flat), new long[]{1, seqLen, rowWidth});
        OnnxTensor maskTensor = OnnxTensor.createTensor(env,
                IntBuffer.wrap(mask), new long[]{1, seqLen});
        OrtSession.Result result;
        try {
            result = prefillSession.run(Map.of(
                    "input_ids", idsTensor,
                    "attention_mask", maskTensor));
        } finally {
            idsTensor.close();
            maskTensor.close();
        }
        try {
            OnnxTensor hidden = extractLastHidden((OnnxTensor) result.get("global_hidden").get());
            /**
             * generate帧。
             * @param state 状态
             * @param maxFrames 最大帧
             * @return generate帧的结果
             */
            return new PrefillState(hidden, seqLen, result);
        } catch (Exception e) {
            result.close();
            throw e;
        }
    }

    /**
     * generateFrames。
     *
     * @param state 状态，不允许为 null
     * @param maxFrames 最大值Frames，不允许为 null
     * @return 结果列表，无数据时为空列表
     * @throws OrtException 当执行过程不满足前置条件时
     */
    private List<int[]> generateFrames(PrefillState state, int maxFrames) throws OrtException {
        List<int[]> audioTokens = new ArrayList<>();
        int rowWidth = nVq + 1;
        Random random = new Random(1234L);
        java.util.Set<Integer>[] seen = new java.util.Set[nVq];
        for (int q = 0; q < nVq; q++) {
            seen[q] = new java.util.HashSet<>();
        }

        List<String> pastInputNames = new ArrayList<>();
        for (int layer = 0; layer < 12; layer++) {
            pastInputNames.add("past_key_" + layer);
            pastInputNames.add("past_value_" + layer);
        }
        List<String> presentOutputNames = new ArrayList<>();
        for (int layer = 0; layer < 12; layer++) {
            presentOutputNames.add("present_key_" + layer);
            presentOutputNames.add("present_value_" + layer);
        }

        for (int step = 0; step < maxFrames; step++) {
            LocalFrame frame = runLocalFixedSampledFrame(state.globalHidden, seen, random);
            if (!frame.shouldContinue) {
                break;
            }
            int[] audioRow = new int[rowWidth];
            audioRow[0] = audioAssistantSlotTokenId;
            java.util.Arrays.fill(audioRow, 1, rowWidth, audioPadTokenId);
            for (int q = 0; q < nVq; q++) {
                audioRow[q + 1] = frame.tokens[q];
                seen[q].add(frame.tokens[q]);
            }
            audioTokens.add(frame.tokens);

            int[] nextFlat = new int[rowWidth];
            System.arraycopy(audioRow, 0, nextFlat, 0, rowWidth);
            Map<String, OnnxTensor> feeds = new HashMap<>();
            OnnxTensor idsTensor = OnnxTensor.createTensor(env,
                    IntBuffer.wrap(nextFlat), new long[]{1, 1, rowWidth});
            OnnxTensor lenTensor = OnnxTensor.createTensor(env,
                    IntBuffer.wrap(new int[]{state.pastValidLengths}), new long[]{1});
            feeds.put("input_ids", idsTensor);
            feeds.put("past_valid_lengths", lenTensor);
            for (int i = 0; i < pastInputNames.size(); i++) {
                feeds.put(pastInputNames.get(i),
                        (OnnxTensor) state.pastResult.get(presentOutputNames.get(i)).get());
            }

            OrtSession.Result outputs = decodeSession.run(feeds);
            idsTensor.close();
            lenTensor.close();

            OnnxTensor nextHidden = extractLastHidden(
                    (OnnxTensor) outputs.get("global_hidden").get());
            OrtSession.Result previous = state.pastResult;
            if (state.globalHidden != null) {
                state.globalHidden.close();
            }
            if (previous != null) {
                previous.close();
            }
            state.globalHidden = nextHidden;
            state.pastResult = outputs;
            state.pastValidLengths += 1;
        }
        return audioTokens;
    }

    private LocalFrame runLocalFixedSampledFrame(OnnxTensor globalHidden,
                                                 java.util.Set<Integer>[] seen,
                                                 Random random) throws OrtException {
        int[] seenMask = new int[nVq * audioCodebookSize];
        for (int channel = 0; channel < nVq; channel++) {
            int base = channel * audioCodebookSize;
            for (int tokenId : seen[channel]) {
                if (tokenId >= 0 && tokenId < audioCodebookSize) {
                    seenMask[base + tokenId] = 1;
                }
            }
        }
        float assistantRandom = clampUnit(random.nextDouble());
        float[] audioRandom = new float[nVq];
        for (int q = 0; q < nVq; q++) {
            audioRandom[q] = clampUnit(random.nextDouble());
        }

        try (OnnxTensor seenTensor = OnnxTensor.createTensor(env,
                        IntBuffer.wrap(seenMask), new long[]{1, nVq, audioCodebookSize});
             OnnxTensor assistantTensor = OnnxTensor.createTensor(env,
                     FloatBuffer.wrap(new float[]{assistantRandom}), new long[]{1});
             OnnxTensor audioTensor = OnnxTensor.createTensor(env,
                     FloatBuffer.wrap(audioRandom), new long[]{1, nVq});
             OrtSession.Result result = localFrameSession.run(Map.of(
                     "global_hidden", globalHidden,
                     "repetition_seen_mask", seenTensor,
                     "assistant_random_u", assistantTensor,
                     "audio_random_u", audioTensor))) {
            OnnxTensor contTensor = (OnnxTensor) result.get("should_continue").get();
            int shouldContinue = firstInt(contTensor.getValue());
            OnnxTensor frameTensor = (OnnxTensor) result.get("frame_token_ids").get();
            int[] tokens = flattenInts(frameTensor.getValue(), nVq);
            return new LocalFrame(shouldContinue > 0, tokens);
        }
    }

    private static final class LocalFrame {
        private final boolean shouldContinue;
        private final int[] tokens;

        private LocalFrame(boolean shouldContinue, int[] tokens) {
            this.shouldContinue = shouldContinue;
            this.tokens = tokens;
        }
    }

    /**
     * 解码Audio。
     *
     * @param audioTokens 方法入参 audioTokens
     * @return 结果值
     * @throws OrtException 当执行过程不满足前置条件时
     */
    private float[] decodeAudio(List<int[]> audioTokens) throws OrtException {
        int numFrames = audioTokens.size();
        if (numFrames == 0) {
            throw new IllegalStateException("未生成任何音频帧");
        }
        int[] flat = new int[numFrames * nVq];
        int offset = 0;
        for (int[] frame : audioTokens) {
            for (int q = 0; q < nVq; q++) {
                flat[offset++] = frame[q];
            }
        }
        try (OnnxTensor codesTensor = OnnxTensor.createTensor(env,
                        IntBuffer.wrap(flat), new long[]{1, numFrames, nVq});
             OnnxTensor lengthsTensor = OnnxTensor.createTensor(env,
                     IntBuffer.wrap(new int[]{numFrames}), new long[]{1});
             OrtSession.Result result = codecSession.run(Map.of(
                     "audio_codes", codesTensor,
                     "audio_code_lengths", lengthsTensor))) {
            OnnxTensor audioTensor = (OnnxTensor) result.get("audio").get();
            float[][][] audio = (float[][][]) audioTensor.getValue();
            OnnxTensor lengthsTensorOut = (OnnxTensor) result.get("audio_lengths").get();
            int reportedLength = firstInt(lengthsTensorOut.getValue());

            float[][] channels = audio[0];
            int length = reportedLength;
            for (float[] channel : channels) {
                length = Math.min(length, channel.length);
            }
            float[] mono = new float[length];
            for (int i = 0; i < length; i++) {
                float sum = 0f;
                for (float[] channel : channels) {
                    sum += channel[i];
                }
                mono[i] = sum / channels.length;
            }
            return mono;
        }
    }

    /**
     * extract最后一个Hidden。
     *
     * @param tensor 方法入参 tensor
     * @return OnnxTensor 对象
     * @throws OrtException 当执行过程不满足前置条件时
     */
    private OnnxTensor extractLastHidden(OnnxTensor tensor) throws OrtException {
        long[] shape = tensor.getInfo().getShape();
        float[] last;
        if (shape.length == 2) {
            last = ((float[][]) tensor.getValue())[0];
        } else if (shape.length == 3) {
            float[][] batch = ((float[][][]) tensor.getValue())[0];
            last = batch[batch.length - 1];
        } else {
            throw new OrtException("global_hidden 维度不支持: " + shape.length);
        }
        return OnnxTensor.createTensor(env,
                FloatBuffer.wrap(last.clone()), new long[]{1, last.length});
    }

    /**
     * 首个Int。
     *
     * @param raw 方法入参 raw
     * @return 结果数值
     */
    private static int firstInt(Object raw) {
        List<Integer> values = new ArrayList<>();
        collect(raw, values);
        if (values.isEmpty()) {
            throw new IllegalStateException("无法提取标量整数");
        /**
         * flattenints。
         * @param raw raw
         * @param limit 限制
         * @return flattenInts的结果
         */
        }
        return values.get(0);
    }

    /**
     * flattenInts。
     *
     * @param raw 方法入参 raw
     * @param limit 上限，不允许为 null
     * @return 结果值
     */
    private static int[] flattenInts(Object raw, int limit) {
        List<Integer> values = new ArrayList<>();
        collect(raw, values);
        int[] result = new int[Math.min(limit, values.size())];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        /**
         * collect。
         * @param raw raw
         * @param out 出
         */
        }
        return result;
    }

    /**
     * 收集。
     *
     * @param raw 方法入参 raw
     * @param out 方法入参 out
     */
    private static void collect(Object raw, List<Integer> out) {
        if (raw instanceof Integer integer) {
            out.add(integer);
        } else if (raw instanceof Long longValue) {
            out.add(longValue.intValue());
        } else if (raw instanceof Short shortValue) {
            out.add(shortValue.intValue());
        } else if (raw instanceof Byte byteValue) {
            out.add(byteValue.intValue());
        } else if (raw instanceof int[] arr) {
            for (int v : arr) {
                out.add(v);
            }
        } else if (raw instanceof long[] arr) {
            for (long v : arr) {
                out.add((int) v);
            }
        } else if (raw instanceof Object[] arr) {
            for (Object item : arr) {
                collect(item, out);
            /**
             * clampunit。
             * @param value 值
             * @return clampUnit的结果
             */
            }
        }
    }

    /**
     * clampUnit。
     *
     * @param value 值，不允许为 null
     * @return 结果数值
     */
    private static float clampUnit(double value) {
        double clamped = Math.max(1e-6, Math.min(value, 1.0 - 1e-6));
        /**
         * 关闭。
         * @param session 会话
         */
        return (float) clamped;
    }

    @Override
    public void close() {
        tokenizer.close();
        closeQuietly(prefillSession);
        closeQuietly(decodeSession);
        closeQuietly(localFrameSession);
        closeQuietly(codecSession);
    }

    /**
     * 关闭Quietly。
     *
     * @param session 会话，不允许为 null
     */
    private void closeQuietly(OrtSession session) {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
