package com.chua.common.support.datasearch.phone.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手机号码归属地信息模型。
 *
 * <p>描述手机号码段对应的省、市、运营商与区号邮编等属地信息。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PhoneLocationInfo {

    /**
     * 手机号码
     */
    private final String phone;

    /**
     * 省份（如：广东省）
     */
    private final String province;

    /**
     * 城市（如：广州市）
     */
    private final String city;

    /**
     * 运营商（如：中国移动 / 中国联通 / 中国电信）
     */
    private final String carrier;

    /**
     * 区号（如：020）
     */
    private final String areaCode;

    /**
     * 邮编（如：510000）
     */
    private final String postCode;

    /**
     * 创建 phone位置信息 实例
     *
     * @param phone    手机号码
     * @param province 省份
     * @param city     城市
     * @param carrier  运营商
     * @param areaCode 区号
     * @param postCode 邮编
     */
    public PhoneLocationInfo(String phone, String province, String city, String carrier, String areaCode, String postCode) {
        this.phone = phone;
        this.province = province;
        this.city = city;
        this.carrier = carrier;
        this.areaCode = areaCode;
        this.postCode = postCode;
    }

    /**
     * 获取手机号码
     *
     * @return 获取phone的结果
     */
    public String getPhone() {
        return phone;
    }

    /**
     * 获取省份
     *
     * @return 获取province的结果
     */
    public String getProvince() {
        return province;
    }

    /**
     * 获取城市
     *
     * @return 获取city的结果
     */
    public String getCity() {
        return city;
    }

    /**
     * 获取运营商
     *
     * @return 获取carrier的结果
     */
    public String getCarrier() {
        return carrier;
    }

    /**
     * 获取区号
     *
     * @return 获取area编码的结果
     */
    public String getAreaCode() {
        return areaCode;
    }

    /**
     * 获取邮编
     *
     * @return 获取post编码的结果
     */
    public String getPostCode() {
        return postCode;
    }

    /**
     * 转换为 映射 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("phone", phone);
        map.put("province", province);
        map.put("city", city);
        map.put("carrier", carrier);
        map.put("areaCode", areaCode);
        map.put("postCode", postCode);
        return map;
    }
}
