package com.chua.smb.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;
import com.chua.smb.client.SmbClient;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SMB 文件存储实现。
 *
 * <p>基于 smb-jna 实现 {@link com.chua.common.support.storage.FileStorage} SPI 接口。</p>
 *
 * <p>{@link BucketSetting} 映射规则：</p>
 * <ul>
 *   <li>endpoint     → SMB 服务器地址（如 "192.168.1.10" 或 "smb://192.168.1.10:445"）</li>
 *   <li>bucket       → share 名称</li>
 *   <li>accessKeyId  → SMB 用户名</li>
 *   <li>accessKeySecret → SMB 密码</li>
 * </ul>
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * BucketSetting setting = BucketSetting.builder()
 *     .endpoint("192.168.1.10")
 *     .bucket("shared")
 *     .accessKeyId("admin")
 *     .accessKeySecret("password")
 *     .build();
 *
 * FileStorage storage = FileStorage.createStorage("smb", setting);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("smb")
public class SmbFileStorage extends AbstractFileStorage {

    /**
     * smb Client
     */
    private final SmbClient smbClient;

    /**
     * 创建 SmbFileStorage 实例
     * @param bucketSetting bucketSetting
     */
    public SmbFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);

        String host = endpoint != null ? endpoint.replaceFirst("^smb://", "") : "127.0.0.1";
        String shareName = bucket != null ? bucket : "smbshare";
        String user = accessKeyId != null ? accessKeyId : "guest";
        String pass = accessKeySecret != null ? accessKeySecret : "";

        int port = 445;
        int slash = host.indexOf('/');
        if (slash > 0) {
            host = host.substring(0, slash);
        }
        int colon = host.indexOf(':');
        if (colon > 0) {
            port = Integer.parseInt(host.substring(colon + 1));
            host = host.substring(0, colon);
        }

        String uri = String.format("smb://%s:%s@%s:%d/%s",
                user, pass, host, port, shareName);
        this.smbClient = SmbClient.create(uri);
    }

    /** EnsureConnected */
    private void ensureConnected() {
        if (smbClient != null) {
            smbClient.connect().login().openShare();
        }
    }

    @Override
    /** PutObject */
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = normalizeKey(request.getKey());
            ensureConnected();
            byte[] bytes = request.getContentBytes();
            if (bytes == null) {
                return PutObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.FAILURE)
                        .message("文件内容为空")
                        .build();
            }
            smbClient.upload(new java.io.ByteArrayInputStream(bytes), key);
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
    /** 获取Object */
    public GetObjectResult getObject(GetObjectRequest request) {
        try {
            String key = normalizeKey(request.getKey());
            ensureConnected();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            smbClient.download(key, baos);
            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(new java.io.ByteArrayInputStream(baos.toByteArray()))
                    .metadata(Metadata.builder().name(extractName(key)).build())
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
        String name = extractName(key);
        String path = extractPath(key);
        return getObject(GetObjectRequest.builder().fileName(name).filePath(path).build());
    }

    @Override
    /** 删除Object */
    public DeleteObjectResult deleteObject(String key) {
        try {
            ensureConnected();
            smbClient.delete(normalizeKey(key));
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
            ensureConnected();
            String key = normalizeKey(request.getKey());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            smbClient.download(key, baos);
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(true)
                    .build();
        } catch (Exception e) {
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(false)
                    .build();
        }
    }

    @Override
    /** ListObject */
    public ListObjectResult listObject(ListObjectRequest request) {
        try {
            String path = request.getFilePath() != null ? request.getFilePath() : "/";
            ensureConnected();
            List<SmbClient.SmbFileEntry> entries = smbClient.listFiles(path);
            List<Metadata> metadata = new ArrayList<>();
            for (SmbClient.SmbFileEntry entry : entries) {
                metadata.add(Metadata.builder()
                        .name(entry.name())
                        .size(entry.size())
                        .directory(entry.isDirectory())
                        .lastModified(entry.lastModified())
                        .path(entry.path())
                        .build());
            }
            return ListObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .metadata(metadata)
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
        try { if (smbClient != null) smbClient.close(); } catch (Exception ignored) {}
    }

    /** NormalizeKey */
    private static String normalizeKey(String key) {
        if (key == null) {
            return "/";
        }
        return key.replace('\\', '/').replaceAll("^/+", "/");
    }

    /** ExtractName */
    private static String extractName(String key) {
        int i = key.lastIndexOf('/');
        return i >= 0 ? key.substring(i + 1) : key;
    }

    /** ExtractPath */
    private static String extractPath(String key) {
        int i = key.lastIndexOf('/');
        return i > 0 ? key.substring(0, i) : "/";
    }
}
