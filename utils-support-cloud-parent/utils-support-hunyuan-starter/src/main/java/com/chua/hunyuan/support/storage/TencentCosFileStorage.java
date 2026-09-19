package com.chua.hunyuan.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.region.Region;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯云 COS（Cloud 对象 Storage）文件存储实现。
 *
 * <p>基于腾讯云 COS Java SDK 实现 {@link FileStorage} SPI 接口，
 * 提供对象存储的上传、下载、删除、存在性检查、列表等操作。</p>
 *
 * <p>配置说明：</p>
 * <ul>
 *   <li>endpoint — COS 访问域名，如 {@code https://cos.ap-guangzhou.myqcloud.com}</li>
 *   <li>region — 地域，如 {@code ap-guangzhou}（广州）、{@code ap-beijing}（北京）</li>
 *   <li>bucket — 存储桶名称（不含 APPID 后缀，SDK 自动拼接）</li>
 *   <li>accessKeyId / accessKeySecret — 腾讯云 API 密钥（SecretId / SecretKey）</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * BucketSetting setting = BucketSetting.builder()
 *     .endpoint("https://cos.ap-guangzhou.myqcloud.com")
 *     .bucket("my-bucket-1250000000")
 *     .accessKeyId("AKID...")
 *     .accessKeySecret("...")
 *     .region("ap-guangzhou")
 *     .build();
 * FileStorage storage = FileStorage.createStorage("cos", setting);
 * }</pre>e("cos", setting);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"cos", "tencent"})
public class TencentCosFileStorage extends AbstractFileStorage {

    /**
     * 腾讯云 COS 客户端
    */
    private final COSClient cosClient;

    /**
     * 创建 tencentcos文件storage 实例
     * @param bucketSetting bucketsetting
     */
    public TencentCosFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        BasicCOSCredentials credentials = new BasicCOSCredentials(accessKeyId, accessKeySecret);
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        this.cosClient = new COSClient(credentials, clientConfig);
    }

    @Override
    /**
     * 放入对象
    */
    public PutObjectResult putObject(com.chua.common.support.storage.request.PutObjectRequest request) {
        try {
            String key = request.getKey();
            ObjectMetadata meta = new ObjectMetadata();
            byte[] content = request.getContent();
            meta.setContentLength(content.length);
            if (request.getMetadata() != null) {
                if (request.getMetadata().getContentType() != null) {
                    meta.setContentType(request.getMetadata().getContentType());
                }
            }

            com.qcloud.cos.model.PutObjectRequest cosPutReq =
                    new com.qcloud.cos.model.PutObjectRequest(bucket, key,
                            new ByteArrayInputStream(content), meta);
            cosClient.putObject(cosPutReq);

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
    public GetObjectResult getObject(com.chua.common.support.storage.request.GetObjectRequest request) {
        try {
            String key = request.getKey();
            com.qcloud.cos.model.GetObjectRequest cosGetReq = new com.qcloud.cos.model.GetObjectRequest(bucket, key);
            COSObject cosObject = cosClient.getObject(cosGetReq);
            ObjectMetadata meta = cosObject.getObjectMetadata();

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(cosObject.getObjectContent())
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
                            .size(meta.getContentLength())
                            .contentType(meta.getContentType())
                            .lastModified(meta.getLastModified().getTime())
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
        return getObject(com.chua.common.support.storage.request.GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    /**
     * 删除对象
    */
    public DeleteObjectResult deleteObject(String key) {
        try {
            cosClient.deleteObject(bucket, key);
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
            boolean exists = cosClient.doesObjectExist(bucket, request.getKey());
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(exists)
                    .build();
        } catch (CosServiceException e) {
            // 404 等异常也视为不存在
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(false)
                    .build();
        } catch (Exception e) {
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    @Override
    /**
     * 列表对象
    */
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            ListObjectsRequest listReq = new ListObjectsRequest();
            listReq.setBucketName(bucket);
            if (request.getFilePath() != null) {
                listReq.setPrefix(request.getFilePath());
            }
            listReq.setMaxKeys(request.getLimit());
            if (request.getMarker() != null) {
                listReq.setMarker(request.getMarker());
            }

            ObjectListing listing = cosClient.listObjects(listReq);
            List<Metadata> metadataList = new ArrayList<>();
            for (COSObjectSummary summary : listing.getObjectSummaries()) {
                metadataList.add(Metadata.builder()
                        .name(summary.getKey())
                        .size(summary.getSize())
                        .lastModified(summary.getLastModified().getTime())
                        .build());
            }

 // 分页：如果结果被截断，返回下一页的 记号笔
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
    /**
     * 关闭
    */
    public void close() {
        if (cosClient != null) {
            cosClient.shutdown();
        }
    }
}
