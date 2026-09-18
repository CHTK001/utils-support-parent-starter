package com.chua.datasource.support.user;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 数据源用户信息。
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {

    /**
    * 用户名
    */
    private String user;

    /**
    * 主机名/IP
    */
    private String host;

    /**
    * 密码
    */
    private String password;
}
