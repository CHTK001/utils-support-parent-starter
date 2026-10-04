package com.chua.common.support.lang.code;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReturnResult 的核心契约。
 *
 * <p>这个类是全仓每个 Controller / Service 的返回类型，契约一旦被动摇，
 * 影响面是「所有接口」，而不是某一个模块。所以这里钉的是<b>语义边界</b>，
 * 不是某一处实现细节。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("ReturnResult 契约")
class ReturnResultContractTest {

    @Test
    @DisplayName("ok / success 成功判定：两个 ok 码都算成功")
    void bothOkCodesCountAsSuccess() {
        assertTrue(ReturnResult.ok("x").isOk());
        assertTrue(ReturnResult.ok("x").isSuccess(), "isSuccess 是 isOk 的别名，两者必须一致");
        assertTrue(ReturnResult.ok("x").isSuccess());

        var okCode = ReturnResult.ok("x");
        okCode.code(ReturnCode.OK.getCode());
        assertTrue(okCode.isOk(), "ReturnCode.OK 也必须算成功，两个成功码历史上并存");

        assertFalse(ReturnResult.error("boom").isOk());
        assertFalse(ReturnResult.error("boom").isSuccess());
    }

    @Test
    @DisplayName("orElse 降级后必须保留原始失败原因（重构前会把它覆盖成「成功」）")
    void orElseKeepsOriginalFailureMessage() {
        ReturnResult<String> result = ReturnResult.error("数据库连接超时");

        ReturnResult<String> after = result.orElse("fallback");

        assertTrue(after.isOk(), "降级后应转为成功");
        assertEquals("fallback", after.getData(), "降级值必须生效");
        assertEquals("数据库连接超时", after.getMsg(),
                "失败原因不能在降级时被抹掉 —— 它在排障时的价值高于「成功」二字；"
                        + "覆盖掉之后，调用方即使打了日志也查不出当初为什么失败");
    }

    @Test
    @DisplayName("orElseMarked 显式给出降级说明时覆盖 msg")
    void orElseMarkedOverwritesMessageWhenGiven() {
        ReturnResult<String> after = ReturnResult.<String>error("数据库连接超时")
                .orElseMarked("fallback", "已降级为默认值：数据库不可用");

        assertTrue(after.isOk());
        assertEquals("fallback", after.getData());
        assertEquals("已降级为默认值：数据库不可用", after.getMsg(),
                "显式标注时以标注为准");
    }

    @Test
    @DisplayName("orElseMarked 传空白串时视为未标注，保留原信息")
    void orElseMarkedIgnoresBlankDegrade() {
        ReturnResult<String> after = ReturnResult.<String>error("原始失败")
                .orElseMarked("fallback", "   ");

        assertEquals("原始失败", after.getMsg(), "空白标注不应覆盖原始失败信息");
    }

    @Test
    @DisplayName("orElseGet 与 orElse 语义一致，同样保留失败原因")
    void orElseGetKeepsOriginalFailureMessage() {
        ReturnResult<String> after = ReturnResult.<String>error("下游超时")
                .orElseGet(() -> "computed");

        assertTrue(after.isOk());
        assertEquals("computed", after.getData(), "默认值必须来自 supplier");
        assertEquals("下游超时", after.getMsg(), "orElseGet 同样不能抹掉失败原因");
    }

    @Test
    @DisplayName("成功状态下 orElse 不改变任何东西")
    void orElseIsNoOpWhenAlreadyOk() {
        ReturnResult<String> before = ReturnResult.ok("real");
        String originalMsg = before.getMsg();

        ReturnResult<String> after = before.orElse("fallback");

        assertEquals("real", after.getData(), "成功时不能被默认值覆盖");
        assertEquals(originalMsg, after.getMsg(), "成功时 msg 不该被改动");
    }

    @Test
    @DisplayName("失败可携带载荷：success 仍为 false，但 data 可用（服务管理建节点场景）")
    void failureCanCarryPayload() {
        ReturnResult<String> result = ReturnResult.error("BIZ_NODE_START_FAILED", "节点已保存但启动失败", "node-7001");

        assertFalse(result.isSuccess(), "带载荷不能让 success 变 true");
        assertEquals("node-7001", result.getData(),
                "失败但已落库时必须能拿到 id，调用方才能按 id 回滚");
        assertEquals("节点已保存但启动失败", result.getMsg());
    }

    @Test
    @DisplayName("不带载荷的失败 data 仍为 null（不得让「没落库」被误读成「已落库」）")
    void plainFailureHasNoPayload() {
        assertNull(ReturnResult.error("节点不存在").getData());
    }

    @Test
    @DisplayName("ifOk / ifError 按成功与否各自触发一次")
    void conditionalCallbacksDispatchByStatus() {
        var okHits = new java.util.ArrayList<String>();
        var errHits = new java.util.ArrayList<String>();

        ReturnResult.ok("v").ifOk(okHits::add).ifError(errHits::add);
        assertEquals(List.of("v"), okHits);
        assertTrue(errHits.isEmpty(), "成功时不应触发 ifError");

        // 显式给类型参数：error(String) 的 T 会被推断成 Object，
        // 而 ifOk 要的是 Consumer<String>，不显式指定就编译不过
        ReturnResult.<String>error("bad").ifOk(okHits::add).ifError(errHits::add);
        assertEquals(1, errHits.size(), "失败时应触发 ifError 一次");
        assertEquals("bad", errHits.get(0));
        assertEquals(List.of("v"), okHits, "失败时不应再触发 ifOk");
    }

    @Test
    @DisplayName("已删除的反射填充 API 不得复活（字段名是字符串，编译期查不出错）")
    void reflectiveFillApiIsGone() {
        for (String gone : new String[]{"with", "build"}) {
            boolean present = false;
            for (var m : ReturnResult.class.getMethods()) {
                if (m.getName().equals(gone) && m.getParameterCount() >= 1) {
                    present = true;
                    break;
                }
            }
            assertFalse(present,
                    "ReturnResult." + gone + "(...) 是靠字段名字符串反射填充的 API："
                            + "字段名打错编译期查不出来，运行期还会被 catch 吞掉、"
                            + "静默无效。实测全仓零调用者，已删除，不应复活");
        }
        assertTrue(getDeclaredFieldNames().stream().noneMatch(n -> n.equals("temp")),
                "temp 字段只服务于反射填充，一并删除");
    }

    /**
     * 取本类声明的所有字段名。
     *
     * @return 字段名集合
     */
    private static java.util.List<String> getDeclaredFieldNames() {
        return java.util.Arrays.stream(ReturnResult.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
    }

    @Test
    @DisplayName("map 把成功载荷逐项映射，失败时保持不动")
    void mapTransformsOnlyOnSuccess() {
        var ok = ReturnResult.ok("abc").map(String::toUpperCase);
        assertTrue(ok.isOk());
        assertEquals("ABC", ok.getData());

        var err = ReturnResult.<String>error("boom").orElseMarked("zzz", null);
        var afterMap = err.map(String::toUpperCase);
        assertTrue(afterMap.isOk());
        assertEquals("ZZZ", afterMap.getData(), "降级后的载荷同样参与 map");
    }

    @Test
    @DisplayName("链式 setter 返回 this，可直接串起来")
    void settersAreChainable() {
        var result = ReturnResult.<String>ok().code("C").msg("M").data("D");

        assertNotNull(result);
        assertEquals("C", result.getCode());
        assertEquals("M", result.getMsg());
        assertEquals("D", result.getData());
    }
}
