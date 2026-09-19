package com.chua.common.support.storage.result;

import com.chua.common.support.storage.metadata.Metadata;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.io.InputStream;

/**
 * 获取/下载文件结果对象。
 *
 * <p>包含文件内容（输入流）、元数据等信息。</p>
 *
 * @author CH
 * @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class GetObjectResult extends ObjectResult {

    /**
     * 文件内容的输入流。
     */
    private InputStream inputStream;

    /**
     * 文件元数据。
     */
    private Metadata metadata;
}
