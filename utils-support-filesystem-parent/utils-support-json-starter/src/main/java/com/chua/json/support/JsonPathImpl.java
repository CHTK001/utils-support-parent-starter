package com.chua.json.support;

import com.chua.common.support.lang.json.JsonPath;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

/**
 * JsonPath SPI 实现，基于 <a href="https://github.com/json-path/JsonPath">jayway JsonPath</a>。
 *
 * <p>提供声明式 JSON 路径查询与操作能力，支持一次性调用和链式操作两种模式。</p>
 *
 * <p><b>线程安全说明：</b></p>
 * <ul>
 *   <li>一次性方法（{@link #read(String, String)} 等）— 线程安全</li>
 *   <li>链式方法（{@link #parse(String)} 后的操作）— 非线程安全，每个线程应使用独立的 {@code parse()} 调用链</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("json")
@SpiDefault
public class JsonPathImpl implements JsonPath {

    /**
     * 默认配置：Jackson 序列化 + 路径不存在时返回 null
     */
    private static final Configuration DEFAULT_CONFIG = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider())
            .mappingProvider(new JacksonMappingProvider())
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
            .build();

    /**
     * 可读的解析上下文
     */
    private static final ParseContext PARSE_CTX = com.jayway.jsonpath.JsonPath.using(DEFAULT_CONFIG);

    /**
     * 链式模式下存储的内部文档上下文
     */
    private DocumentContext documentContext;

    // ==================== 一次性方法 ====================

    @Override
    public <T> T read(String json, String jsonPath) {
        try {
            return PARSE_CTX.parse(json).read(jsonPath);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public <T> T read(String json, String jsonPath, Class<T> type) {
        try {
            return PARSE_CTX.parse(json).read(jsonPath, type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String set(String json, String jsonPath, Object value) {
        try {
            return PARSE_CTX.parse(json).set(jsonPath, value).jsonString();
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public String delete(String json, String jsonPath) {
        try {
            return PARSE_CTX.parse(json).delete(jsonPath).jsonString();
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public String add(String json, String jsonPath, Object value) {
        try {
            return PARSE_CTX.parse(json).add(jsonPath, value).jsonString();
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public String put(String json, String jsonPath, String key, Object value) {
        try {
            return PARSE_CTX.parse(json).put(jsonPath, key, value).jsonString();
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public boolean isExist(String json, String jsonPath) {
        try {
            return PARSE_CTX.parse(json).read(jsonPath) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int length(String json, String jsonPath) {
        try {
            Object value = PARSE_CTX.parse(json).read(jsonPath);
            if (value == null) {
                return 0;
            }
            if (value instanceof java.util.List) {
                return ((java.util.List<?>) value).size();
            }
            if (value instanceof String) {
                return ((String) value).length();
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    // ==================== 链式方法 ====================

    @Override
    public JsonPath parse(String json) {
        this.documentContext = PARSE_CTX.parse(json);
        return this;
    }

    @Override
    public <T> T read(String jsonPath) {
        ensureParsed();
        try {
            return documentContext.read(jsonPath);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public <T> T read(String jsonPath, Class<T> type) {
        ensureParsed();
        try {
            return documentContext.read(jsonPath, type);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public JsonPath set(String jsonPath, Object value) {
        ensureParsed();
        documentContext.set(jsonPath, value);
        return this;
    }

    @Override
    public JsonPath delete(String jsonPath) {
        ensureParsed();
        documentContext.delete(jsonPath);
        return this;
    }

    @Override
    public JsonPath add(String jsonPath, Object value) {
        ensureParsed();
        documentContext.add(jsonPath, value);
        return this;
    }

    @Override
    public JsonPath put(String jsonPath, String key, Object value) {
        ensureParsed();
        documentContext.put(jsonPath, key, value);
        return this;
    }

    @Override
    public boolean isExist(String jsonPath) {
        ensureParsed();
        try {
            return documentContext.read(jsonPath) != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public int length(String jsonPath) {
        ensureParsed();
        try {
            Object value = documentContext.read(jsonPath);
            if (value == null) {
                return 0;
            }
            if (value instanceof java.util.List) {
                return ((java.util.List<?>) value).size();
            }
            if (value instanceof String) {
                return ((String) value).length();
            }
            return 1;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String toJson() {
        ensureParsed();
        return documentContext.jsonString();
    }

    // ==================== 内部方法 ====================

    /**
     * 检查链式模式下是否已调用 {@link #parse(String)}。
     */
    private void ensureParsed() {
        if (documentContext == null) {
            throw new IllegalStateException("请先调用 parse(String) 解析 JSON 后再进行链式操作");
        }
    }
}
