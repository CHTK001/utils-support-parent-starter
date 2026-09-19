package com.chua.common.support.media;

import java.util.HashMap;
import java.util.Map;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class MediaTypeFactory {

    /** MEDITYPES */
    private static final Map<String, MediaType> MEDIA_TYPES = new HashMap<>();

    static {
        MEDIA_TYPES.put("text", new MediaType("text", "plain", "UTF-8"));
        MEDIA_TYPES.put("html", new MediaType("text", "html", "UTF-8"));
        MEDIA_TYPES.put("json", new MediaType("application", "json", "UTF-8"));
        MEDIA_TYPES.put("xml", new MediaType("application", "xml", "UTF-8"));
        MEDIA_TYPES.put("jpg", new MediaType("image", "jpeg"));
        MEDIA_TYPES.put("jpeg", new MediaType("image", "jpeg"));
        MEDIA_TYPES.put("png", new MediaType("image", "png"));
        MEDIA_TYPES.put("gif", new MediaType("image", "gif"));
        MEDIA_TYPES.put("bmp", new MediaType("image", "bmp"));
        MEDIA_TYPES.put("webp", new MediaType("image", "webp"));
        MEDIA_TYPES.put("svg", new MediaType("image", "svg+xml"));
        MEDIA_TYPES.put("pdf", new MediaType("application", "pdf"));
        MEDIA_TYPES.put("css", new MediaType("text", "css"));
        MEDIA_TYPES.put("js", new MediaType("application", "javascript"));
    }

    /**
    * 获取media类型
    *
    * @param name 名称
    * @return 获取media类型的结果
    */
    public static MediaType getMediaType(String name) {
        MediaType mt = MEDIA_TYPES.get(name.toLowerCase());
        if (mt == null) {
            throw new IllegalArgumentException("Unsupported media type: " + name);
        }
        return mt;
    }

    /**
     * 获取media类型空
     *
     * @param name 名称
     * @return 获取media类型空的结果
     */
    public static MediaType getMediaTypeNullable(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return MEDIA_TYPES.get(name.trim().toLowerCase());
    }
}
