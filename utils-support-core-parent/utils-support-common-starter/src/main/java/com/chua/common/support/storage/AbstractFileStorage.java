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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullUnmarked;

/**
 * 文件存储抽象基类。
 *
 * <p>提供 Bucket 初始化、检查等公共逻辑，简化具体实现的开发。</p>
 *
 * @author CH
 * @since 1.0
 */
@NullUnmarked
public abstract class AbstractFileStorage implements FileStorage {

    protected final BucketSetting bucketSetting;
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
     * AccessKey ID
     */
    protected final String accessKeyId;
    /**
     * AccessKey Secret
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

    @Override
    public void close() {
        // 子类可覆盖实现资源释放
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
     */
    private static class LocalTmpMultipartStorage implements MultipartStorage {

        private final FileStorage fileStorage;
        private final Map<String, MultipartContext> contexts;

        LocalTmpMultipartStorage(FileStorage fileStorage) {
            this.fileStorage = fileStorage;
            this.contexts = new ConcurrentHashMap<>();
        }

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

        @Override
        public MultipartPartResult uploadPart(com.chua.common.support.storage.request.MultipartUploadPartRequest request) {
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
                                .contentType(ctx.request.getMetadata() != null ? ctx.request.getMetadata().getContentType() : null)
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

        private byte[] mergeParts(MultipartContext ctx, List<PartETag> parts) throws IOException {
            parts.sort((a, b) -> Integer.compare(a.getPartNumber(), b.getPartNumber()));
            long totalSize = parts.stream().mapToLong(p -> {
                try {
                    return Files.size(ctx.tempDir.resolve(String.valueOf(p.getPartNumber())));
                } catch (IOException e) {
                    throw new RuntimeException(e);
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

        private record MultipartContext(PutObjectRequest request, Path tempDir) {
        }
    }
}
