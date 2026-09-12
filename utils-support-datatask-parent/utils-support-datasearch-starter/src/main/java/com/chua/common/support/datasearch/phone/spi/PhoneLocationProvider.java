package com.chua.common.support.datasearch.phone.spi;

import com.chua.common.support.datasearch.phone.model.PhoneLocationInfo;

/**
* 手机号码归属地数据提供者 SPI 接口。
*
* <p>封装中国大陆手机号码属地（省 / 市 / 运营商 / 区号 / 邮编）查询能力。
* 各实现通过 SPI 机制注册，例如基于在线号码段接口或本地号码段库的数据源。
*
* @author CH
* @since 4.0.0.42
 */
public interface PhoneLocationProvider {

    /**
    * 获取数据源名称
    *
    * @return 数据源名称
     */
    String name();

    /**
    * 查询手机号码归属地。
    *
    * <p>号码需为 11 位中国大陆手机号（如 13800138000）。
    *
    * @param phone 手机号码
    * @return 归属地信息；格式非法或查询失败返回 空
     */
    PhoneLocationInfo getLocation(String phone);
}
