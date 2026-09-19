package com.chua.common.support.lang.code;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果封装。
 *
 * <p>承载分页查询的页码、每页数量、总页数、总数与当前页数据。
 * 实现 {@link Serializable}，可直接作为 RPC/HTTP 返回值传输。</p>
 *
 * @param <T> 数据元素类型
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

 /**
  * 序列化版本号
 */
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
  * 空分页结果单例（所有字段为默认值：页码 0、每页 0、总页数 0、总数 0、数据 null），
  * 供 {@link #empty()} 复用，避免每次创建新对象。
  */
 private static final PageResult<Object> EMPTY = PageResult.builder().build();

 /**
  * 获取指定类型的空分页结果单例。
  *
  * @param <T> 数据元素类型
  * @return 空分页结果（所有字段为默认值，data 为 null）
  */
 @SuppressWarnings("unchecked")
 public static <T> PageResult<T> empty() {
 return (PageResult<T>) EMPTY;
 }
}
