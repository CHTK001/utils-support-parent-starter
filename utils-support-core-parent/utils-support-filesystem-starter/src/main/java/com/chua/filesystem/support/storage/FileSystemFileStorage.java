package com.chua.filesystem.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.AbstractFileStorage;
import com.chua.common.support.storage.metadata.Metadata;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.*;
import com.chua.common.support.storage.setting.BucketSetting;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地文件系统文件存储实现。
 *
 * <p>基于 {@link java.nio.file} 实现 {@link com.chua.common.support.storage.FileStorage} SPI 接口，
 * 将文件存储到本地磁盘目录，适合开发测试或单机部署场景。</p>
 *
 * <p>配置说明：</p>
 * <ul>
 *   <li>{@code bucket} — 本地存储根目录（必填），如 "/data/storage" 或 "C:/storage"</li>
 *   <li>{@code endpoint} — 可选，备用根目录（优先级低于 bucket）</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * BucketSetting setting = BucketSetting.builder()
 *     .bucket("/tmp/filestorage")
 *     .build();
 * FileStorage storage = FileStorage.createStorage("filesystem", setting);
 *
 * // 上传文件
 * storage.putObject(PutObjectRequest.builder()
 *     .fileName("test.txt")
 *     .filePath("docs")
 *     .content("hello".getBytes())
 *     .build());
 *
 * // 下载文件
 * GetObjectResult result = storage.getObject("docs/test.txt");
 * }</pre>件
 * GetObjectResult result = storage.getObject("docs/test.txt");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi({"filesystem", "file", "local"})
public class FileSystemFileStorage extends AbstractFileStorage {

    /**
     * 基础路径
    */
    private final Path basePath;

    /**
     * 创建 文件系统文件storage 实例
     * @param bucketSetting bucketsetting
     */
    public FileSystemFileStorage(BucketSetting bucketSetting) {
        super(bucketSetting);
        String root = bucket != null && !bucket.isEmpty() ? bucket
                : endpoint != null ? endpoint
                : System.getProperty("user.home") + File.separator + "filestorage";
        this.basePath = Paths.get(root).toAbsolutePath();
        ensureBasePath();
    }

    /**
     * ensurebase路径
    */
    private void ensureBasePath() {
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("无法创建存储根目录: " + basePath, e);
        }
    }

    /**
     * 解析路径
     *
     * @param key 键
     * @return resolve路径的结果
     */
    private Path resolvePath(String key) {
        return basePath.resolve(key).normalize();
    }

    /**
     * 构建键
     *
     * @param request 请求
     * @return 构建键的结果
     */
    private String buildKey(PutObjectRequest request) {
        String path = request.getFilePath();
        String name = request.getFileName();
        if (path != null && !path.isEmpty()) {
            return path.replace('\\', '/') + "/" + name;
        }
        return name;
    }

    /**
     * 构建键
     *
     * @param request 请求
     * @return 构建键的结果
     */
    private String buildKey(GetObjectRequest request) {
        String path = request.getFilePath();
        String name = request.getFileName();
        if (path != null && !path.isEmpty()) {
            return path.replace('\\', '/') + "/" + name;
        }
        return name;
    }

    @Override
    /**
     * 放入对象
    */
    public PutObjectResult putObject(PutObjectRequest request) {
        try {
            String key = buildKey(request);
            Path target = resolvePath(key);

            // 确保父目录存在
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }

            Files.write(target, request.getContentBytes());

            return PutObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .key(key)
                    .url(target.toAbsolutePath().toString())
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
            String key = buildKey(request);
            Path source = resolvePath(key);

            if (!Files.exists(source)) {
                return GetObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.FAILURE)
                        .message("文件不存在: " + key)
                        .build();
            }

            BasicFileAttributes attrs = Files.readAttributes(source, BasicFileAttributes.class);
            String fileName = source.getFileName().toString();
            String suffix = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";

            return GetObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .inputStream(Files.newInputStream(source))
                    .metadata(Metadata.builder()
                            .name(fileName)
                            .path(source.getParent() != null ? source.getParent().toString() : "")
                            .size(attrs.size())
                            .suffix(suffix)
                            .lastModified(attrs.lastModifiedTime().toInstant().toEpochMilli())
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
            Path target = resolvePath(key);
            Files.deleteIfExists(target);
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
            String key = request.getKey();
            Path target = resolvePath(key);
            return ExistObjectResult.builder()
                    .resultCode(ObjectResult.ResultCode.SUCCESS)
                    .exists(Files.exists(target))
                    .build();
        } catch (Exception e) {
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
            String filePath = request.getFilePath();
            Path dir = filePath != null && !filePath.isEmpty() ? resolvePath(filePath) : basePath;

            if (!Files.exists(dir) || !Files.isDirectory(dir)) {
                return ListObjectResult.builder()
                        .resultCode(ObjectResult.ResultCode.SUCCESS)
                        .metadata(List.of())
                        .build();
            }

            List<Metadata> metadataList = new ArrayList<>();
            int limit = request.getLimit();
            String marker = request.getMarker();

            try (Stream<Path> stream = Files.list(dir)) {
                Comparator<Path> comparator = Comparator.comparing(p -> p.getFileName().toString());

                stream.sorted(comparator)
                        .filter(p -> {
                            String name = p.getFileName().toString();
                            return marker == null || name.compareTo(marker) > 0;
                        })
                        .limit(limit)
                        .forEach(path -> {
                            try {
                                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
                                String name = path.getFileName().toString();
                                String suffix = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
                                metadataList.add(Metadata.builder()
                                        .name(name)
                                        .path(filePath != null ? filePath : "")
                                        .size(attrs.isDirectory() ? 0 : attrs.size())
                                        .suffix(suffix)
                                        .directory(attrs.isDirectory())
                                        .lastModified(attrs.lastModifiedTime().toInstant().toEpochMilli())
                                        .build());
                            } catch (IOException ignored) {
                            }
                        });
            }

            boolean hasMore = metadataList.size() >= limit;
            String nextMarker = hasMore && !metadataList.isEmpty()
                    ? metadataList.get(metadataList.size() - 1).getName() : null;

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
}
