package com.chua.milvus.support.configuration;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * Milvus 向量存储配置属性。
 *
 * <p>通过 Spring Boot 配置或 {@link com.chua.common.support.vector.VectorStorageProvider}
 * 链式构建器传入。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@Accessors(chain = true)
public class MilvusStorageProperties implements Serializable {

    /** 串行版本UID */
    private static final long serialVersionUID = 1L;

    /**
    * Milvus 服务地址（支持完整 URI，如 https://...）
    */
    private String host = "127.0.0.1";

    /**
     * Milvus 服务端口（仅当 主机 不含协议时使用）
     */
    private int port = 19530;

    /**
     * 集合 名称
     */
    private String collection = "vector_store";

    /**
     * 认证令牌（可选，Zilliz Cloud 必须）
     */
    private String token;
}
