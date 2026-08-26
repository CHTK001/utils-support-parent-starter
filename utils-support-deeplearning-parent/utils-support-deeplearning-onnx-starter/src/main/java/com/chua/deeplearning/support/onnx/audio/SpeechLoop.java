package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.TextToAudioClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 完整语音回路示例：文本 → TextToAudioClient → WAV → AudioClient → 文本。
 *
 * <p>全部基于项目标准客户端 API 构建，TTS 与 ASR 通过 SPI 名称自由组合：
 *
 * <pre>{@code
 * try (SpeechLoop loop = new SpeechLoop()) {
 *     LoopResult r = loop.roundTrip("今天天气很好。", "moss-tts-nano", "zipformer");
 *     System.out.println(r);
 * }
 * }</pre>
 *
 * @author chua
 * @since 4.0.0.42
 */
public final class SpeechLoop implements AutoCloseable {

    /** 单次回环结果报告。 */
    public static final class LoopResult {
        private final String inputText;
        private final String outputText;
        private final String ttsModel;
        private final String asrModel;
        private final int wavBytes;
        private final long ttsMillis;
        private final long asrMillis;
        private final double matchRatio;

        LoopResult(String inputText, String outputText,
                   String ttsModel, String asrModel,
                   int wavBytes, long ttsMillis, long asrMillis) {
            this.inputText = inputText;
            this.outputText = outputText;
            this.ttsModel = ttsModel;
            this.asrModel = asrModel;
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
                    ttsModel, asrModel, inputText, outputText,
                    wavBytes / 1024, ttsMillis, asrMillis, matchRatio * 100);
        }
    }

    /**
     * 执行一次完整回环。
     *
     * @param text     输入文本
     * @param ttsModel TTS SPI 名称（vits-icefall-zh / moss-tts-nano）
     * @param asrModel ASR SPI 名称（sensevoice-small / zipformer / paraformer-zh-small）
     * @return 回环报告
     * @throws Exception 管线异常
     */
    public LoopResult roundTrip(String text, String ttsModel, String asrModel) throws Exception {
        long t0 = System.currentTimeMillis();
        byte[] wav;
        try (TextToAudioClient tts = TextToAudioClient.create(ttsModel, ttsModel)) {
            wav = tts.synthesize(text);
        }
        long ttsMs = System.currentTimeMillis() - t0;

        t0 = System.currentTimeMillis();
        String heard;
        try (AudioClient asr = AudioClient.create(asrModel, asrModel)) {
            heard = asr.audio(wav).transcribe();
        }
        long asrMs = System.currentTimeMillis() - t0;

        return new LoopResult(text, heard, ttsModel, asrModel,
                wav.length, ttsMs, asrMs);
    }

    /**
     * 批量跑全部默认组合。
     *
     * @param text 输入文本
     * @return 全部组合的报告列表
     * @throws Exception 管线异常
     */
    public List<LoopResult> roundTripAll(String text) throws Exception {
        List<LoopResult> results = new ArrayList<>();
        results.add(roundTrip(text, "moss-tts-nano", "zipformer"));
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
        // 客户端均在 try-with-resources 中自管理生命周期
    }

    /**
     * CLI 入口：java SpeechLoop "文本" [ttsModel] [asrModel]。
     *
     * @param args 文本 [tts] [asr]
     * @throws Exception 管线异常
     */
    public static void main(String[] args) throws Exception {
        String text = args.length > 0 ? args[0] : "你好，这是语音回路测试。";
        String tts = args.length > 1 ? args[1] : "moss-tts-nano";
        String asr = args.length > 2 ? args[2] : "zipformer";
        try (SpeechLoop loop = new SpeechLoop()) {
            System.out.println(loop.roundTrip(text, tts, asr));
        }
    }
}
