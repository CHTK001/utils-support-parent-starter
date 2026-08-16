package com.chua.alibaba.support.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.*;
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
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云 OSS 文件存储实现。
 *
 * <p>基于阿里云 OSS SDK 实现 {@link FileStorage} SPI 接口，提供对象存储的上传、下载、删除、列表等操作。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("oss")
public class AliYunFileStorage extends AbstractFileStorage {

    private final OSS ossClient;

    public AliYunFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            com.aliyun.oss.model.PutObjectRequest putReq = new com.aliyun.oss.model.PutObjectRequest(bucket, key,
                    new ByteArrayInputStream(request.getContent()));
            ossClient.putObject(putReq);

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
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String key = request.getKey();
            com.aliyun.oss.model.GetObjectRequest getReq = new com.aliyun.oss.model.GetObjectRequest(bucket, key);
            OSSObject ossObject = ossClient.getObject(getReq);

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(ossObject.getObjectContent())
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(ossObject.getObjectMetadata().getContentLength())
                            .contentType(ossObject.getObjectMetadata().getContentType())
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
            ossClient.deleteObject(bucket, key);
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
            boolean exists = ossClient.doesObjectExist(bucket, request.getKey());
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(exists)
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
            ListObjectsRequest listReq = new ListObjectsRequest(bucket);
            listReq.setPrefix(request.getFilePath());
            listReq.setMaxKeys(request.getLimit());
            if (request.getMarker() != null) {
                listReq.setMarker(request.getMarker());
            }

            ObjectListing listing = ossClient.listObjects(listReq);
            List<Metadata> metadataList = new ArrayList<>();
            for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                metadataList.add(Metadata.builder()
                        .name(summary.getKey())
                        .size(summary.getSize())
                        .lastModified(summary.getLastModified().getTime())
                        .build());
            }

            // 分页：如果结果被截断，返回下一页的 marker
            boolean truncated = listing.isTruncated();
            String nextMarker = truncated ? listing.getNextMarker() : null;

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
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }
}
