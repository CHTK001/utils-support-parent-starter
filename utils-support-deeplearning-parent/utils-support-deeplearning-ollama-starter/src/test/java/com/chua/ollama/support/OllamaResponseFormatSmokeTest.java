package com.chua.ollama.support;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.feature.FeatureClient;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.spi.ServiceProvider;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Ollama 响应格式装配门（main 方法直跑，不依赖测试框架与在线服务）。
 *
 * <p>校验 {@code responseFormat} 从配置与链式设置两条入口进入请求对象的完整链路：
 * 未设置时不得约束输出格式，{@code json_object} 才开启 JSON 模式，
 * 其余取值保持普通文本。请求对象经私有构建方法反射获取，只观测不联网。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.ollama.support.OllamaResponseFormatSmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class OllamaResponseFormatSmokeTest {

    /**
     * 私有请求构建方法名
     */
    private static final String BUILD_REQUEST = "buildRequest";

    /**
     * 服务商别名
     */
    private static final String PROVIDER = "ollama";

    private static int pass;

    private static int fail;

    private OllamaResponseFormatSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        checkProviderReachable();
        checkDefaultKeepsFormatUnset();
        checkSettingJsonObject();
        checkSettingTextAndSchema();
        checkFluentSetterApplies();
        checkCaseAndBlankTolerant();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 校验 ollama 在五个能力扩展点上均可按别名解析（本模块用 JDK {@code META-INF/services} 登记）。
     */
    private static void checkProviderReachable() {
        observeAbility(ChatClient.class);
        observeAbility(EmbeddingClient.class);
        observeAbility(FeatureClient.class);
        observeAbility(ImageClient.class);
        observeAbility(VirtualClient.class);
        ChatClient client = ChatClient.create(
                ChatClientSetting.builder().provider("ollama").baseUrl("http://127.0.0.1:1").build());
        observe("ChatClient.create(ollama)=" + (client == null ? "null" : client.getClass().getName()));
        check("ChatClient.create 可取得 ollama 实例", client instanceof OllamaChatClient);
    }

    /**
     * 观测单个能力扩展点上 ollama 别名的解析结果。
     *
     * @param ability 能力接口
     * @param <T> 能力类型
     */
    private static <T> void observeAbility(Class<T> ability) {
        try {
            Map<String, Class<T>> types = ServiceProvider.of(ability).listType();
            boolean found = types.keySet().stream().anyMatch(PROVIDER::equalsIgnoreCase);
            observe(ability.getSimpleName() + " 别名集=" + types.keySet() + " 命中 " + PROVIDER + "=" + found);
            check(ability.getSimpleName() + " 已登记 " + PROVIDER, found);
        } catch (Throwable t) {
            check(ability.getSimpleName() + " 解析异常: " + rootMessage(t), false);
        }
    }

    /**
     * 未设置响应格式时不得开启 JSON 模式。
     */
    private static void checkDefaultKeepsFormatUnset() {
        var format = formatOf(client(null), "你好");
        observe("setting.responseFormat=null 请求 format=" + format);
        check("默认不约束输出格式", format == null);
    }

    /**
     * 配置 json_object 时应开启 JSON 模式。
     */
    private static void checkSettingJsonObject() {
        var format = formatOf(client("json_object"), "给我一个人物档案");
        observe("setting.responseFormat=json_object 请求 format=" + format);
        check("json_object 开启 JSON 模式", "json".equals(String.valueOf(format)));
    }

    /**
     * text 与 json_schema 取值均不得静默改成 JSON 模式。
     */
    private static void checkSettingTextAndSchema() {
        var text = formatOf(client("text"), "介绍一下杭州");
        var schema = formatOf(client("json_schema"), "介绍一下杭州");
        observe("text 请求 format=" + text + " json_schema 请求 format=" + schema);
        check("text 保持普通文本", text == null);
        check("json_schema 不自动约束（缺 schema）", schema == null);
    }

    /**
     * 链式设置应覆盖构造期取值并进入请求。
     */
    private static void checkFluentSetterApplies() {
        ChatClient client = client(null).responseFormat("json_object");
        var enabled = formatOf(client, "算一下 1+1");
        observe("链式 json_object 请求 format=" + enabled);
        check("链式 responseFormat 生效", "json".equals(String.valueOf(enabled)));
        var disabled = formatOf(client.responseFormat(null), "算一下 1+1");
        observe("链式置空后请求 format=" + disabled);
        check("链式置空可撤销约束", disabled == null);
    }

    /**
     * 取值大小写与首尾空白不应影响判定。
     */
    private static void checkCaseAndBlankTolerant() {
        var upper = formatOf(client(" JSON_OBJECT "), "给我一个人物档案");
        var blank = formatOf(client("   "), "你好");
        observe("' JSON_OBJECT ' 请求 format=" + upper + " 空白串请求 format=" + blank);
        check("大小写与空白容错", "json".equals(String.valueOf(upper)));
        check("空白取值等同未设置", blank == null);
    }

    /**
     * 构造客户端。
     *
     * @param responseFormat 响应格式，可为 空
     * @return 对话客户端
     */
    private static ChatClient client(String responseFormat) {
        return new OllamaChatClient(ChatClientSetting.builder()
                .provider("ollama")
                .model("llama3.2:3b")
                .responseFormat(responseFormat)
                .build());
    }

    /**
     * 反射取请求对象的 format 字段。
     *
     * @param client 对话客户端
     * @param prompt 提示词
     * @return 请求格式（未约束时为 空）
     */
    private static Object formatOf(ChatClient client, String prompt) {
        try {
            Method method = OllamaChatClient.class.getDeclaredMethod(BUILD_REQUEST, String.class);
            method.setAccessible(true);
            OllamaChatRequest request = (OllamaChatRequest) method.invoke(client, prompt);
            return request.getFormat();
        } catch (ReflectiveOperationException e) {
            var cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("构建请求失败: " + cause, cause);
        }
    }

    /**
     * 取根因消息。
     *
     * @param t 异常
     * @return 根因描述
     */
    private static String rootMessage(Throwable t) {
        var cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    /**
     * 输出观察值。
     *
     * @param text 观察内容
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录单项校验结果。
     *
     * @param name 校验名
     * @param ok 是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }
}
