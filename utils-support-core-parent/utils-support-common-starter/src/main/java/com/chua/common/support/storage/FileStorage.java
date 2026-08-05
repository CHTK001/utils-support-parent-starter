package com.chua.common.support.storage;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.MultipartUploadPartRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.ExistObjectResult;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.ListObjectResult;
import com.chua.common.support.storage.result.MultipartPartResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;

/**
 * 文件存储 SPI（Service Provider Interface）接口。
 *
 * <p>定义统一的文件存储操作契约，支持对象存储（OSS/S3/COS 等）、
 * WebDAV、文件系统等多种存储后端的接入。</p>
 *
 * <p>核心操作：</p>
 * <ul>
 *   <li>{@link #putObject(PutObjectRequest)} — 上传/存储文件</li>
 *   <li>{@link #getObject(GetObjectRequest)} — 获取/下载文件</li>
 *   <li>{@link #deleteObject(String)} — 删除文件</li>
 *   <li>{@link #existObject(ExistObjectRequest)} — 检查文件是否存在</li>
 *   <li>{@link #listObject(ListObjectRequest)} — 列出目录下的文件</li>
 * </ul>
 *
 * @author CH
 * @since 1.0
 */
@Spi
public interface FileStorage extends AutoCloseable {

    /**
     * 上传文件（通过请求对象）。
     *
     * @param request 上传请求，包含文件内容、文件名、路径等
     * @return 上传结果
     */
    PutObjectResult putObject(PutObjectRequest request);

    /**
     * 获取/下载文件（通过请求对象）。
     *
     * @param request 下载请求，包含文件名、路径等
     * @return 下载结果，包含文件内容和元数据
     */
    GetObjectResult getObject(GetObjectRequest request);

    /**
     * 获取/下载文件（通过 Key 字符串）。
     *
     * <p>将 Key（如 "dir/file.txt"）解析为 {@link GetObjectRequest} 后调用核心方法。</p>
     *
     * @param key 文件 Key，支持 "path/file.ext" 格式
     * @return 下载结果
     */
    GetObjectResult getObject(String key);

    /**
     * 删除文件。
     *
     * @param key 要删除的文件 Key
     * @return 删除结果
     */
    DeleteObjectResult deleteObject(String key);

    /**
     * 检查文件是否存在。
     *
     * @param request 检查请求
     * @return 存在性检查结果
     */
    ExistObjectResult existObject(ExistObjectRequest request);

    /**
     * 列出指定路径下的文件。
     *
     * @param request 列表请求，包含路径、分页标记等
     * @return 文件列表结果
     */
    ListObjectResult listObject(ListObjectRequest request);

    /**
     * 通过 SPI 创建文件存储实例。
     *
     * @param name          存储类型名称（如 "oss"、"s3"、"bos" 等）
     * @param bucketSetting 存储配置
     * @return 文件存储实例
     */
    static FileStorage createStorage(String name, BucketSetting bucketSetting) {
        return com.chua.common.support.spi.ServiceProvider.of(FileStorage.class)
                .getNewExtension(name, bucketSetting);
    }

    /**
     * 创建分片上传存储。
     *
     * <p>每个子类必须提供自己的实现，不支持原生分片协议的后端可使用基于本地临时目录的默认实现。</p>
     *
     * @return 分片上传存储实例
     */
    MultipartStorage createMultipartStorage();
}
