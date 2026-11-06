package com.chua.skywalking.support.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code serviceCode} → 服务名的映射。
 * <p>
 * 这条用例锁的是<b>真实 OAP 9.7.0 的返回形态</b>：{@code queryTrace} 里的
 * {@code serviceCode} 直接就是明文服务名（实测 {@code "apm-demo-a"}），
 * 不是旧版的 {@code base64(服务名) + "." + layerId}。
 * </p>
 * <p>
 * 曾经的实现无条件当 base64 解：{@code "apm-demo-a"} 解出 7 个字节，
 * 按 UTF-8 读成 {@code "j\uFFFD\uFFFDu\uFFFD\uFFFD"}，链路详情里的服务名
 * 就这样坏成了乱码；而且 U+FFFD 码位 ≥ 0x20，原来「可打印字符」的校验拦不住。
 * </p>
 * @author CH
 * @since 4.0.0.51
 */
class SkywalkingServiceCodeTest {

    @Test
    @DisplayName("OAP 9.7.0 的明文服务名：必须原样返回，不得当 base64 解码")
    void plainNameReturnedAsIs() {
        assertEquals("apm-demo-a", SkywalkingOtnlEngine.decodeServiceName("apm-demo-a"));
        assertEquals("apm-demo-b", SkywalkingOtnlEngine.decodeServiceName("apm-demo-b"));
    }

    @Test
    @DisplayName("明文服务名里带点也不能误判：后缀不是数字就不是 layerId")
    void plainNameWithDotsReturnedAsIs() {
        assertEquals("com.example.order", SkywalkingOtnlEngine.decodeServiceName("com.example.order"));
        assertEquals("svc.v2", SkywalkingOtnlEngine.decodeServiceName("svc.v2"));
    }

    @Test
    @DisplayName("旧版 base64 + 数字 layerId：还原成服务名")
    void base64WithLayerIdDecoded() {
        String code = "YXBtLWRlbW8tYQ==.1";
        assertEquals("apm-demo-a", SkywalkingOtnlEngine.decodeServiceName(code));
    }

    @Test
    @DisplayName("中文服务名的 base64 + layerId：按 UTF-8 还原，不出现替换字符")
    void chineseNameDecoded() {
        String name = "订单服务";
        String code = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + ".1";
        assertEquals(name, SkywalkingOtnlEngine.decodeServiceName(code));
    }

    @Test
    @DisplayName("带数字后缀但不是合法 base64：原样返回，不抛异常")
    void invalidBase64WithNumericSuffixReturnsCode() {
        String code = "not base64 at all!.1";
        assertEquals(code, SkywalkingOtnlEngine.decodeServiceName(code));
    }

    @Test
    @DisplayName("空值：返回空串")
    void blankReturnsEmpty() {
        assertEquals("", SkywalkingOtnlEngine.decodeServiceName(null));
        assertEquals("", SkywalkingOtnlEngine.decodeServiceName(""));
        assertEquals("", SkywalkingOtnlEngine.decodeServiceName("   "));
    }
}
