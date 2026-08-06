package com.chua.common.support.lang.code;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果
 *
 * @author CH
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

 private static final long serialVersionUID = 1L;

 /**
 * 页码
 */
 private int pageNo;
 /**
 * 每页数量
 */
 private int pageSize;
 /**
 * 总页数
 */
 private int totalPages;
 /**
 * 总数
 */
 private long total;
 /**
 * 数据
 */
 private List<T> data;

 /**
 * 空分页结果单例
 */
 private static final PageResult<Object> EMPTY = PageResult.builder().build();

 /**
 * 空结果
 *
 * @param <T> 类型
 * @return 空分页结果
 */
 @SuppressWarnings("unchecked")
 public static <T> PageResult<T> empty() {
 return (PageResult<T>) EMPTY;
 }
}
