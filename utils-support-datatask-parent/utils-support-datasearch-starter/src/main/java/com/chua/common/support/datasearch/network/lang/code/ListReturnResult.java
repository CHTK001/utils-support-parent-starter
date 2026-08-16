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

    private static final long serialVersionUID = 1L;
    private List<T> data;
    private String message;
    private boolean success;

    public static <T> ListReturnResult<T> ok(List<T> data) {
        return new ListReturnResult<>(data, null, true);
    }

    public static <T> ListReturnResult<T> empty() {
        return new ListReturnResult<T>(Collections.<T>emptyList(), null, true);
    }

    public static <T> ListReturnResult<T> error(String message) {
        return new ListReturnResult<T>(null, message, false);
    }
}
