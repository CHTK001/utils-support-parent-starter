package com.chua.deeplearning.support.onnx.audio;

import com.chua.deeplearning.support.onnx.audio.moss.MossTtsTranslator;
import com.chua.deeplearning.support.onnx.audio.sensevoice.SenseVoiceTranslator;
import com.chua.deeplearning.support.onnx.audio.tts.VitsTtsTranslator;
import com.chua.deeplearning.support.onnx.audio.zipformer.ZipformerStreamingTranslator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 完整语音回路：文本 → TTS → 音频 → ASR → 文本。
 *
 * <p>串联本模块全部已实现引擎，支持任意 TTS × ASR 组合并输出回环报告：
 * <ul>
 *   <li>TTS：vits-icefall-zh（8 kHz 内嵌）/ MOSS-TTS-Nano（48 kHz downloadUrl）</li>
 *   <li>ASR：SenseVoice-small（多语言内嵌）/ Zipformer 双语流式</li>
 * </ul>
 *
 * <p>外部模型目录默认指向本地缓存，可通过系统属性覆盖：
 * {@code speech.loop.moss.dir}、{@code speech.loop.codec.dir}、
 * {@code speech.loop.zipformer.dir}、{@code speech.loop.sensevoice.dir}。
 *
 * @author chua
 * @since 4.0.0.42
 */
public final class SpeechLoop implements AutoCloseable {

    private Path mossDir = Path.of(
            System.getProperty("speech.loop.moss.dir",
                    "C:/Users/Administrator/AppData/Local/Temp/opencode/moss-tts"));
    private Path codecDir = Path.of(
            System.getProperty("speech.loop.codec.dir",
                    "C:/Users/Administrator/AppData/Local/Temp/opencode/moss-tokenizer"));
    private Path zipformerDir = Path.of(
            System.getProperty("speech.loop.zipformer.dir",
                    "C:/Users/Administrator/AppData/Local/Temp/opencode/zh-zipformer"));
    private Path sensevoiceDir = Path.of(
            System.getProperty("speech.loop.sensevoice.dir",
                    "C:/Users/Administrator/AppData/Local/Temp/opencode/sensevoice"));

    /** TTS 引擎枚举。 */
    public enum TtsEngine {
        /** VITS 中文（AISHELL3，8 kHz，jar 内嵌）。 */
        VITS_ZH,
        /** MOSS-TTS-Nano 多语言（48 kHz）。 */
        MOSS_NANO
    }

    /** ASR 引擎枚举。 */
    public enum AsrEngine {
        /** SenseVoice-small 多语言（含标点与 ITN）。 */
        SENSEVOICE,
        /** Zipformer 中英双语流式。 */
        ZIPFORMER
    }

    /** 单次回环结果报告。 */
    public static final class LoopResult {
        private final String inputText;
        private final String outputText;
        private final TtsEngine ttsEngine;
        private final AsrEngine asrEngine;
        private final int wavBytes;
        private final long ttsMillis;
        private final long asrMillis;
        private final double matchRatio;

        LoopResult(String inputText, String outputText,
                   TtsEngine ttsEngine, AsrEngine asrEngine,
                   int wavBytes, long ttsMillis, long asrMillis) {
            this.inputText = inputText;
            this.outputText = outputText;
            this.ttsEngine = ttsEngine;
            this.asrEngine = asrEngine;
            this.wavBytes = wavBytes;
            this.ttsMillis = ttsMillis;
            this.asrMillis = asrMillis;
            this.matchRatio = charMatchRatio(inputText, outputText);
        }

        /**
         * 字符级 LCS 匹配率。
         *
         * @return [0,1] 相似度
         */
        public double getMatchRatio() {
            return matchRatio;
        }

        @Override
        public String toString() {
            return String.format(
                    "[回路 %s→%s] 输入:%s | 输出:%s | WAV:%dKB | "
                            + "TTS:%dms | ASR:%dms | 匹配率:%.0f%%",
                    ttsEngine, asrEngine, inputText, outputText,
                    wavBytes / 1024, ttsMillis, asrMillis, matchRatio * 100);
        }
    }

    private VitsTtsTranslator vits;
    private MossTtsTranslator moss;
    private SenseVoiceTranslator sensevoice;
    private ZipformerStreamingTranslator zipformer;
    private boolean sensevoiceReady;

    /**
     * 设置 MOSS-TTS 与 Audio Tokenizer 目录。
     *
     * @param ttsDir   MOSS-TTS 目录
     * @param codecDir Audio Tokenizer 目录
     * @return this
     */
    public SpeechLoop mossDirs(Path ttsDir, Path codecDir) {
        this.mossDir = ttsDir;
        this.codecDir = codecDir;
        return this;
    }

    /**
     * 设置 Zipformer 模型目录。
     *
     * @param dir 模型目录
     * @return this
     */
    public SpeechLoop zipformerDir(Path dir) {
        this.zipformerDir = dir;
        return this;
    }

    /**
     * 设置 SenseVoice 模型目录。
     *
     * @param dir 模型目录
     * @return this
     */
    public SpeechLoop sensevoiceDir(Path dir) {
        this.sensevoiceDir = dir;
        return this;
    }

    /**
     * 文本转语音。
     *
     * @param text   待合成文本
     * @param engine TTS 引擎
     * @return WAV 字节流
     * @throws Exception 合成异常
     */
    public byte[] speak(String text, TtsEngine engine) throws Exception {
        if (engine == TtsEngine.VITS_ZH) {
            if (vits == null) {
                vits = new VitsTtsTranslator();
            }
            return vits.synthesize(text, 0);
        }
        if (moss == null) {
            moss = new MossTtsTranslator();
            moss.prepare(mossDir, codecDir);
        }
        return moss.synthesize(text, "Junhao", 80);
    }

    /**
     * 语音转文本。
     *
     * @param wav    WAV 字节流
     * @param engine ASR 引擎
     * @return 识别文本
     * @throws Exception 识别异常
     */
    public String listen(byte[] wav, AsrEngine engine) throws Exception {
        Path temp = Files.createTempFile("speech-loop-", ".wav");
        try {
            Files.write(temp, wav);
            return listen(temp, engine);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * 语音转文本（文件路径入口）。
     *
     * @param wavPath WAV 路径
     * @param engine  ASR 引擎
     * @return 识别文本
     * @throws Exception 识别异常
     */
    public String listen(Path wavPath, AsrEngine engine) throws Exception {
        if (engine == AsrEngine.ZIPFORMER) {
            if (zipformer == null) {
                zipformer = new ZipformerStreamingTranslator();
                zipformer.prepare(zipformerDir);
            }
            return zipformer.transcribe(wavPath);
        }
        if (!sensevoiceReady) {
            sensevoice = new SenseVoiceTranslator();
            sensevoice.prepare(sensevoiceDir);
            sensevoiceReady = true;
        }
        return sensevoice.transcribe(wavPath, "zh");
    }

    /**
     * 执行一次完整回环：文本 → TTS → ASR → 文本。
     *
     * @param text 输入文本
     * @param tts  TTS 引擎
     * @param asr  ASR 引擎
     * @return 回环报告
     * @throws Exception 管线异常
     */
    public LoopResult roundTrip(String text, TtsEngine tts, AsrEngine asr) throws Exception {
        long t0 = System.currentTimeMillis();
        byte[] wav = speak(text, tts);
        long ttsMs = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        String heard = listen(wav, asr);
        long asrMs = System.currentTimeMillis() - t0;

        return new LoopResult(text, heard, tts, asr, wav.length, ttsMs, asrMs);
    }

    /**
     * 批量跑全部组合。
     *
     * @param text 输入文本
     * @return 全部组合的报告列表
     * @throws Exception 管线异常
     */
    public List<LoopResult> roundTripAll(String text) throws Exception {
        List<LoopResult> results = new java.util.ArrayList<>();
        for (TtsEngine tts : TtsEngine.values()) {
            for (AsrEngine asr : AsrEngine.values()) {
                results.add(roundTrip(text, tts, asr));
            }
        }
        return results;
    }

    /**
     * 字符级最长公共子序列匹配率。
     *
     * @param a 参考文本
     * @param b 假设文本
     * @return [0,1]
     */
    static double charMatchRatio(String a, String b) {
        if (a == null || b == null || a.isEmpty()) {
            return 0d;
        }
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        int lcs = dp[a.length()][b.length()];
        String cleanA = stripPunct(a);
        return cleanA.isEmpty() ? 0d : (double) lcs / cleanA.length();
    }

    private static String stripPunct(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Override
    public void close() {
        if (moss != null) {
            moss.close();
        }
        if (zipformer != null) {
            zipformer.close();
        }
        if (vits != null) {
            vits.close();
        }
    }

    /**
     * CLI 入口：java SpeechLoop "文本" [tts] [asr]。
     *
     * @param args 文本 [tts=vits|moss] [asr=sv|zip]，缺省跑全部组合
     * @throws Exception 管线异常
     */
    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "你好，这是语音回路测试。";
        try (SpeechLoop loop = new SpeechLoop()) {
            if (args.length >= 3) {
                TtsEngine tts = "moss".equalsIgnoreCase(args[1])
                        ? TtsEngine.MOSS_NANO : TtsEngine.VITS_ZH;
                AsrEngine asr = "zip".equalsIgnoreCase(args[2])
                        ? AsrEngine.ZIPFORMER : AsrEngine.SENSEVOICE;
                System.out.println(loop.roundTrip(text, tts, asr));
            } else {
                for (LoopResult result : loop.roundTripAll(text)) {
                    System.out.println(result);
                }
            }
        }
    }
}
