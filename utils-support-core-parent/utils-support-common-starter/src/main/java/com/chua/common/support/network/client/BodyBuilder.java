package com.chua.common.support.network.client;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.lang.json.Json;

import java.util.Map;

/**
 * 请求体构建器，以链式 {@code withXxx} 写法组装 JSON 请求体。
 *
 * <p>通过 {@link #create()} 创建实例，使用 {@code with} 链式添加字段，
 * 最终通过 {@link #build()} 输出 JSON 字符串，可直接传给
 * {@link HttpClientBuilder#body(BodyBuilder)} 作为请求体：</p>
 *
 * <pre>{@code
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .path("/v1/images/generations")
 *     .json()
 *     .body(BodyBuilder.create()
 *             .with("model", "dall-e-3")
 *             .with("prompt", "一只可爱的猫")
 *             .with("n", 1)
 *             .with("size", "1024x1024")
 *             .with(quality != null, "quality", quality))
 *     .post();
 * }</pre>
 *
 * <p><b>字段规则：</b></p>
 * <ul>
 *   <li>{@link #with(String, Object)} 添加字段，值为 {@code null} 时跳过该字段
 *       （不会序列化为 JSON {@code null}）</li>
 *   <li>{@link #with(boolean, String, Object)} 仅当条件成立时添加字段，
 *       条件为 {@code false} 时跳过，适用于可选字段</li>
 *   <li>字段值可以是任意可被 JSON 序列化的类型（字符串、数字、布尔、Map、List、POJO 等），
 *       序列化由项目全局 {@link Json} 实现负责，特殊字符会自动转义</li>
 * </ul>
 *
 * <p>本类非线程安全，每个请求应使用独立的实例。</p>
 *
 * @author CH
 * @since 4.0.0.51
 * @see HttpClientBuilder#body(BodyBuilder)
 */
public final class BodyBuilder {

    /**
     * 字段容器，保持字段的添加顺序
     */
    private final Map<String, Object> fields = Wraps.linkedMap();

    /**
     * 私有构造方法，请通过 {@link #create()} 创建实例。
     */
    private BodyBuilder() {
    }

    /**
     * 创建空的请求体构建器。
     *
     * @return BodyBuilder 实例
     */
    public static BodyBuilder create() {
        return new BodyBuilder();
    }

    /**
     * 添加字段。
     *
     * <p>值为 {@code null} 时跳过该字段（不写入 JSON），适用于可选项；
     * 键为 {@code null} 时同样跳过。字段按添加顺序序列化。</p>
     *
     * @param key   字段名，不能为 {@code null}
     * @param value 字段值，为 {@code null} 时跳过该字段
     * @return 当前实例，支持链式调用
     */
    public BodyBuilder with(String key, Object value) {
        if (key == null || value == null) {
            return this;
        }
        fields.put(key, value);
        return this;
    }

    /**
     * 条件添加字段：仅当条件成立时写入，条件为 {@code false} 时跳过。
     *
     * <p>适用于根据运行期状态动态决定是否携带的可选字段：</p>
     * <pre>{@code
     * BodyBuilder.create()
     *     .with("model", "dall-e-3")
     *     .with(isNotBlank(quality), "quality", quality);
     * }</pre>
     *
     * @param condition 条件，为 {@code true} 时添加字段
     * @param key       字段名，不能为 {@code null}
     * @param value     字段值
     * @return 当前实例，支持链式调用
     */
    public BodyBuilder with(boolean condition, String key, Object value) {
        if (condition) {
            return with(key, value);
        }
        return this;
    }

    /**
     * 构建请求体：将已添加的字段序列化为 JSON 字符串。
     *
     * @return JSON 字符串；未添加任何字段时返回空对象 {@code "{}"}
     */
    public String build() {
        return Json.toJson(fields);
    }
}
