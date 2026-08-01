package com.chua.springboot.support.api.rule;

import com.chua.springboot.support.api.annotations.ApiFieldPrivacyEncrypt;
import com.chua.common.support.utils.PrivacyUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.google.common.base.Strings;
import java.io.IOException;
import java.util.Objects;

/**
 * 隐私字段序列化器，配合 Jackson 与 ApiFieldPrivacyEncrypt 注解使用。
 * <p>
 * 根据 {@link PrivacyTypeEnum} 选择对应的脱敏策略（姓名、身份证、手机号、邮箱、地址、银行卡、车牌等）；
 * CUSTOMER 类型支持自定义前后保留位数与打码字符。
 * 原文为空或未配置脱敏类型时按原文输出。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
public class PrivacySerializer extends JsonSerializer<String> implements ContextualSerializer {

    /**
     * 脱敏类型
     */
    private PrivacyTypeEnum privacyTypeEnum;
    /**
     * 前几位不脱敏
     */
    private Integer prefixNoMaskLen;
    /**
     * 最后几位不脱敏
     */
    private Integer suffixNoMaskLen;
    /**
     * 用什么打码
     */
    private String symbol;

    /**
     * 隐私加密注解
     */
    private ApiFieldPrivacyEncrypt apiFieldPrivacyEncrypt;

    /**
     * 无参构造函数
     */
    public PrivacySerializer() {
    }

    /**
     * 构造函数
     *
     * @param privacyTypeEnum 脱敏类型
     * @param prefixNoMaskLen 前几位不脱敏
     * @param suffixNoMaskLen 最后几位不脱敏
     * @param symbol 用什么打码
     * @param apiFieldPrivacyEncrypt 隐私加密注解
     */
    public PrivacySerializer(PrivacyTypeEnum privacyTypeEnum, Integer prefixNoMaskLen, Integer suffixNoMaskLen, String symbol, ApiFieldPrivacyEncrypt apiFieldPrivacyEncrypt) {
        this.privacyTypeEnum = privacyTypeEnum;
        this.prefixNoMaskLen = prefixNoMaskLen;
        this.suffixNoMaskLen = suffixNoMaskLen;
        this.symbol = symbol;
        this.apiFieldPrivacyEncrypt = apiFieldPrivacyEncrypt;
    }

    /**
     * 构造函数
     *
     * @param privacyTypeEnum 脱敏类型
     * @param prefixNoMaskLen 前几位不脱敏
     * @param suffixNoMaskLen 最后几位不脱敏
     * @param symbol 用什么打码
     */
    public PrivacySerializer(PrivacyTypeEnum privacyTypeEnum, Integer prefixNoMaskLen, Integer suffixNoMaskLen, String symbol) {
        this.privacyTypeEnum = privacyTypeEnum;
        this.prefixNoMaskLen = prefixNoMaskLen;
        this.suffixNoMaskLen = suffixNoMaskLen;
        this.symbol = symbol;
    }

    @Override
    public void serialize(final String origin, final JsonGenerator jsonGenerator,
                          final SerializerProvider serializerProvider) throws IOException {
        if (!Strings.isNullOrEmpty(origin) && null != privacyTypeEnum) {
            switch (privacyTypeEnum) {
                case CUSTOMER:
                    jsonGenerator.writeString(PrivacyUtils.desValue(origin, prefixNoMaskLen, suffixNoMaskLen, symbol));
                    break;
                case NAME:
                    jsonGenerator.writeString(PrivacyUtils.hideChineseName(origin));
                    break;
                case ID_CARD:
                    jsonGenerator.writeString(PrivacyUtils.hideCard(origin));
                    break;
                case PHONE:
                    jsonGenerator.writeString(PrivacyUtils.hidePhone(origin));
                    break;
                case EMAIL:
                    jsonGenerator.writeString(PrivacyUtils.hideEmail(origin));
                    break;
                case ADDRESS:
                    jsonGenerator.writeString(PrivacyUtils.hideAddress(origin, 8));
                    break;
                case BANK_CARD:
                    jsonGenerator.writeString(PrivacyUtils.hideBankCard(origin));
                    break;
                case PASSWORD:
                    jsonGenerator.writeString(PrivacyUtils.hidePassword(origin));
                    break;
                case CAR_NUMBER:
                    jsonGenerator.writeString(PrivacyUtils.hideCarNumber(origin));
                    break;
                case NONE:
                    jsonGenerator.writeString(origin);
                    break;
                default:
                    throw new IllegalArgumentException("unknown privacy type enum " + privacyTypeEnum);
            }
            return;
        }

        jsonGenerator.writeString(origin);
    }

    @Override
    public JsonSerializer<?> createContextual(final SerializerProvider serializerProvider,
                                              final BeanProperty beanProperty) throws JsonMappingException {
        if (beanProperty != null) {
            if (Objects.equals(beanProperty.getType().getRawClass(), String.class)) {
                ApiFieldPrivacyEncrypt apiFieldPrivacyEncrypt = beanProperty.getAnnotation(ApiFieldPrivacyEncrypt.class);
                if (apiFieldPrivacyEncrypt == null) {
                    apiFieldPrivacyEncrypt = beanProperty.getContextAnnotation(ApiFieldPrivacyEncrypt.class);
                }
                if (apiFieldPrivacyEncrypt != null) {
                    return new PrivacySerializer(apiFieldPrivacyEncrypt.type(), apiFieldPrivacyEncrypt.prefixNoMaskLen(),
                            apiFieldPrivacyEncrypt.suffixNoMaskLen(), apiFieldPrivacyEncrypt.symbol());
                }
            }
            return serializerProvider.findValueSerializer(beanProperty.getType(), beanProperty);
        }
        return serializerProvider.findNullValueSerializer(null);
    }
}

