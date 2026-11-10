package com.chua.common.support.storage;

import com.chua.common.support.storage.request.MultipartUploadPartRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.MultipartPartResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import com.chua.common.support.utils.IdUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.chua.common.support.storage.request.MultipartUploadPartRequest;

/**
 * 文件存储抽象基类。
 *
 * <p>提供 Bucket 初始化、检查等公共逻辑，简化具体实现的开发。</p>
 *
 * @since 1.0
 * @author CH
 */
public abstract class AbstractFileStorage implements FileStorage {

    /**
     * 存储桶设置
     */
    protected final BucketSetting bucketSetting;
    /**
     * 存储桶
     */
    protected final String bucket;
    /**
     * 区域
     */
    protected final String region;
    /**
     * 服务端点
     */
    protected final String endpoint;
    /**
     * 访问密钥 标识
     */
    protected final String accessKeyId;
    /**
     * 访问密钥 Secret
     */
    protected final String accessKeySecret;

    /**
     * 构造函数，接收 Bucket 配置。
     *
     * @param bucketSetting Bucket 配置
     */
    protected AbstractFileStorage(BucketSetting bucketSetting) {
        this.bucketSetting = bucketSetting;
        this.bucket = bucketSetting.getBucket();
        this.region = bucketSetting.getRegion();
        this.endpoint = bucketSetting.getEndpoint();
        this.accessKeyId = bucketSetting.getAccessKeyId();
        this.accessKeySecret = bucketSetting.getAccessKeySecret();
    }

    /**
     * 关闭
     */
    @Override
    public void close() {
        // 子类可覆盖实现资源释放
    }

    /**
     * 按偏移/长度裁剪输入流（{@link GetObjectRequest} 的 Range 语义兜底实现）。
     *
     * <p>给不支持原生 seek 的存储实现使用：先 {@code skip} 掉前缀，再用长度
     * 上限包装，调用方读到的就是请求区间。若底层流自身已按 offset/length 定位
     * （如对象存储下推了 Range 头），**不要**再调用本方法，否则会二次跳过。</p>
     *
     * <p>{@code skip} 在本地文件上是廉价定位；在网络流上是白读，这正是
     * {@link com.chua.common.support.storage.FileStorage#supportsRange()} 存在的
     * 原因——只有支持原生区间读取的实现才应跳过前缀。</p>
     *
     * @param in     原始输入流（不为 null）
     * @param offset 起始偏移，≤0 表示从头
     * @param length 读取长度，&lt;0 表示读到末尾
     * @return 定位后的输入流（关闭返回值即关闭原始流）
     * @throws IOException skip 失败或提前 EOF 时抛出
     */
    protected static InputStream applyRange(InputStream in, long offset, long length) throws IOException {
        if (offset > 0) {
            long remaining = offset;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    // skip 返回 0 时读一字节推进，避免个别实现永远 skip 0 造成死循环
                    if (in.read() < 0) {
                        throw new IOException("偏移超出文件长度: " + offset);
                    }
                    skipped = 1;
                }
                remaining -= skipped;
            }
        }
        return length < 0 ? in : new BoundedInputStream(in, length);
    }

    /**
     * 长度受限的输入流包装：读满上限后表现为 EOF，关闭时关闭底层流。
     *
     * <p>用于 Range 读取——不预先分配 {@code length} 大小的数组，也不依赖
     * 底层流是否恰好在此处结束（部分存储的流会多吐字节或提前结束，两种都必须
     * 收敛到调用方声明的区间上界）。</p>
     */
    protected static final class BoundedInputStream extends java.io.FilterInputStream {

        /** 剩余可读字节数 */
        private long remaining;

        /**
         * 构造长度受限流。
         *
         * @param in     底层流
         * @param length 最多可读字节数（&lt;0 表示不限制）
         */
        BoundedInputStream(InputStream in, long length) {
            super(in);
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = super.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            if (skipped > 0) {
                remaining -= skipped;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    /**
     * 创建分片上传存储。
     *
     * <p>默认使用本地临时目录暂存分片，完成时合并为完整文件后调用 {@link #putObject(PutObjectRequest)}。</p>
     *
     * @return 分片上传存储实例
     */
    @Override
    public MultipartStorage createMultipartStorage() {
        return new LocalTmpMultipartStorage(this);
    }

    /**
     * 基于本地临时目录的分片上传存储实现。
     *
     * <p>分片暂存在本地临时目录，完成时合并为完整字节数组后调用 {@link #putObject(PutObjectRequest)}。</p>
     * @author CH
     * @since 4.0.0
     */
    private static class LocalTmpMultipartStorage implements MultipartStorage {

        /**
         * 文件存储
         */
        private final FileStorage fileStorage;
        /**
         * 上下文
         */
        private final Map<String, MultipartContext> contexts;

        /**
         * Local Tmp Multipart Storage
         * @param fileStorage 参数
         * @return 返回值
        */
        LocalTmpMultipartStorage(FileStorage fileStorage) {
            this.fileStorage = fileStorage;
            this.contexts = new ConcurrentHashMap<>();
        }

        /**
         * Initiate
         * @param request 请求
         */
        @Override
        public MultipartPartResult initiate(PutObjectRequest request) {
            String uploadId = IdUtils.simpleUuid();
            try {
                Path tempDir = Files.createTempDirectory("multipart-" + uploadId + "-");
                contexts.put(uploadId, new MultipartContext(request, tempDir));
                return MultipartPartResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.SUCCESS)
                        .uploadId(uploadId)
                        .build();
            } catch (IOException e) {
                return MultipartPartResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.FAILURE)
                        .message("创建临时目录失败: " + e.getMessage())
                        .build();
            }
        }

        /**
         * uploadpart
         * @param request 请求
         */
        @Override
        public MultipartPartResult uploadPart(MultipartUploadPartRequest request) {
            MultipartContext ctx = contexts.get(request.getUploadId());
            if (ctx == null) {
                return MultipartPartResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.FAILURE)
                        .message("上传任务不存在: " + request.getUploadId())
                        .build();
            }
            try {
                Path partFile = ctx.tempDir.resolve(String.valueOf(request.getPartNumber()));
                Files.write(partFile, request.getContent());
                return MultipartPartResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.SUCCESS)
                        .uploadId(request.getUploadId())
                        .partNumber(request.getPartNumber())
                        .partSize((long) request.getContent().length)
                        .build();
            } catch (IOException e) {
                return MultipartPartResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.FAILURE)
                        .message("写入分片失败: " + e.getMessage())
                        .build();
            }
        }

        /**
         * 完成
         * @param uploadId uploadid
         * @param parts parts
         */
        @Override
        public PutObjectResult complete(String uploadId, List<PartETag> parts) {
            MultipartContext ctx = contexts.remove(uploadId);
            if (ctx == null) {
                return PutObjectResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.FAILURE)
                        .message("上传任务不存在: " + uploadId)
                        .build();
            }
            try {
                byte[] merged = mergeParts(ctx, parts);
                PutObjectRequest putRequest = PutObjectRequest.builder()
                        .fileName(ctx.request.getFileName())
                        .filePath(ctx.request.getFilePath())
                        .metadata(com.chua.common.support.storage.metadata.Metadata.builder()
                                .contentType(ctx.request.getMetadata() != null
                                        ? ctx.request.getMetadata().getContentType()
                                        : null)
                                .build())
                        .content(merged)
                        .build();
                return fileStorage.putObject(putRequest);
            } catch (Exception e) {
                return PutObjectResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.FAILURE)
                        .message("合并上传失败: " + e.getMessage())
                        .build();
            } finally {
                deleteTempDir(ctx.tempDir);
            }
        }

        /**
         * Abort
         * @param uploadId uploadid
         */
        @Override
        public DeleteObjectResult abort(String uploadId) {
            MultipartContext ctx = contexts.remove(uploadId);
            if (ctx == null) {
                return DeleteObjectResult.builder()
                        .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.SUCCESS)
                        .build();
            }
            deleteTempDir(ctx.tempDir);
            return DeleteObjectResult.builder()
                    .resultCode(com.chua.common.support.storage.result.ObjectResult.ResultCode.SUCCESS)
                    .build();
        }

        /**
         * 合并Parts
         * @param ctx ctx
         * @param parts parts
         * @return 合并parts的结果
         * @throws IOException 异常
        */
        private byte[] mergeParts(MultipartContext ctx, List<PartETag> parts) throws IOException {
            parts.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
            long totalSize = parts.stream().mapToLong(p -> {
                try {
                    return Files.size(ctx.tempDir.resolve(String.valueOf(p.getPartNumber())));
                } catch (IOException e) {
                    throw new RuntimeException("获取分片文件大小失败: " + e.getMessage(), e);
                }
            }).sum();
            byte[] merged = new byte[(int) totalSize];
            int pos = 0;
            for (PartETag part : parts) {
                Path partFile = ctx.tempDir.resolve(String.valueOf(part.getPartNumber()));
                byte[] data = Files.readAllBytes(partFile);
                System.arraycopy(data, 0, merged, pos, data.length);
                pos += data.length;
            }
            return merged;
        }

        /**
         * 删除tempdir
         * @param tempDir tempdir
         */
        private void deleteTempDir(Path tempDir) {
            try {
                if (Files.exists(tempDir)) {
                    Files.walk(tempDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    // ignore
                                }
                            });
                }
            } catch (IOException e) {
                // ignore
            }
        }
        /**
         * 私募 record multipart上下文(放入对象请求 请求, 路径 tempdir) {
         *
         * @author CH
         * @since 4.0.0.42
         * @param request 请求
         * @param tempDir tempdir
         * @return multipart上下文的结果
         */

        private record MultipartContext(PutObjectRequest request, Path tempDir) {
        }
    }
}
