package com.chua.common.support.datasearch.music.model;

import lombok.Builder;
import lombok.Data;

/**
* 音乐源选项
* 
* @author CH
* @since 4.0.0.42
*/
@Data
@Builder
public class MusicSourceOption {
    /** 代码 */
    private String code;
    /** 名称 */
    private String name;
    /** 描述 */
    private String description;
    /** 是否启用 */
    private Boolean enabled;
}


