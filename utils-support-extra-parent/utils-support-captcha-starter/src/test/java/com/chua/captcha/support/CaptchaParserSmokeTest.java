package com.chua.captcha.support;

import com.chua.common.support.constant.CaptchaConstant;
import com.chua.common.support.spi.ServiceProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 验证码解析链冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.captcha.support.CaptchaParserSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * <p>所有请求都打到本机临时 HTTP 服务，不访问任何真实服务商。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class CaptchaParserSmokeTest {

    /**
     * 失败计数
     */
    private static int failureCount = 0;
    /**
     * 成功计数
     */
    private static int passCount = 0;
    /**
     * 捕获到的请求（路径 + 请求体）
     */
    private static final List<String> CAPTURED = new ArrayList<>();
    /**
     * getTaskResult 调用次数
     */
    private static int resultCalls = 0;
    /**
     * 是否让任务恒处处理中，用于验证超时分支
     */
    private static boolean alwaysProcessing = false;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", CaptchaParserSmokeTest::handle);
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            testSpiMounted();
            testYesCaptchaImageBody(url);
            testCaptchaRunImageBody(url);
            testTypeMapping(url);
            testUnknownTypeRejected(url);
            testDefaultEndpointPerProvider();
            testAwaitResultReady(url);
            testAwaitResultTimeout(url);
            testFilePersistenceRoundTrip();
        } finally {
            server.stop(0);
        }

        System.out.println("========================================");
        System.out.println("CaptchaParserSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 扩展点登记与按名取用
     */
    private static void testSpiMounted() {
        ServiceProvider<CaptchaParser> provider = ServiceProvider.of(CaptchaParser.class);
        check(provider.listType().size() >= 2, "CaptchaParser 扩展点登记到 " + provider.listType().size() + " 个实现");
        check(provider.isSupport("yescaptcha") && provider.isSupport("captcha-run"), "按 yescaptcha/captcha-run 名称可判定支持");
        CaptchaSetting setting = CaptchaSetting.builder().apiToken("tk").apiUrl("http://127.0.0.1:1").build();
        check(provider.getNewExtension("yescaptcha", setting) instanceof YesCaptchaClient, "带配置构造 yescaptcha 实现");
        check(provider.getNewExtension("captcha-run", setting) instanceof CaptchaRunClient, "带配置构造 captcha-run 实现");
        check(provider.getExtension("yescaptcha") != null, "无参 SPI 实例可创建（配置取自环境变量）");
    }

    /**
     * 图文验证码：imageData 必须编码进请求体
     */
    private static void testYesCaptchaImageBody(String url) {
        byte[] image = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        String base64 = Base64.getEncoder().encodeToString(image);
        CAPTURED.clear();
        new YesCaptchaClient(setting(url)).submitCaptcha(image, Map.of("captchaType", CaptchaType.TEXT_CAPTCHA.getType()));
        String sent = last();
        check(sent.contains("ImageToTextTask"), "yescaptcha 图文任务类型正确");
        check(sent.contains(base64), "yescaptcha 把 imageData 以 Base64 放进 body");
    }

    /**
     * 图文验证码：captcha-run 同样必须带上图片
     */
    private static void testCaptchaRunImageBody(String url) {
        byte[] image = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        String base64 = Base64.getEncoder().encodeToString(image);
        CAPTURED.clear();
        new CaptchaRunClient(setting(url)).submitCaptcha(image, Map.of("captchaType", CaptchaType.TEXT_CAPTCHA.getType()));
        check(last().contains(base64), "captcha-run 把 imageData 以 Base64 放进 body");
    }

    /**
     * 全部声明的类型都有对应任务类型，不再静默降级
     */
    private static void testTypeMapping(String url) {
        byte[] image = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        for (CaptchaType type : CaptchaType.values()) {
            CAPTURED.clear();
            new YesCaptchaClient(setting(url)).submitCaptcha(image, Map.of(
                    "captchaType", type.getType(), "url", "https://target.example", "siteKey", "sk"));
            String sent = last();
            check(!sent.contains("NoCaptchaTaskProxyless") || type == CaptchaType.RECAPTCHA_V2,
                    type.name() + " 未被静默降级为 reCAPTCHA 任务");
        }
    }

    /**
     * 未知类型必须报错
     */
    private static void testUnknownTypeRejected(String url) {
        try {
            new YesCaptchaClient(setting(url)).submitCaptcha(null, Map.of("captchaType", "ReCaptchaxV9"));
            check(false, "未知验证码类型被拒绝");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains("ReCaptchaxV9"), "未知验证码类型被拒绝并回显原值");
        }
    }

    /**
     * 各解析器使用自己的缺省端点
     */
    private static void testDefaultEndpointPerProvider() {
        CaptchaSetting bare = CaptchaSetting.builder().apiToken("tk").build();
        check(CaptchaSetting.CAPTCHA_RUN_API_URL.equals(bare.apiUrlOr(CaptchaSetting.CAPTCHA_RUN_API_URL)),
                "captcha-run 缺省打到 captcha-run 主机");
        check(CaptchaSetting.YESCAPTCHA_API_URL.equals(bare.apiUrlOr(CaptchaSetting.YESCAPTCHA_API_URL)),
                "yescaptcha 缺省打到 yescaptcha 主机");
        try {
            CaptchaSetting.builder().build().requireApiToken();
            check(false, "缺令牌时明确报错而不是把 null 发出去");
        } catch (IllegalStateException e) {
            check(true, "缺令牌时明确报错而不是把 null 发出去");
        }
    }

    /**
     * 轮询等待成功路径
     */
    private static void testAwaitResultReady(String url) {
        resultCalls = 0;
        alwaysProcessing = false;
        CaptchaResponse response = new YesCaptchaClient(setting(url)).awaitResult("T1", 5000, 20);
        check(response.isSuccess(), "轮询到 ready 后返回成功");
        check("AB12".equals(response.getToken()), "取回解答内容");
        check(response.getExecutionTime() > 0, "executionTime 被回填（" + response.getExecutionTime() + "ms）");
        check(response.getErrorCode() == null, "成功响应不带错误码");
    }

    /**
     * 轮询等待超时路径
     */
    private static void testAwaitResultTimeout(String url) {
        resultCalls = 0;
        alwaysProcessing = true;
        try {
            CaptchaResponse response = new YesCaptchaClient(setting(url)).awaitResult("T1", 200, 20);
            check(!response.isSuccess(), "恒处理中时不谎报成功");
            check(CaptchaConstant.ERROR_TIMEOUT.equals(response.getErrorCode()), "超时给出 TIMEOUT 错误码");
        } finally {
            alwaysProcessing = false;
        }
    }

    /**
     * 文件持久化：含分隔符与换行的字段可完整重载
     */
    private static void testFilePersistenceRoundTrip() throws Exception {
        java.nio.file.Path dir = Files.createTempDirectory("captcha-smoke");
        String file = dir.resolve("tasks.txt").toString();
        CaptchaResponse remote = CaptchaResponse.builder()
                .success(false)
                .taskId("T|9")
                .token("tok|abc")
                .message("ERROR_INVALID_KEY: 额度不足\nsecond line")
                .errorCode("E5|1")
                .executionTime(1234L)
                .build();
        TaskPersistence store = TaskPersistence.file(file);
        store.save("T|9", remote);
        Optional<CaptchaResponse> cached = store.query("T|9");
        check(cached.isPresent(), "内存缓存命中");

        TaskPersistence reload = TaskPersistence.file(file);
        CaptchaResponse back = reload.query("T|9").orElse(null);
        check(back != null, "重新加载后记录仍在（分隔符与换行未撑破行格式）");
        if (back != null) {
            check("T|9".equals(back.getTaskId()), "任务标识完整");
            check("tok|abc".equals(back.getToken()), "令牌完整");
            check(remote.getMessage().equals(back.getMessage()), "含换行的错误描述完整");
            check("E5|1".equals(back.getErrorCode()), "错误码完整");
            check(back.getExecutionTime() == 1234L, "执行耗时随记录一起持久化");
        }
        reload.delete("T|9");
        check(TaskPersistence.file(file).query("T|9").isEmpty(), "删除后不再返回");
    }

    /**
     * 指向本机桩服务的配置
     *
     * @param url 服务地址
     * @return 配置
     */
    private static CaptchaSetting setting(String url) {
        return CaptchaSetting.builder().apiToken("tk").apiUrl(url).build();
    }

    /**
     * 桩服务：按 yescaptcha 协议应答 createTask 与 getTaskResult
     *
     * @param exchange 交换
     * @throws java.io.IOException 读写失败
     */
    private static void handle(HttpExchange exchange) throws java.io.IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream in = exchange.getRequestBody()) {
            in.transferTo(buf);
        }
        String path = exchange.getRequestURI().getPath();
        CAPTURED.add(path + " " + buf.toString(StandardCharsets.UTF_8));
        String resp;
        if ("/getTaskResult".equals(path)) {
            resultCalls++;
            resp = (alwaysProcessing || resultCalls < 3)
                    ? "{\"errorId\":0,\"status\":\"processing\"}"
                    : "{\"errorId\":0,\"status\":\"ready\",\"solution\":{\"code\":\"AB12\"}}";
        } else if ("/createTask".equals(path)) {
            resp = "{\"errorId\":0,\"taskId\":\"T1\"}";
        } else {
            resp = "{}";
        }
        byte[] out = resp.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /**
     * 最近一次捕获的请求
     *
     * @return 路径与请求体
     */
    private static String last() {
        return CAPTURED.isEmpty() ? "<无请求>" : CAPTURED.get(CAPTURED.size() - 1);
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
