package com.chua.spider.support.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 单条 Cookie。
*
* @author CH
* @since 4.0.0.42
 */

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder(toBuilder = true)
public class SpiderCookie {

    /**
    * Cookie 名称。
    */
    private String name;

    /**
    * Cookie 值。
    */
    private String value;

    /**
    * 域名。
    *
    * <p>为空表示不限域名。</p>
    */
    private String domain;

    /**
    * 路径。
    *
    * <p>为空时使用默认路径 {@code /}。</p>
    */
    private String path;

    /**
    * 是否仅 HTTPS 发送。
    */
    private Boolean secure;
}
