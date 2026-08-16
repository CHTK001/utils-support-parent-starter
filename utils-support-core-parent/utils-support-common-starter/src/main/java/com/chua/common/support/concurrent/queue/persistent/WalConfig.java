package com.chua.common.support.concurrent.queue.persistent;

import lombok.Builder;
import lombok.Data;

/**
 * WAL（Write-Ahead Log）持久化配置。
 *
 * <p>控制 {@link PersistentLockFreeQueue} 的日志文件路径、刷盘策略与映射方式：
 * <ul>
 *   <li>{@link #mmap} — true 时使用 {@code MappedByteBuffer}（内存映射）顺序写入，
 *       吞吐量高于 FileChannel；false 时回退为 FileChannel + force</li>
 *   <li>{@link #sync} — true 时每次写入后强制刷盘（{@code force}/{@code mmap().force()}），
 *       保证 crash 不丢；false 时降级为异步刷盘，由 JVM/OS 决定落盘时机，
 *       吞吐更高但 crash 可能丢失最近未落盘的操作</li>
 *   <li>{@link #flushIntervalMillis} — 异步刷盘模式下，后台线程的刷新间隔（毫秒），
 *       0 表示不启用后台刷盘，完全依赖 OS 页缓存</li>
 *   <li>{@link #initialFileSize} — mmap 文件初始大小（字节），写满后自动扩展并重新映射</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class WalConfig {

    /**
     * 默认 mmap 初始文件大小：64 MB
     */
    private static final int DEFAULT_INITIAL_FILE_SIZE = 64 * 1024 * 1024;

    /**
     * 默认异步刷盘间隔：100 毫秒
     */
    private static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 100L;

    /**
     * WAL 目录路径
     */
    @Builder.Default
    private String walDir = "./wal";

    /**
     * WAL 文件名
     */
    @Builder.Default
    private String walFile = "queue.wal";

    /**
     * 是否使用内存映射（mmap）写入，默认 true
     */
    @Builder.Default
    private boolean mmap = true;

    /**
     * 是否同步刷盘，默认 false（异步降级以提高吞吐）
     */
    @Builder.Default
    private boolean sync = false;

    /**
     * 异步刷盘后台线程刷新间隔（毫秒），仅 sync=false 时生效
     */
    @Builder.Default
    private long flushIntervalMillis = DEFAULT_FLUSH_INTERVAL_MILLIS;

    /**
     * mmap 初始文件大小（字节）
     */
    @Builder.Default
    private int initialFileSize = DEFAULT_INITIAL_FILE_SIZE;
}
