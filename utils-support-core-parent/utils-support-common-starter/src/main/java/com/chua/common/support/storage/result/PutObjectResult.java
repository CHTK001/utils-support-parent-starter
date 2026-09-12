package com.chua.common.support.storage.result;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
* 上传文件结果对象。
*
* <p>包含上传成功后返回的文件访问 URL 和 Key。</p>
*
* @author CH
* @since 1.0
 */
@Getter
@Setter
@SuperBuilder
public class PutObjectResult extends ObjectResult {

    /**
    * 文件的访问 URL。
     */
    private String url;

    /**
    * 文件的唯一 键。
     */
    private String key;
}
