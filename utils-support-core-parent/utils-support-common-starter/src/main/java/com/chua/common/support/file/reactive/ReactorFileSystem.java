package com.chua.common.support.file.reactive;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

/**
 * 响应式文件系统接口。
 *
 * <p>所有方法返回 Mono/Flux，内部根据文件大小自动选择执行策略：</p>
 * <ul>
 *   <li>小文件（&lt; 阈值）→ 阻塞 Files.* 在 boundedElastic 调度器上执行</li>
 *   <li>大文件（&ge; 阈值）→ AsynchronousFileChannel 真异步</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ReactorFileSystem {

    /** 默认大小文件阈值 1MB */
    long DEFAULT_SIZE_THRESHOLD = 1024L * 1024L;

    /* ==================== 读取 ==================== */

    /**
     * 读取整个文件为字符串。
     *
     * @param path 文件路径
     * @return 文件内容
     */
    Mono<String> readString(Path path);

    /**
     * 读取整个文件为字节数组。
     *
     * @param path 文件路径
     * @return 文件字节数组
     */
    Mono<byte[]> readBytes(Path path);

    /**
     * 按行读取文件。
     *
     * @param path 文件路径
     * @return 行内容 Flux
     */
    Flux<String> readLines(Path path);

    /* ==================== 写入 ==================== */

    /**
     * 写入字符串到文件（覆盖）。
     *
     * @param path    文件路径
     * @param content 内容
     * @return 完成信号
     */
    Mono<Void> writeString(Path path, String content);

    /**
     * 写入字节数组到文件（覆盖）。
     *
     * @param path 文件路径
     * @param data 字节数据
     * @return 完成信号
     */
    Mono<Void> writeBytes(Path path, byte[] data);

    /**
     * 追加字符串到文件末尾。
     *
     * @param path    文件路径
     * @param content 追加内容
     * @return 完成信号
     * @param data 数据，不允许为 null
     */
    Mono<Void> appendBytes(Path path, byte[] data);

    /* ==================== 删除 ==================== */

    /**
     * 删除文件。
     *
     * @param path 文件路径
     * @return 是否删除成功
     */
    Mono<Boolean> delete(Path path);

    /* ==================== 元信息 ==================== */

    /**
     * 获取文件大小。
     *
     * @param path 文件路径
     * @return 文件大小（字节）
     */
    Mono<Long> size(Path path);

    /**
     * 检查文件是否存在。
     *
     * @param path 文件路径
     * @return 存在返回 true
     */
    Mono<Boolean> exists(Path path);
}
