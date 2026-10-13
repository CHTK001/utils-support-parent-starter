package com.chua.common.support.image.compression;

/**
 * deflate 压缩引擎 SPI —— 图片压缩的<b>native 钩子</b>。
 *
 * <p>PNG 的 IDAT 数据按规范只能使用 deflate（RFC 1951）压缩，容器层用的是 zlib 封装（RFC 1950）。
 * 所有 PNG 压缩率上的差异，除了像素端的有损量化，绝大部分来自 deflate 自身的实现与参数搜索。
 *
 * <h3>为什么抽出 SPI</h3>
 * <p>{@code java.util.zip.Deflater} 是 zlib 的 Java 绑定，压缩率已达 zlib 上限；
 * 而 {@code libdeflate} / {@code zlib-ng} 在同等压缩率下快 1.5~2 倍，
 * {@code zopfli} 则通过大量迭代搜索在同参数下再小 3%~8%。
 * 这些能力都只能通过原生库获得。抽出本接口后，接入 native 只需新增一个实现类并注册，
 * 压缩引擎与调用方完全不用改动。
 *
 * <h3>实现约定</h3>
 * <ul>
 *   <li>{@link #deflate(byte[], DeflateProfile)} 必须返回 <b>带 zlib 头</b>（RFC 1950）的字节，
 *       而非裸 deflate 块</li>
 *   <li>实现必须是<b>无状态</b>且<b>线程安全</b>的</li>
 *   <li>实现应当复用内部缓冲区，避免大图场景下的高频分配</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface DeflateEngine {

    /**
     * 按给定档位压缩数据。
     *
     * @param data    待压缩的原始字节（PNG 的滤波后扫描线数据）
     * @param profile 压缩档位描述
     * @return 带 zlib 头的压缩字节，不会返回 {@code null}
     * @throws Exception 当压缩失败时
     */
    byte[] deflate(byte[] data, DeflateProfile profile) throws Exception;

    /**
     * 获取引擎名称。
     *
     * @return 引擎名称，如 {@code jdk}、{@code libdeflate}、{@code zopfli}
     */
    String name();

    /**
     * 判断引擎是否可用。
     *
     * <p>原生实现应在此处探测动态库能否成功加载；纯 Java 实现恒返回 {@code true}。
     *
     * @return 可用返回 {@code true}
     */
    boolean available();

    /**
     * deflate 压缩档位。
     *
     * <p>{@code level} 取值 0~9（对应 zlib 的 Z_BEST_SPEED~Z_BEST_COMPRESSION），
     * {@code iterations} 表示「同一档位下反复尝试的参数组合次数」，
     * 用于模拟 zopfli 的穷举行为。
     *
     * @param level      压缩级别，取值 0~9
     * @param iterations 参数搜索次数，取值 1 表示不做额外搜索
     * @author CH
     * @since 4.0.0.43
     */
    record DeflateProfile(int level, int iterations) {

        /**
         * 构造压缩档位
         *
         * @throws IllegalArgumentException 当级别超出 0~9 或迭代次数小于 1 时
         */
        public DeflateProfile {
            if (level < 0 || level > 9) {
                throw new IllegalArgumentException("deflate level 必须在 0~9 之间，实际=" + level);
            }
            if (iterations < 1) {
                throw new IllegalArgumentException("iterations 必须大于等于 1，实际=" + iterations);
            }
        }

        /**
         * 创建单次压缩档位
         *
         * @param level 压缩级别，取值 0~9
         * @return 压缩档位
         */
        public static DeflateProfile of(int level) {
            return new DeflateProfile(level, 1);
        }

        /**
         * 创建多轮参数搜索档位
         *
         * @param level      压缩级别，取值 0~9
         * @param iterations 参数搜索次数
         * @return 压缩档位
         */
        public static DeflateProfile of(int level, int iterations) {
            return new DeflateProfile(level, iterations);
        }

        /**
         * 把压缩等级映射为 deflate 级别
         *
         * @param compressLevel 图片压缩等级
         * @return deflate 级别，取值 0~9
         */
        public static int toDeflateLevel(CompressLevel compressLevel) {
            return switch (compressLevel) {
                case L0, L1 -> 6;
                case L2 -> 7;
                case L3, L4, L5, L6 -> 9;
            };
        }
    }
}