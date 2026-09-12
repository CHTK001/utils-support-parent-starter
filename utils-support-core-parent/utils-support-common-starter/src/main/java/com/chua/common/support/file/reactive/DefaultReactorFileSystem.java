package com.chua.common.support.file.reactive;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

/**
* 默认响应式文件系统实现。
*
* <p>路由策略：文件大小 &lt; {@link #sizeThreshold} → 阻塞 Files.* 在 boundedElastic 调度器上执行；
* &ge; 阈值 → AsynchronousFileChannel 真异步（Windows IOCP / Linux 回退线程池）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DefaultReactorFileSystem implements ReactorFileSystem {

    /** 小文件阈值：小于此值用阻塞 Files.*，大于此值用 AsynchronousFileChannel */
    private final long sizeThreshold;

    public DefaultReactorFileSystem() {
        this(DEFAULT_SIZE_THRESHOLD);
    }

    /**
    * 构造并指定阈值。
     */
    public DefaultReactorFileSystem(long sizeThreshold) {
        this.sizeThreshold = Math.max(1, sizeThreshold);
    }

    /* ==================== 读取 ==================== */

    @Override
    public Mono<String> readString(Path path) {
        return readBytes(path).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    @Override
    public Mono<byte[]> readBytes(Path path) {
        return exists(path).flatMap(exists -> {
            if (!exists) {
                return Mono.error(new java.nio.file.NoSuchFileException(path.toString()));
            }
            return Mono.fromCallable(() -> java.nio.file.Files.readAllBytes(path))
                    .subscribeOn(isLargeFile(path)
                            ? Schedulers.boundedElastic()
                            : Schedulers.single());
        });
    }

    @Override
    public Flux<String> readLines(Path path) {
        return readString(path)
                .flatMapMany(content ->
                        Flux.fromArray(content.split("\r?\n")));
    }

    /* ==================== 写入 ==================== */

    @Override
    public Mono<Void> writeString(Path path, String content) {
        return writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> writeBytes(Path path, byte[] data) {
        if (data.length >= sizeThreshold) {
            return writeAsync(path, data);
        }
        return Mono.fromCallable(() -> {
                    java.nio.file.Files.write(path, data,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    return Void.TYPE.cast(null);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> appendBytes(Path path, byte[] data) {
        return Mono.fromCallable(() -> {
                    java.nio.file.Files.write(path, data,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    return Void.TYPE.cast(null);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /* ==================== 删除 ==================== */

    @Override
    public Mono<Boolean> delete(Path path) {
        return Mono.fromCallable(() -> java.nio.file.Files.deleteIfExists(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /* ==================== 元信息 ==================== */

    @Override
    public Mono<Long> size(Path path) {
        return Mono.fromCallable(() -> java.nio.file.Files.size(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Boolean> exists(Path path) {
        return Mono.fromCallable(() -> java.nio.file.Files.exists(path))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /* ==================== 内部方法 ==================== */

    private boolean isLargeFile(Path path) {
        try {
            return java.nio.file.Files.size(path) >= sizeThreshold;
        } catch (Exception e) {
            return false;
        }
    }

    /**
    * 使用 AsynchronousFileChannel 异步写入。
    * Windows 上底层为 IOCP 真·非阻塞；Linux 上 JVM 内部使用线程池模拟。
     */
    private Mono<Void> writeAsync(Path path, byte[] data) {
        return Mono.create(sink -> {
            AtomicLong position = new AtomicLong(0);
            try {
                var channel = AsynchronousFileChannel.open(
                        path,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

                ByteBuffer buffer = ByteBuffer.wrap(data);

                channel.write(buffer, 0, null, new CompletionHandler<Integer, Object>() {
                    @Override
                    public void completed(Integer result, Object attachment) {
                        position.addAndGet(result);
                        if (buffer.hasRemaining()) {
                            long pos = position.get();
                            channel.write(buffer, pos, null, this);
                        } else {
                            closeQuietly(channel);
                            sink.success();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, Object attachment) {
                        closeQuietly(channel);
                        sink.error(exc);
                    }
                });
            } catch (IOException e) {
                sink.error(e);
            }
        });
    }

    private static void closeQuietly(AsynchronousFileChannel channel) {
        try { channel.close(); } catch (IOException ignored) { }
    }
}
