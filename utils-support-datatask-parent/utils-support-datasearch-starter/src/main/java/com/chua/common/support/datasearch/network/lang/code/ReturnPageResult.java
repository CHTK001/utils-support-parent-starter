package com.chua.common.support.datasearch.network.lang.code;

import com.chua.common.support.lang.code.PageResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnPageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;
    private PageResult<T> data;
    private String message;
    private boolean success;

    public static <T> ReturnPageResult<T> of(PageResult<T> data) {
        return new ReturnPageResult<>(data, null, true);
    }

    public static <T> ReturnPageResult<T> ok(PageResult<T> data) {
        return of(data);
    }

    public static <T> ReturnPageResult<T> error(String message) {
        return new ReturnPageResult<T>(null, message, false);
    }

    public static <T> ReturnPageResult<T> empty() {
        return new ReturnPageResult<T>(PageResult.<T>empty(), null, true);
    }
}
