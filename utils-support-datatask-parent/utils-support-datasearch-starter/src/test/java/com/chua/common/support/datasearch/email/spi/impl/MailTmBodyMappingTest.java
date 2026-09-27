package com.chua.common.support.datasearch.email.spi.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * MailTM 正文映射回归测试。
 *
 * <p>报文形状取自 mail.tm 官方 OpenAPI（<a href="https://api.mail.tm/docs">api.mail.tm/docs</a>）：
 * {@code text} 为 string，{@code html} 为 string 数组。旧实现把
 * {@code detail.path("html").asText("")} 拼进 body，而 Jackson 对数组节点调用
 * {@code asText(String)} 恒返回默认空串，导致 html 整段丢失且 {@code EmailInfo.html} 从未赋值。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class MailTmBodyMappingTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 详情报文：text 是字符串，html 是分段数组。
     */
    private static final String DETAIL = "{\"id\":\"68f1a\",\"subject\":\"探针\","
            + "\"text\":\"PLAIN_BODY\","
            + "\"html\":[\"<p>PART_1</p>\",\"<p>PART_2</p>\"],"
            + "\"from\":{\"address\":\"sender@example.com\",\"name\":\"Sender\"},"
            + "\"createdAt\":\"2026-09-20T08:00:00.000Z\"}";

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) throws Exception {
        JsonNode detail = new ObjectMapper().readTree(DETAIL);

        // 旧行为：数组节点 asText("") 得到空串 —— html 被静默丢弃
        check("旧实现 asText(\"\") 对 html 数组恒为空串", "".equals(detail.path("html").asText("")));
        check("旧实现拼出的 body 只剩纯文本", "PLAIN_BODY".equals(
                detail.path("text").asText("") + detail.path("html").asText("")));

        // 新行为：分段数组被拼接，且 body/html 各归其位
        check("html 分段被拼接", "<p>PART_1</p><p>PART_2</p>".equals(MailTmEmailProvider.textOrJoin(detail.path("html"))));
        check("text 保持原样", "PLAIN_BODY".equals(MailTmEmailProvider.textOrJoin(detail.path("text"))));
        check("缺失节点返回 null", MailTmEmailProvider.textOrJoin(detail.path("nope")) == null);
        check("空数组返回 null", MailTmEmailProvider.textOrJoin(
                new ObjectMapper().readTree("{\"html\":[]}").path("html")) == null);
        check("null 节点返回 null", MailTmEmailProvider.textOrJoin(
                new ObjectMapper().readTree("{\"html\":null}").path("html")) == null);
        check("from 仍按对象取 address", "sender@example.com".equals(detail.path("from").path("address").asText(null)));

        System.out.println("[MailTmBodyMappingTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
