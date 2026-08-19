package com.chua.webdav.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import com.github.sardine.DavResource;
import com.github.sardine.Sardine;
import com.github.sardine.SardineFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 简单 WebDAV 文件存储实现。
 *
 * <p>基于 Sardine WebDAV 库实现 {@link FileStorage} SPI 接口。
 * 支持通过 WebDAV 协议与各种 WebDAV 服务器（如 Nginx WebDAV、Apache mod_dav、
 * NextCloud、ownCloud 等）进行文件存储操作。</p>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * BucketSetting setting = BucketSetting.builder()
 *     .endpoint("https://webdav.example.com")
 *     .accessKeyId("username")
 *     .accessKeySecret("password")
 *     .bucket("remote-path")
 *     .build();
 *
 * FileStorage storage = new SWebdavFileStorage(setting);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"webdav", "swebdav"})
public class WebdavFileStorage extends AbstractFileStorage {

    /**
     * sardine
     */
    private final Sardine sardine;
    /**
     * 基础地址
     */
    private final String baseUrl;

    /**
     * 创建 WebdavFileStorage 实例
     * @param bucketSetting bucketSetting
     */
    public WebdavFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        this.sardine = SardineFactory.begin(accessKeyId, accessKeySecret);
        String url = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        this.baseUrl = bucket != null && !bucket.isEmpty() ? url + bucket + "/" : url;
    }

    /** FullUrl */
    private String fullUrl(String key) {
        return baseUrl + key;
    }

    @Override
    /** PutObject */
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = request.getKey();
            String url = fullUrl(key);
            // 确保父目录存在
            ensureParentPath(key);
            sardine.put(url, request.getContent(), request.getMetadata() != null ? 
                    request.getMetadata().getContentType() : "application/octet-stream");

            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .key(key)
                    .url(url)
                    .build();
        } catch (Exception e) {
            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.FAILURE)
                    .message(e.getMessage())
                    .build();
        }
    }

    /**
     * 确保父目录存在，不存在则递归创建。
     */
    private void ensureParentPath(String key) throws IOException {
        if (!key.contains("/")) {
            return;
        }
        String parent = key.substring(0, key.lastIndexOf('/'));
        String[] parts = parent.split("/");
        StringBuilder path = new StringBuilder(baseUrl);
        for (String part : parts) {
            path.append(part).append("/");
            if (!sardine.exists(path.toString())) {
                sardine.createDirectory(path.toString());
            }
        }
    }

    @Override
    /** 获取Object */
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String key = request.getKey();
            InputStream is = sardine.get(fullUrl(key));

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(is)
                    .metadata(Metadata.builder()
                            .name(request.getFileName())
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
    /** 获取Object */
    public GetObjectResult getObject(String key) {
        String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
        String path = key.contains("/") ? key.substring(0, key.lastIndexOf('/')) : "";
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    /** 删除Object */
    public DeleteObjectResult deleteObject(String key) {
        try {
            sardine.delete(fullUrl(key));
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
    /** ExistObject */
    public ExistObjectResult existObject(ExistObjectRequest request) {
        try {
            boolean exists = sardine.exists(fullUrl(request.getKey()));
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
    /** ListObject */
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            String path = request.getFilePath() != null ? request.getFilePath() : "";
            // WebDAV 协议原生不支持分页，忽略 marker 参数
            List<DavResource> resources = sardine.list(fullUrl(path));
            List<Metadata> metadataList = new ArrayList<>();

            for (DavResource resource : resources) {
                // 跳过当前目录引用
                if (".".equals(resource.getName()) || resource.getName() == null) {
                    continue;
                }
                metadataList.add(Metadata.builder()
                        .name(resource.getName())
                        .size(resource.getContentLength())
                        .contentType(resource.getContentType())
                        .lastModified(resource.getModified() != null ? 
                                resource.getModified().getTime() : 0)
                        .directory(resource.isDirectory())
                        .path(request.getFilePath())
                        .build());
            }

            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
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
    /** 关闭 */
    public void close() {
        try {
            sardine.shutdown();
        } catch (Exception ignored) {
        }
    }
}
