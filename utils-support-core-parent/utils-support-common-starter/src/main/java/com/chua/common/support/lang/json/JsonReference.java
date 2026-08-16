package com.chua.common.support.lang.json;

import com.chua.common.support.utils.StringUtils;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_BIG_PARENTHESES;
import static com.chua.common.support.constant.CommonConstant.SYMBOL_LEFT_SQUARE_BRACKET;


/**
 * JSON 字符串引用封装类，用于便捷地判断和操作 JSON 数据。
 * 该类提供了对 JSON 字符串是否为空、是否为数组或对象类型的快速检查，
 * 以及直接获取对应的 JsonObject 或 JsonArray 实例的方法。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JsonReference {
 /**
 * 内部存储的原始 JSON 字符串（已去除首尾空白）。
 */
 private final String json;

 /**
 * 构造函数，初始化 JSON 引用。
 * 如果传入的 JSON 字符串为 null，则内部字段保持为 null；否则进行 trim 处理。
 *
 * @param json 待引用的 JSON 字符串。若为 null，则视为无效输入。
 */
 public JsonReference(String json) {
 this.json = null == json ? null : json.trim();
 }

 /**
 * 判断当前 JSON 是否无效。
 * 无效的定义包括：字符串为空/空白，或者不以 '[' (数组开始符) 和 '{' (对象开始符) 开头。
 * 注意：此方法在 json 为 null 时也会返回 true。
 *
 * @return 如果 JSON 无效则返回 true，否则返回 false。
 */
 public boolean isEmpty() {
 // 如果 json 本身是 null，StringUtils.isBlank 会抛出 NPE，需先判空
 if (json == null) {
 return true;
 }
 return StringUtils.isBlank(json) || !json.startsWith(SYMBOL_LEFT_SQUARE_BRACKET) && !json.startsWith(SYMBOL_LEFT_BIG_PARENTHESES);
 }

 /**
 * 判断当前 JSON 字符串是否表示一个 JSON 数组。
 * 有效条件：字符串非空且以 '[' 开头。
 *
 * @return 如果是 JSON 数组则返回 true，否则返回 false。
 */
 public boolean isArray() {
 return json != null && json.startsWith(SYMBOL_LEFT_SQUARE_BRACKET);
 }

 /**
 * 判断当前 JSON 字符串是否表示一个 JSON 对象。
 * 有效条件：字符串非空且以 '{' 开头。
 *
 * @return 如果是 JSON 对象则返回 true，否则返回 false。
 */
 public boolean isObject() {
 return json != null && json.startsWith(SYMBOL_LEFT_BIG_PARENTHESES);
 }

 /**
 * 将当前的 JSON 字符串解析并转换为 JsonObject 实例。
 * 如果当前不是有效的 JSON 对象，底层实现可能会抛出异常或返回 null（取决于 Json.getJsonObject 的具体逻辑）。
 *
 * @return 解析后的 JsonObject 实例。
 */
 public JsonObject getJsonObject() {
 return Json.getJsonObject(json);
 }

 /**
 * 将当前的 JSON 字符串解析并转换为 JsonArray 实例。
 * 如果当前不是有效的 JSON 数组，底层实现可能会抛出异常或返回 null（取决于 Json.getJsonArray 的具体逻辑）。
 *
 * @return 解析后的 JsonArray 实例。
 */
 public JsonArray getJsonArray() {
 return Json.getJsonArray(json);
 }
}
