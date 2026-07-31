package com.chua.amazon.support.storage;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Amazon S3 文件存储实现（兼容所有 S3 协议存储：MinIO、Ceph、JuiceFS 等）。
 *
 * <p>基于 AWS SDK for Java S3 实现 {@link FileStorage} SPI 接口。</p>
 *
 * @author CH
 * @since 1.0
 */
@Spi({"s3", "amazon"})
public class AmazonS3FileStorage extends AbstractFileStorage {

    private final AmazonS3 s3Client;

    public AmazonS3FileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        BasicAWSCredentials credentials = new BasicAWSCredentials(accessKeyId, accessKeySecret);
        this.s3Client = AmazonS3ClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withEndpointConfiguration(new AwsClientBuilder.EndpointConfiguration(endpoint, region))
                .withPathStyleAccessEnabled(true)
                .build();
    }

    @Override
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(request.getContent().length);
            s3Client.putObject(bucket, key, new ByteArrayInputStream(request.getContent()), meta);

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
            S3Object s3Object = s3Client.getObject(bucket, key);
            ObjectMetadata meta = s3Object.getObjectMetadata();

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(s3Object.getObjectContent())
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
    public GetObjectResult getObject(String key) {
        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        String path = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    public DeleteObjectResult deleteObject(String key) {
        try {
            s3Client.deleteObject(bucket, key);
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
            boolean exists = s3Client.doesObjectExist(bucket, request.getKey());
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
            ListObjectsV2Request listReq = new ListObjectsV2Request()
                    .withBucketName(bucket)
                    .withPrefix(request.getFilePath())
                    .withMaxKeys(request.getLimit());
            // S3 V2 API 使用 continuationToken 代替 marker
            if (request.getMarker() != null) {
                listReq.withContinuationToken(request.getMarker());
            }

            ListObjectsV2Result listing = s3Client.listObjectsV2(listReq);

            List<Metadata> metadataList = new ArrayList<>();
            for (S3ObjectSummary summary : listing.getObjectSummaries()) {
                metadataList.add(Metadata.builder()
                        .name(summary.getKey())
                        .size(summary.getSize())
                        .lastModified(summary.getLastModified().getTime())
                        .build());
            }

            // 分页：如果结果被截断，返回下一页的 continuationToken
            boolean truncated = listing.isTruncated();
            String nextMarker = truncated ? listing.getNextContinuationToken() : null;

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
        if (s3Client != null) {
            s3Client.shutdown();
        }
    }
}
