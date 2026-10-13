package com.chua.image.support.compression.engine;

import com.chua.common.support.image.compression.DeflateEngine;
import com.chua.common.support.spi.annotations.Spi;

import java.util.zip.Deflater;

/**
 * 基于 {@code java.util.zip.Deflater} 的 deflate 引擎。
 *
 * <p>JDK 绑定的是 zlib 本体，压缩率已经等于 zlib 上限，因此无法达到 zopfli 那样的
 * 「同等参数再小 5%」。但 zlib 提供了 {@link Deflater#setStrategy(int)} 这个
 * 纯 Java 也能用的维度：同一份数据在不同 strategy 下的最优选择并不固定
 * （{@code DEFAULT} 适合常规图，{@code FILTERED} 在已经做过行间预测的数据上常常更优）。
 * 本实现把「级别 × 策略」组成候选矩阵逐个试压并取最小值，
 * 是 zopfli 思想在纯 Java 约束下的可行近似。
 *
 * <h3>后续替换</h3>
 * <p>{@link DeflateEngine} 的契约在 {@link #deflate} 的 Javadoc 中已经写明：
 * 新增一个 libdeflate / zopfli 实现并注册到
 * {@code META-INF/extensions/com.chua.common.support.image.compression.DeflateEngine}，
 * 即可被 {@link CompressionEngines} 自动优先选中，本类自动让位。
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi(value = {"jdk", "zlib", "default"}, order = -100)
public class JdkDeflateEngine implements DeflateEngine {

    /**
     * 单次 deflate 输出的初始缓冲区大小
     */
    private static final int BUFFER_SIZE = 1 << 16;

    @Override
    public byte[] deflate(byte[] data, DeflateProfile profile) throws Exception {
        int[] levels = candidateLevels(profile.level());
        int[] strategies = candidateStrategies(profile.iterations());

        byte[] best = null;
        int attempts = 0;
        int budget = Math.max(1, profile.iterations());
        for (int level : levels) {
            for (int strategy : strategies) {
                if (attempts >= budget && best != null) {
                    break;
                }
                byte[] candidate = deflateOnce(data, level, strategy);
                attempts++;
                if (best == null || candidate.length < best.length) {
                    best = candidate;
                }
            }
        }
        return best == null ? deflateOnce(data, profile.level(), Deflater.DEFAULT_STRATEGY) : best;
    }

    /**
     * 执行一次 deflate
     *
     * @param data     原始数据
     * @param level    压缩级别
     * @param strategy 压缩策略
     * @return 带 zlib 头的压缩结果
     */
    private byte[] deflateOnce(byte[] data, int level, int strategy) {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setStrategy(strategy);
            deflater.setInput(data);
            deflater.finish();
            byte[] buffer = new byte[Math.max(BUFFER_SIZE, data.length / 2)];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(buffer.length);
            while (!deflater.finished()) {
                int produced = deflater.deflate(buffer);
                out.write(buffer, 0, produced);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * 生成候选压缩级别
     *
     * <p>级别越高压缩率越好但耗时线性增长，因此只在目标级别附近取少量候选。
     *
     * @param target 目标级别
     * @return 候选级别数组
     */
    private int[] candidateLevels(int target) {
        if (target >= 9) {
            return new int[]{8, 9};
        }
        return new int[]{target, Math.max(target - 1, 0), target + 1};
    }

    /**
     * 生成候选压缩策略
     *
     * @param iterations 迭代次数
     * @return 候选策略数组
     */
    private int[] candidateStrategies(int iterations) {
        if (iterations <= 2) {
            return new int[]{Deflater.DEFAULT_STRATEGY};
        }
        if (iterations <= 8) {
            return new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED};
        }
        return new int[]{Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY};
    }

    @Override
    public String name() {
        return "jdk";
    }

    @Override
    public boolean available() {
        return true;
    }
}