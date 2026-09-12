package com.chua.common.support.converter.definition;

import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* BigDecimal 类型转换器。
* <p>将各种类型的值转换为 {@link BigDecimal}，支持以下特性：</p>
* <ul>
*     <li>百分比字符串 "12.5%" → 0.125</li>
*     <li>包含不可见空格（\u00A0）的字符串自动清理</li>
*     <li>兜底调用 {@link #transToBigDecimal(Object)} 和 {@link #convertIfNecessary(Object)}</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
@Slf4j
public class BigDecimalTypeConverter implements TypeConverter<BigDecimal> {

    /**
    * 将给定值转换为 BigDecimal。
    * <p>字符串处理流程：去除两端空白 → 检测百分比后缀 → 清理不可见空格 → 调用 {@link TypeConverter#stringTransToBigDecimal(String)} 解析。</p>
    *
    * @param value 源值
    * @return BigDecimal 值，如果无法转换则返回 null
     */
    @Override
    public BigDecimal convert(Object value) {
        if (null == value) {
            return null;
        }

        //                                                    
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            boolean percent = false;
            if (text.endsWith("%")) {
                percent = true;
                text = text.substring(0, text.length() - 1);
            }
            //                                        
            text = text.replaceAll("[\\u00A0\\s]+", "").trim();
            try {
                BigDecimal bd = TypeConverter.stringTransToBigDecimal(text);
                if (bd != null) {
                    return percent ? bd.divide(BigDecimal.valueOf(100)) : bd;
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("BigDecimal convert failed for text='{}'. error={}", text, e.getMessage());
                }
            }
        }

        BigDecimal bigDecimal = transToBigDecimal(value);
        if (null != bigDecimal) {
            return bigDecimal;
        }
        return convertIfNecessary(value);
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return BigDecimal.class
     */
    @Override
    public Class<BigDecimal> getType() {
        return BigDecimal.class;
    }
}
