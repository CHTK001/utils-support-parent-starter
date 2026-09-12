package com.chua.common.support.storage;

import com.chua.common.support.storage.request.MultipartUploadPartRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;

import java.util.List;

/**
 * 分片上传存储接口。
 *
 * <p>提供分片上传的四个核心能力：初始化、上传分片、完成合并、取消。</p>
 *
 * @author CH
 * @since 1.0
 */
public interface MultipartStorage {

    /**
     * 初始化分片上传任务。
     *
     * @param request 上传请求
     * @return 初始化结果，包含 uploadid
     */
    com.chua.common.support.storage.result.MultipartPartResult initiate(PutObjectRequest request);

    /**
     * 上传分片。
     *
     * @param request 分片上传请求
     * @return 上传结果，包含 part数字、etag
     */
    com.chua.common.support.storage.result.MultipartPartResult uploadPart(MultipartUploadPartRequest request);

    /**
     * 完成分片上传，合并所有分片。
     *
     * @param uploadId 上传任务 标识
     * @param parts 分片标签列表
     * @return 上传结果
     */
    PutObjectResult complete(String uploadId, List<PartETag> parts);

    /**
     * 取消分片上传，清理临时数据。
     *
     * @param uploadId 上传任务 标识
     * @return 取消结果
     */
    DeleteObjectResult abort(String uploadId);
}