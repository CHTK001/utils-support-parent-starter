package com.chua.common.support.datasearch.network.lang.code;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
* @author CH
* @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListReturnResult<T> implements Serializable {

    /** 串行版本UID */
    private static final long serialVersionUID = 1L;
    /** 数据 */
    private List<T> data;
    /** 消息 */
    private String message;
    /** 成功 */
    private boolean success;

    /**
    * Ok
    *
    * @param data 数据
    * @return ok的结果
    */
    public static <T> ListReturnResult<T> ok(List<T> data) {
        return new ListReturnResult<>(data, null, true);
    }

    /**
    * 空
    *
    * @return 空的结果
    */
    public static <T> ListReturnResult<T> empty() {
        return new ListReturnResult<T>(Collections.<T>emptyList(), null, true);
    }

    /**
    * 记录错误
    *
    * @param message 消息
    * @return 错误的结果
    */
    public static <T> ListReturnResult<T> error(String message) {
        return new ListReturnResult<T>(null, message, false);
    }
}
