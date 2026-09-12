package com.chua.trae.support.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

/**
* 模型信息，承载模型 标识 标识。
*
* @param id 模型标识，如 glm-5.2
* @param object 对象类型，固定 模型
* @author CH
* @since 4.0.0.42
 */
public record ModelInfo(
    /** 模型标识 */
    @JsonProperty("id") String id,
    /** 对象类型，固定 模型 */
    @JsonProperty("object") String object
) {
    /**
    * 创建模型信息。
    *
    * @param id 模型标识，不可为 空
    * @return 模型信息实例
     */
    public static ModelInfo of(String id) {
        Objects.requireNonNull(id, "id must not be null");
        return new ModelInfo(id, "model");
    }

    /**
    * 模型列表响应。
    *
    * @param object 对象类型，固定 列表
    * @param data 模型信息列表
    * @author CH
    * @since 4.0.0.42
     */
    public record ModelList(
        /** 对象类型 */
        @JsonProperty("object") String object,
        /** 模型列表 */
        @JsonProperty("data") List<ModelInfo> data
    ) {
        /**
        * 从 标识 列表构建模型列表响应。
        *
        * @param ids 模型 标识 列表，不可为 空
        * @return 模型列表响应
         */
        public static ModelList of(List<String> ids) {
            Objects.requireNonNull(ids, "ids must not be null");
            List<ModelInfo> data = ids.stream().map(ModelInfo::of).toList();
            return new ModelList("list", data);
        }
    }
}