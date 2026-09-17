package com.chua.common.support.converter.definition;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.utils.StringUtils;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.awt.*;
import java.util.Arrays;
import java.util.List;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_HASH;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Color 类型转换器。
* <p>将各种类型的值转换为 {@link Color}，支持以下输入格式：</p>
* <ul>
*   <li>数值类型 — 直接作为 RGB 整数值构造 Color</li>
*   <li>字符串：
*     <ul>
*       <li>0xRRGGBB 或 0xAARRGGBB — 十六进制 RGBA 格式</li>
*       <li>rgba(r,g,b,a) — CSS RGBA 格式</li>
*       <li>rgb(r,g,b) — CSS RGB 格式</li>
*       <li>#RRGGBB 或 #RGB — HTML 十六进制颜色格式</li>
*     </ul>
*   </li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/10/30
 */
public class ColorTypeConverter implements TypeConverter<Color> {

    /** 十六进制前缀 */
    private static final String HEX_16 = "0x";

    /**
    * 将给定值转换为 Color。
    *
    * @param value 源值
    * @return Color 值，如果无法转换则返回 null
    */
    @Override
    public Color convert(Object value) {
        if (null == value) {
            return null;
        }

        if (value instanceof Number) {
            return new Color(((Number) value).intValue());
        }

        if (value instanceof String) {
            String str = value.toString().toLowerCase();

            if(str.startsWith(HEX_16)) {
                return hexRgba(str.replace(HEX_16, ""));
            }

            if (isRgba(str)) {
                return rgba(str.replace("rgba(", "").replace(")", ""));
            }

            if (isRgb(str)) {
                return rgb(str.replace("rgb(", "").replace(")", ""));
            }

            if(str.startsWith(SYMBOL_HASH)) {
                return hexStringToColor(str);
            }
        }

        return null;
    }

    /**
    * Convert hex string to Color
    *
    * @param hexColor hex color string, e.g. "#418063"
    * @return Color object
    */
    private Color hexStringToColor(String hexColor) {
        if (hexColor.startsWith(SYMBOL_HASH)) {
            hexColor = hexColor.substring(1);
        }

        int length = hexColor.length();
        if(length == 3) {
            return new Color(
                    Integer.parseInt(String.valueOf(hexColor.charAt(0) + hexColor.charAt(0)), 16),
                    Integer.parseInt(String.valueOf(hexColor.charAt(1) + hexColor.charAt(1)), 16),
                    Integer.parseInt(String.valueOf(hexColor.charAt(2) + hexColor.charAt(2)), 16)
            );
        }
        if(length < 6) {
            hexColor = hexColor  + StringUtils.repeat("0", 6 - length);
        }
        int r = Integer.parseInt(hexColor.substring(0, 2), 16);
        int g = Integer.parseInt(hexColor.substring(2, 4), 16);
        int b = Integer.parseInt(hexColor.substring(4, 6), 16);

        return new Color(r, g, b);
    }


    /**
    * Parse hex rgba color
    * @param str color string
    * @return color
    */
    private Color hexRgba(String str) {
        boolean hasA = str.length() >= 8;
        if(hasA) {
            String str1 = str.substring(0, 2);
            String str2 = str.substring(2, 4);
            String str3 = str.substring(4, 6);
            String str4 = str.substring(6, 8);
            int alpha = Integer.parseInt(str1, 16);
            int red = Integer.parseInt(str2, 16);
            int green = Integer.parseInt(str3, 16);
            int blue = Integer.parseInt(str4, 16);
            return new Color(red, green, blue, alpha);
        }

        String str1 = str.substring(0, 2);
        String str2 = str.substring(2, 4);
        String str3 = str.substring(4, 6);
        int red = Integer.parseInt(str1, 16);
        int green = Integer.parseInt(str2, 16);
        int blue = Integer.parseInt(str3, 16);
        return new Color(red, green, blue);
    }

    /**
    * Parse rgb color
    *
    * @param hexStr rgb string
    * @return color
    */
    private Color rgb(String hexStr) {
        String[] parts = hexStr.split(",");
        return new Color(
                Converter.createInteger(parts[0].trim()),
                Converter.createInteger(parts[1].trim()),
                Converter.createInteger(parts[2].trim()));
    }

    /**
    * Parse rgba color
    *
    * @param hexStr rgba string
    * @return color
    */
    private Color rgba(String hexStr) {
        String[] parts = hexStr.split(",");
        return new Color(
                Converter.createInteger(parts[0].trim()),
                Converter.createInteger(parts[1].trim()),
                Converter.createInteger(parts[2].trim()),
                (int)(Converter.createFloat(parts[3].trim()) * 255f)
        );
    }

    /**
    * Check if string is rgb format
    *
    * @param str rgb string
    * @return true if rgb
    */
    private boolean isRgb(String str) {
        return str.startsWith("rgb");
    }

    /**
    * Check if string is rgba format
    *
    * @param str rgba string
    * @return true if rgba
    */
    private boolean isRgba(String str) {
        return str.startsWith("rgba");
    }

    /**
    * 获取当前转换器支持的目标类型。
    *
    * @return Color.class
    */
    @Override
    public Class<Color> getType() {
        return Color.class;
    }

    /**
    * Color name helper
    */
    @Data
    @AllArgsConstructor
    public static class ColorName {
        /** RGB 颜色分量 */
        public int r, g, b;
        /**
        * 名称
        */
        public String name;

        /**
        * 创建 ColorName 实例
        * @param name name
        * @param int int
        * @param int int
        * @param int int
        */
        public ColorName(String name, int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.name = name;
        }

        /**
        * Get Color object
        * @return color
        */
        public Color getColor() {
            return new Color(r, g, b);
        }
    }
}
