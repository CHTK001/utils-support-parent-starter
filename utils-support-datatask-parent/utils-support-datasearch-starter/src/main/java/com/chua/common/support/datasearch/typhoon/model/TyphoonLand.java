package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
* 台风登陆记录实体。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonLand {

    /** 登陆地点 */
    private String landaddress;

    /** 登陆时间 */
    private String landtime;

    /** 登陆经度 */
    private String lng;

    /** 登陆纬度 */
    private String lat;

    /** 登陆说明 */
    private String info;

    /** 登陆时强度 */
    private String strong;
}
