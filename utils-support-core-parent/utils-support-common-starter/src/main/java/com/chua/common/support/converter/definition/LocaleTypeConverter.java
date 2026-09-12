package com.chua.common.support.converter.definition;

import lombok.extern.slf4j.Slf4j;

import java.util.Locale;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Locale       
*
* <p>                     </p>
* <ul>
*     <li>                "zh", "zh_CN", "zh-CN", "en-US", "zh-Hans-CN"(language tag)</li>
*     <li>Map          language / country / variant    (               )</li>
*     <li>Locale               </li>
* </ul>
*
* @author CH
* @version 1.0.0
* @since 2020/12/31
 */
@Slf4j
public class LocaleTypeConverter implements TypeConverter<Locale> {

    /**
    *             
    *
    * @return Locale.class
     */
    @Override
    public Class<Locale> getType() {
        return Locale.class;
    }

    /**
    *           Locale
    *
    * @param value    
    * @return Locale     null
     */
    @Override
    public Locale convert(Object value) {
        if (null == value) {
            return null;
        }
        if (value instanceof Locale) {
            return (Locale) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                //              IETF BCP 47                    zh-Hans-CN / en-US    
                if (text.contains("-")) {
                    Locale loc = Locale.forLanguageTag(text);
                    // forLanguageTag                                      Locale(""),                      
                    if (!loc.getLanguage().isEmpty()) {
                        return loc;
                    }
                }
                //        zh_CN / en_US / zh / zh__#Hans          
                String normalized = text.replace('-', '_');
                String[] parts = normalized.split("_");
                if (parts.length == 1) {
                    return new Locale(parts[0].toLowerCase());
                }
                if (parts.length == 2) {
                    return new Locale(parts[0].toLowerCase(), parts[1].toUpperCase());
                }
                if (parts.length >= 3) {
                    return new Locale(parts[0].toLowerCase(), parts[1].toUpperCase(), parts[2]);
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Locale convert failed. value={}, error={}", value, e.getMessage());
                }
            }
            return null;
        }
        if (value instanceof Map) {
            try {
                Map map = (Map) value;
                Object lang = getIgnoreCase(map, "language");
                Object country = getIgnoreCase(map, "country");
                Object variant = getIgnoreCase(map, "variant");
                String l = lang == null ? null : lang.toString().trim();
                String c = country == null ? null : country.toString().trim();
                String v = variant == null ? null : variant.toString().trim();
                if (l == null || l.isEmpty()) {
                    return null;
                }
                if (c == null || c.isEmpty()) {
                    return new Locale(l.toLowerCase());
                }
                if (v == null || v.isEmpty()) {
                    return new Locale(l.toLowerCase(), c.toUpperCase());
                }
                return new Locale(l.toLowerCase(), c.toUpperCase(), v);
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Locale convert from Map failed. value={}, error={}", value, e.getMessage());
                }
            }
        }
        return convertIfNecessary(value);
    }

    /**
    * Map                      
    *
    * @param map   map
    * @param key      
    * @return    
     */
    private Object getIgnoreCase(Map map, String key) {
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (Object k : map.keySet()) {
            if (k != null && key.equalsIgnoreCase(String.valueOf(k))) {
                return map.get(k);
            }
        }
        return null;
    }
}
