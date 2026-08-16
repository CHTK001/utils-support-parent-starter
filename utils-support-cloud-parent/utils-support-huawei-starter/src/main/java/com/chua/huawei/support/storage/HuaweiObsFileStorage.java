package com.chua.huawei.support.storage;

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
import com.obs.services.ObsClient;
import com.obs.services.model.ListObjectsRequest;
import com.obs.services.model.ObjectListing;
import com.obs.services.model.ObjectMetadata;
import com.obs.services.model.ObsObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 华为云 OBS 文件存储实现。
 *
 * <p>基于华为云 OBS SDK 实现 {@link FileStorage} SPI 接口。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"obs", "huawei"})
public class HuaweiObsFileStorage extends AbstractFileStorage {

    private final ObsClient obsClient;

    public HuaweiObsFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        this.obsClient = new ObsClient(accessKeyId, accessKeySecret, endpoint);
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            obsClient.putObject(bucket, key, 
                    new ByteArrayInputStream(request.getContent()));

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
            ObsObject obsObject = obsClient.getObject(bucket, key);
            ObjectMetadata meta = obsObject.getMetadata();

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(obsObject.getObjectContent())
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(meta.getContentLength())
                            .contentType(meta.getContentType())
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
            obsClient.deleteObject(bucket, key);
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
            boolean exists = obsClient.doesObjectExist(bucket, request.getKey());
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
            // 设置分页 marker
            if (request.getMarker() != null) {
                listReq.setMarker(request.getMarker());
            }

            ObjectListing listing = obsClient.listObjects(listReq);
            List<Metadata> metadataList = new ArrayList<>();
            for (ObsObject obj : listing.getObjects()) {
                metadataList.add(Metadata.builder()
                        .name(obj.getObjectKey())
                        .size(obj.getMetadata().getContentLength())
                        .lastModified(obj.getMetadata().getLastModified().getTime())
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
        if (obsClient != null) {
            try {
                obsClient.close();
            } catch (Exception ignored) {
            }
        }
    }
}
