package com.chua.minio.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import io.minio.*;
import io.minio.messages.Item;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 最小io 文件存储实现。
 *
 * <p>基于 MinIO Java SDK（{@code io.minio:minio}）实现 {@link FileStorage} SPI 接口，<br>
 * 提供对 最小io 对象存储的完整操作支持。</p>
 *
 * <p>MinIO 是一个高性能的 S3 兼容对象存储服务器，可在本地通过 Docker 快速部署，<br>
 * 非常适合开发测试环境使用。</p>
 *
 * <p><b>本地快速启动（Docker）：</b></p>
 * <pre>{@code
 * docker run -d --name minio \
 *   -p 9000:9000 -p 9001:9001 \
 *   -e MINIO_ROOT_USER=minioadmin \
 *   -e MINIO_ROOT_PASSWORD=minioadmin \
 *   minio/minio server /data --console-address ":9001"
 * }</pre>le-address ":9001"
 * }</pre>
 *
 * <p>启动后访问 http://127.0.0.1:9001（控制台）或通过 9000 端口（API）进行操作。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * BucketSetting setting = BucketSetting.builder()
 *     .endpoint("http://127.0.0.1:9000")
 *     .bucket("test-bucket")
 *     .accessKeyId("minioadmin")
 *     .accessKeySecret("minioadmin")
 *     .region("us-east-1")
 *     .build();
 * FileStorage storage = FileStorage.createStorage("minio", setting);
 * }</pre>age("minio", setting);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"minio", "minio-s3"})
public class MinioFileStorage extends AbstractFileStorage {

    /**
     * Minio客户端
    */
    private final MinioClient minioClient;

    /**
     * 创建 minio文件storage 实例
     * @param bucketSetting bucketsetting
     */
    public MinioFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKeyId, accessKeySecret)
                .region(region)
                .build();
        ensureBucket();
    }

    /**
     * ensurebucket
    */
    private void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    /**
     * 放入对象
    */
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            byte[] content = request.getContentBytes();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(bucket)
                        .object(key)
                        .stream(bais, content.length, -1)
                        .contentType(request.getMetadata() != null
                                ? request.getMetadata().getContentType() : "application/octet-stream")
                        .build());
            }

            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .key(key)
                    .url(endpoint + "/" + bucket + "/" + key)
                    .build();
        } catch (Exception e) {
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
     * 获取对象
    */
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String key = request.getKey();

 // 获取对象内容流（获取对象响应 本身携带了对象元数据）
            GetObjectResponse response = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());

            String contentType = response.headers().get("Content-Type");

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(response)
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(response.headers().get("Content-Length") != null
                                    ? Long.parseLong(Objects.requireNonNull(response.headers().get("Content-Length"))) : null)
                            .contentType(contentType)
                            .etag(response.headers().get("ETag") != null
                                    ? Objects.requireNonNull(response.headers().get("ETag")).replace("\"", "") : null)
                            .build())
                    .build();
        } catch (Exception e) {
            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
     * 获取对象
    */
    public GetObjectResult getObject(String key) {
        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        String path = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    /**
     * 删除对象
    */
    public DeleteObjectResult deleteObject(String key) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return DeleteObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .build();
        } catch (Exception e) {
            return DeleteObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
     * exist对象
    */
    public ExistObjectResult existObject(ExistObjectRequest request) {
        try {
            minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(request.getKey())
                    .build());
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(true)
                    .build();
        } catch (Exception e) {
 // 最小io SDK 在对象不存在时抛出异常（错误响应异常），视为不存在
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(false)
                    .build();
        }
    }

    @Override
    /**
     * 列表对象
    */
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            List<Metadata> metadataList = new ArrayList<>();

            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucket)
                            .prefix(request.getFilePath())
                            .maxKeys(request.getLimit())
                            .build());

            String lastKey = null;
            for (Result<Item> result : results) {
                Item item = result.get();
                lastKey = item.objectName();
                metadataList.add(Metadata.builder()
                        .name(item.objectName())
                        .size(item.size())
                        .lastModified(item.lastModified() != null
                                ? item.lastModified().toInstant().toEpochMilli() : 0)
                        .etag(item.etag())
                        .build());
            }

 // 最小io SDK 的 可迭代 在迭代完所有结果后自动终止，
            // 无法直接获取 "是否还有下一页" 的信息。
 // 这里使用最后一条记录的 键 作为 记号笔 的简化方案。
            boolean hasMore = metadataList.size() >= request.getLimit();
            String nextMarker = hasMore ? lastKey : null;

            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .marker(nextMarker)
                    .metadata(metadataList)
                    .build();
        } catch (Exception e) {
            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
 // minio客户端 实现了 auto关闭，调用其 关闭 方法释放资源
        if (minioClient != null) {
            try {
                minioClient.close();
            } catch (Exception ignored) {
                // 忽略关闭时的异常
            }
        }
    }
}
