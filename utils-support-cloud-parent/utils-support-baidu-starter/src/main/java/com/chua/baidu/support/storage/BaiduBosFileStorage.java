package com.chua.baidu.support.storage;

import com.baidubce.auth.DefaultBceCredentials;
import com.baidubce.services.bos.BosClient;
import com.baidubce.services.bos.BosClientConfiguration;
import com.baidubce.services.bos.model.*;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 百度云 BOS 文件存储实现。
 *
 * <p>基于百度云 BOS SDK 实现 {@link FileStorage} SPI 接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"bos", "baidu"})
public class BaiduBosFileStorage extends AbstractFileStorage {

    private final BosClient bosClient;

    public BaiduBosFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        BosClientConfiguration config = new BosClientConfiguration();
        config.setCredentials(new DefaultBceCredentials(accessKeyId, accessKeySecret));
        config.setEndpoint(endpoint);
        this.bosClient = new BosClient(config);
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            com.baidubce.services.bos.model.PutObjectRequest putRequest =
                    new com.baidubce.services.bos.model.PutObjectRequest(
                            bucket, key, new ByteArrayInputStream(request.getContent())
                    );
            bosClient.putObject(putRequest);

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
            BosObject bosObject = bosClient.getObject(bucket, key);
            ObjectMetadata metadata = bosObject.getObjectMetadata();

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(bosObject.getObjectContent())
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(metadata.getContentLength())
                            .contentType(metadata.getContentType())
                            .lastModified(metadata.getLastModified().getTime())
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
            bosClient.deleteObject(bucket, key);
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
            boolean exists = bosClient.doesObjectExist(bucket, request.getKey());
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
            listReq.withPrefix(request.getFilePath());
            listReq.withMaxKeys(request.getLimit());
            // 设置分页 marker
            if (request.getMarker() != null) {
                listReq.withMarker(request.getMarker());
            }

            ListObjectsResponse listing = bosClient.listObjects(listReq);
            List<Metadata> metadataList = new ArrayList<>();
            for (BosObjectSummary summary : listing.getContents()) {
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
        if (bosClient != null) {
            bosClient.shutdown();
        }
    }
}
