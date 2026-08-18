package com.chua.google.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.ExistObjectResult;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.ListObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.*;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Cloud Storage 文件存储实现。
 *
 * <p>基于 Google Cloud Storage SDK 实现 {@link FileStorage} SPI 接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"gcs", "google"})
public class GoogleCloudFileStorage extends AbstractFileStorage {

    /** 对象存储客户端 */
    /** 存储 */
    private final Storage storage;

    public GoogleCloudFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        // 凭据解析策略：优先使用 accessKeySecret 作为服务账号 JSON 密钥，失败时降级为 ADC
        Credentials credentials = resolveCredentials(accessKeySecret);
        this.storage = StorageOptions.newBuilder()
                .setCredentials(credentials)
                .setProjectId(bucket)
                .build()
                .getService();
    }

    /**
     * 解析 GCS 凭据。
     *
     * <p>解析优先级：
     * <ol>
     *   <li>将 {@code secret} 作为服务账号 JSON 密钥内容解析</li>
     *   <li>若解析失败，降级为 {@link GoogleCredentials#getApplicationDefault()}（ADC 凭据链）</li>
     * </ol>
     *
     * <p>ADC 凭据链包括：
     * <ul>
     *   <li>{@code GOOGLE_APPLICATION_CREDENTIALS} 环境变量指向的 JSON 文件</li>
     *   <li>Google Cloud SDK 默认凭据（{@code gcloud auth application-default login}）</li>
     *   <li>GCE/GKE 元数据服务（运行在 Google Cloud 上时自动获取）</li>
     * </ul>
     *
     * @param secret 密钥内容（JSON 字符串或任意字符串）
     * @return 解析后的凭据，不会为 null
     */
    private static Credentials resolveCredentials(String secret) {
        // 尝试将 secret 作为服务账号 JSON 密钥解析
        if (secret != null && !secret.isEmpty()) {
            try {
                // 快速检查：是否以 JSON 对象开头
                String trimmed = secret.trim();
                if (trimmed.startsWith("{")) {
                    return ServiceAccountCredentials
                            .fromStream(new ByteArrayInputStream(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            } catch (Exception ignored) {
                // JSON 解析失败，降级到 ADC
            }
        }

        // 降级：使用 Application Default Credentials
        try {
            return GoogleCredentials.getApplicationDefault();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法创建 GCS 凭据：accessKeySecret 不是有效的服务账号 JSON，且 ADC 也未配置。" +
                    "请设置 GOOGLE_APPLICATION_CREDENTIALS 环境变量或运行 gcloud auth application-default login。", e);
        }
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            BlobId blobId = BlobId.of(bucket, key);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType(request.getMetadata() != null ? request.getMetadata().getContentType() : null)
                    .build();
            storage.create(blobInfo, request.getContent());

            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .key(key)
                    .build();
        } catch (Exception e) {
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String key = request.getKey();
            Blob blob = storage.get(BlobId.of(bucket, key));
            if (blob == null) {
                return GetObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.FAILURE)
                        .message("文件不存在: " + key)
                        .build();
            }

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(new java.io.ByteArrayInputStream(blob.getContent()))
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(blob.getSize())
                            .contentType(blob.getContentType())
                            .lastModified(blob.getUpdateTime())
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
    public GetObjectResult getObject(String key) {
        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        String path = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    public DeleteObjectResult deleteObject(String key) {
        try {
            storage.delete(BlobId.of(bucket, key));
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
    public ExistObjectResult existObject(ExistObjectRequest request) {
        try {
            Blob blob = storage.get(BlobId.of(bucket, request.getKey()));
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(blob != null)
                    .build();
        } catch (Exception e) {
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            // 构建 GCS 列表选项：前缀 + 页大小
            Storage.BlobListOption[] options;
            String marker = request.getMarker();
            if (marker != null && !marker.isEmpty()) {
                options = new Storage.BlobListOption[]{
                        Storage.BlobListOption.prefix(request.getFilePath()),
                        Storage.BlobListOption.pageSize(request.getLimit()),
                        Storage.BlobListOption.pageToken(marker)
                };
            } else {
                options = new Storage.BlobListOption[]{
                        Storage.BlobListOption.prefix(request.getFilePath()),
                        Storage.BlobListOption.pageSize(request.getLimit())
                };
            }

            var result = storage.list(bucket, options);
            List<Metadata> metadataList = new ArrayList<>();
            for (Blob blob : result.getValues()) {
                metadataList.add(Metadata.builder()
                        .name(blob.getName())
                        .size(blob.getSize())
                        .contentType(blob.getContentType())
                        .lastModified(blob.getUpdateTime())
                        .build());
            }

            // 分页：返回下一页的 pageToken
            String nextMarker = result.getNextPageToken();

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
    public void close() {
        // Google Cloud Storage 客户端由 SDK 内部管理连接池
    }
}
