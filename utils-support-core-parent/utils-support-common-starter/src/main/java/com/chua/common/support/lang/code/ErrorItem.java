package com.chua.common.support.lang.code;

import lombok.Builder;
import lombok.Data;

/**
 * 错误项
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class ErrorItem {

 /**
  * 无错误
  */
 public static final ErrorItem NO_ERROR = ErrorItem.builder().isError(false).build();

 /**
  * 是否错误
  */
 @Builder.Default
 /** IS错误 */
 private boolean isError = true;
 /**
 * 错误信息
 */
 private String message;
 /**
  * 错误
  */
 private volatile transient Throwable throwable;

 /**
  * 错误
  *
  * @param value 值
  * @param message 错误信息
  * @return 错误项
  */
 public static ErrorItem of(boolean value, String message) {
 if (value) {
 return ErrorItem.builder().build();
 }
 return ErrorItem.builder().message(message).build();
 }

 /**
  * 错误
  *
  * @param value 值
  * @param throwable 错误
  * @return 错误项
  */
 public static ErrorItem of(boolean value, Throwable throwable) {
 if (value) {
 return ErrorItem.builder().build();
 }
 return ErrorItem.builder().throwable(throwable).build();
 }

 /**
  * 错误
  *
  * @param message 错误信息
  * @return 错误项
  */
 public static ErrorItem of(String message) {
 return ErrorItem.builder().message(message).build();
 }

 /**
  * 错误
  *
  * @param value 值
  * @return 错误项
  */
 public static ErrorItem of(boolean value) {
 if (value) {
 return NO_ERROR;
 }
 return ErrorItem.builder().build();
 }
}
