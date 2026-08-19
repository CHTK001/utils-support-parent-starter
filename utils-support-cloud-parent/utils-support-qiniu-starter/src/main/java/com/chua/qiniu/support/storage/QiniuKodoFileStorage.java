package com.chua.qiniu.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.util.Auth;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 七牛云 Kodo 文件存储实现。
 *
 * <p>基于七牛云 Java SDK 实现 {@link FileStorage} SPI 接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"kodo", "qiniu"})
public class QiniuKodoFileStorage extends AbstractFileStorage {

    /** 鉴权客户端 */
    /** Auth */
    private final Auth auth;
    /** 上传管理器 */
    private final UploadManager uploadManager;
    /** Bucket 管理器 */
    /** 存储桶管理器 */
    private final BucketManager bucketManager;

    public QiniuKodoFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        Configuration cfg = new Configuration(Region.autoRegion());
        this.auth = Auth.create(accessKeyId, accessKeySecret);
        this.uploadManager = new UploadManager(cfg);
        this.bucketManager = new BucketManager(auth, cfg);
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            String upToken = auth.uploadToken(bucket);
            com.qiniu.http.Response response = uploadManager.put(
                    request.getContent(), key, upToken);

            if (response.isOK()) {
                return PutObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.SUCCESS)
                        .key(key)
                        .build();
            }
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(response.error)
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
            String downloadUrl = auth.privateDownloadUrl(
                    endpoint + "/" + key, 3600);
            java.net.URL url = new java.net.URL(downloadUrl);

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(url.openStream())
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .contentType(url.openConnection().getContentType())
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
            bucketManager.delete(bucket, key);
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
            com.qiniu.storage.model.FileInfo info = bucketManager.stat(bucket, request.getKey());
            boolean exists = info != null;
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(exists)
                    .build();
        } catch (Exception e) {
            if (e instanceof QiniuException && ((QiniuException) e).code() == 612) {
                return ExistObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.SUCCESS)
                        .exists(false)
                        .build();
            }
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            // 使用 marker 作为分页起始（七牛用空字符串表示从头开始）
            String marker = request.getMarker() != null ? request.getMarker() : "";
            BucketManager.FileListIterator iterator = bucketManager.createFileListIterator(
                    bucket, request.getFilePath(), request.getLimit(), marker);
            List<Metadata> metadataList = new ArrayList<>();

            // 只获取一页数据（不循环所有页），以支持 marker 分页
            if (iterator.hasNext()) {
                FileInfo[] items = iterator.next();
                if (items != null) {
                    for (FileInfo item : items) {
                        metadataList.add(Metadata.builder()
                                .name(item.key)
                                .size(item.fsize)
                                .lastModified(item.putTime / 10000)
                                .build());
                    }
                }
                // 记录最后一个文件的 key 作为下一页的 marker
                if (!metadataList.isEmpty()) {
                    marker = metadataList.get(metadataList.size() - 1).getName();
                }
            }

            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .marker(marker)
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
        // 七牛云客户端无需显式关闭
    }
}
