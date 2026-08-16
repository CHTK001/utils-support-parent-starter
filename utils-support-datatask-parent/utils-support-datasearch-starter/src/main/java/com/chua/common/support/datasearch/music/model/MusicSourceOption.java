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
    private String code;
    private String name;
    private String description;
    private Boolean enabled;
}


