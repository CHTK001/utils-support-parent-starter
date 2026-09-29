package com.chua.common.support.lang.code;


/**
 * 返回结果（XML 报文形态）。
 *
 * <p>与同包的 {@link ReturnResult} 平行，但面向「响应体本身是 XML 文本」的场景：
 * {@link #code} 用<b>数值</b>编码表达成败，{@link #data} 直接承载 XML 报文原文
 * （字符串而非已解析的对象），{@link #msg} 为补充说明或失败原因。</p>
 *
 * <p>可空性：规范构造器不做任何非空校验，三个分量均<b>允许为 {@code null}</b>，
 * 且本 record 未提供默认值兜底，消费方须自行判空后取值。</p>
 *
 * <p>注意：本 record 在当前代码库中<b>尚无构造与读取调用点</b>，属预留类型，
 * 因此下述语义按类型约定与同包惯例描述，落地接入时需与实际协议对齐。</p>
 *
 * @param code 响应 编码，标识本次响应的成败与类别。注意本分量为 {@link Integer}
 *              <b>数值</b>型，与同包 {@link ReturnResult#getCode()} 的
 *              {@code String} 型 10 位业务编码（见 {@link ReturnCode} 的
 *              {@code S0500C0400} 编码格式）<b>不是同一套体系</b>，两者不可混用：
 *              跨用会导致「数值转字符串」后与业务编码表完全对不上。
 *              取值来源为对接协议的响应状态码，语义上通常为 HTTP 风格的三位
 *              状态码（{@code ResultCode#HTTP_200} / {@code HTTP_400} /
 *              {@code HTTP_500} 等常量族），成功与失败的判定应以
 *              {@code ResultCode#transferForHttpCode(Integer)} 的映射结果
 *              或 2xx 区间为准，而非与 {@link ReturnCode#getCode()} 比较。
 *              无单位量纲。允许为 {@code null}，此时应视为「状态未知」并
 *              按失败处理，不应放行数据
 * @param data 业务 数据 承载位，此处为 <b>XML 报文原文字符串</b>（未解析、未做
 *             任何转义或校验），而非结构化对象——这是本 record 区别于
 *             {@link ReturnResult} 的关键点：后者的 {@code data} 是泛型对象，
 *             由框架负责序列化。本 record 把序列化权交给调用方，
 *             因此<b>传入的文本需自行保证 XML 合法且已正确转义</b>，
 *             且不得把不可信内容直接拼入其中以免引入 XML 注入。
 *             无单位量纲，长度受下游 HTTP 响应体大小限制约束。
 *             允许为 {@code null}，在失败场景下这是正常取值（失败时通常只有
 *             {@link #code} 与 {@link #msg}，无数据体）
 * @param msg 提示 信息，成功时可为「操作成功」一类的说明文字（可取
 *            {@link ReturnCode#SUCCESS#getMsg()}），失败时为失败原因，
 *            需对人可读以便直接呈现给调用方或写入日志。
 *            取值来源为调用方在组装响应时写入，或参照 {@link ReturnResult}
 *            中「异常 message 为空时回退到状态码默认文案」的同款约定。
 *            允许为 {@code null}，此时调用方展示层需自行提供兜底文案
 * @author CH
 * @since 4.0.0.42
 */
public record ReturnXmlResult(
 Integer code,
 String data,
 String msg
) {
}
