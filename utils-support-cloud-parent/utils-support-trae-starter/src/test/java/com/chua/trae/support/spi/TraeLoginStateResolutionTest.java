// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.trae.support.spi;

import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.trae.support.auth.AuthManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trae 登录态解析回归测试。
 *
 * <p>覆盖 {@link TraeChatClient} 的三条登录态来源及其与 {@link AuthManager} 的路径契约：</p>
 * <ol>
 *   <li><b>手动 JWT</b>：只有 {@code eyJ} 开头的 {@code appKey} 才算令牌，其他密钥不参与；</li>
 *   <li><b>本机数据目录</b>：传给 {@code AuthManager} 的必须是「应用数据根目录」，
 *       {@code AuthManager} 内部会再拼 {@code User/globalStorage/storage.json}；</li>
 *   <li><b>版本与目录匹配</b>：国内版优先 {@code Trae CN}、国际版优先 {@code Trae}，
 *       避免用国内 token 打国际网关。</li>
 * </ol>
 *
 * <p>真实登录态相关用例（读取本机 {@code storage.json} 并走 CN 版 tc 解密）在未安装 Trae 的
 * 机器上通过 {@link Assumptions} 自动跳过，不会阻塞 CI。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
class TraeLoginStateResolutionTest {

    /**
     * storage.json 中认证信息对应的键（与 {@code AuthManager} 保持一致）
     */
    private static final String AUTH_KEY = "iCubeAuthInfo://icube.cloudide";

    /**
     * 国内版网关
     */
    private static final String CN_HOST = "https://trae-api-cn.mchost.guru";

    /**
     * 国际版网关
     */
    private static final String GLOBAL_HOST = "https://coresg-normal.trae.ai";

    /**
     * storage.json 相对应用数据根目录的位置（同时也是「不能传给 AuthManager」的路径形态）
     */
    private static final String STORAGE_RELATIVE = "User/globalStorage/storage.json";

    /**
     * 演示用 JWT（结构合法、不联网、非真实凭据）
     */
    private static final String DEMO_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjE3MDAwMDAwMDB9.demo-signature";

    /**
     * JSON 工具
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 国内版：两个版本目录同时存在时必须选 {@code Trae CN}。
     *
     * @param base 临时根目录（模拟 {@code %APPDATA%}）
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("cn 版本优先使用 Trae CN 目录")
    void cnEditionPrefersTraeCnDirectory(@TempDir Path base) throws IOException {
        Path cn = createFakeTrae(base, "Trae CN", DEMO_JWT);
        createFakeTrae(base, "Trae", DEMO_JWT);

        String resolved = TraeChatClient.resolveDataDir("cn", null, base.toString(), null);
        assertEquals(cn.toString(), resolved);
    }

    /**
     * 国际版：两个版本目录同时存在时必须选 {@code Trae}。
     *
     * <p>这是本次验证发现的缺陷对应用例：旧实现固定先取 {@code Trae CN}，
     * 导致 {@code trae-global} 拿国内 token 打国际网关。</p>
     *
     * @param base 临时根目录（模拟 {@code %APPDATA%}）
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("sg 版本优先使用 Trae 目录")
    void sgEditionPrefersTraeDirectory(@TempDir Path base) throws IOException {
        createFakeTrae(base, "Trae CN", DEMO_JWT);
        Path global = createFakeTrae(base, "Trae", DEMO_JWT);

        String resolved = TraeChatClient.resolveDataDir("sg", null, base.toString(), null);
        assertEquals(global.toString(), resolved);
    }

    /**
     * 首选项目录不存在时回退到另一个版本，而不是直接判定「无登录态」。
     *
     * @param base 临时根目录（模拟 {@code %APPDATA%}）
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("首选项缺失时回退到另一版本目录")
    void fallsBackToOtherEditionWhenPreferredMissing(@TempDir Path base) throws IOException {
        Path global = createFakeTrae(base, "Trae", DEMO_JWT);

        assertEquals(global.toString(), TraeChatClient.resolveDataDir("cn", null, base.toString(), null));
    }

    /**
     * 显式 {@code TRAE_DATA_DIR} 优先级最高，且不受版本排序影响。
     *
     * @param base 临时根目录
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("环境变量目录优先于版本目录")
    void envDirectoryWinsOverEdition(@TempDir Path base) throws IOException {
        createFakeTrae(base, "Trae CN", DEMO_JWT);
        createFakeTrae(base, "Trae", DEMO_JWT);
        Path custom = createFakeTrae(base.resolve("custom"), "any", DEMO_JWT);

        assertEquals(custom.toString(),
                TraeChatClient.resolveDataDir("sg", custom.toString(), base.toString(), null));
    }

    /**
     * 仅有目录、缺少 {@code storage.json} 时视为没有登录态（不能把空目录当登录态）。
     *
     * @param base 临时根目录
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("缺少 storage.json 时不返回目录")
    void ignoresDirectoryWithoutStorageJson(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("Trae CN").resolve("User").resolve("globalStorage"));

        assertNull(TraeChatClient.resolveDataDir("cn", null, base.toString(), null));
    }

    /**
     * 返回的必须是「应用数据根目录」：其下 {@code User/globalStorage/storage.json} 存在，
     * 且路径本身不含 {@code globalStorage} 段（否则 {@code AuthManager} 会拼成两段 User）。
     *
     * @param base 临时根目录
     * @throws IOException 造目录失败
     */
    @Test
    @DisplayName("返回应用数据根目录而非 globalStorage")
    void returnsApplicationRootNotGlobalStorage(@TempDir Path base) throws IOException {
        Path cn = createFakeTrae(base, "Trae CN", DEMO_JWT);

        String resolved = TraeChatClient.resolveDataDir("cn", null, base.toString(), null);
        assertNotNull(resolved);
        assertTrue(Files.exists(Path.of(resolved, "User", "globalStorage", "storage.json")));
        assertFalse(resolved.contains("globalStorage"), "不得返回 globalStorage 路径: " + resolved);
        assertEquals(cn.toString(), resolved);
    }

    /**
     * 手动令牌只接受 JWT（{@code eyJ} 前缀），并去除首尾空白。
     */
    @Test
    @DisplayName("只有 JWT 形态的 appKey 被当作令牌")
    void manualTokenOnlyAcceptsJwtPrefix() throws Exception {
        TraeChatClient jwtClient = new TraeChatClient(ChatClientSetting.builder()
                .provider("trae")
                .appKey("  " + DEMO_JWT + "  ")
                .build());
        assertEquals(DEMO_JWT, invokeString(jwtClient, "resolveManualToken"));

        TraeChatClient apiKeyClient = new TraeChatClient(ChatClientSetting.builder()
                .provider("trae")
                .appKey("sk-not-a-jwt")
                .build());
        assertNull(invokeString(apiKeyClient, "resolveManualToken"));
    }

    /**
     * 与 {@link AuthManager} 的路径契约：必须传应用数据根目录。
     *
     * <p>反向验证「传 globalStorage 目录」会失败，说明
     * {@code AuthManager#fromTraeDataDir} 的语义不能直接用于本客户端的探测结果。</p>
     *
     * @param base 临时根目录
     * @throws Exception 造目录失败 / 读取登录态失败
     */
    @Test
    @DisplayName("AuthManager 只接受应用数据根目录")
    void authManagerRequiresApplicationRoot(@TempDir Path base) throws Exception {
        Path root = createFakeTrae(base, "Trae CN", DEMO_JWT);

        AuthManager.AuthSnapshot snapshot = new AuthManager("cn", root.toString(), null, CN_HOST).getAuth();
        assertEquals(DEMO_JWT, snapshot.token());
        assertEquals("user-demo", snapshot.userId());
        assertEquals("cn", snapshot.edition());

        // 传 globalStorage 目录 → AuthManager 再拼一次 User/globalStorage，必然找不到文件
        AuthManager wrong = new AuthManager("cn",
                root.resolve("User").resolve("globalStorage").toString(), null, CN_HOST);
        AuthManager.AuthException error = assertThrows(AuthManager.AuthException.class, wrong::getAuth);
        assertTrue(error.getMessage().contains("storage.json not found"), error.getMessage());
    }

    /**
     * 网关地址：provider 决定版本，显式 baseUrl 覆盖并去掉尾部斜杠。
     */
    @Test
    @DisplayName("provider 与 baseUrl 决定网关地址")
    void apiHostFollowsProvider() throws Exception {
        assertEquals(CN_HOST, readField(new TraeChatClient(ChatClientSetting.builder()
                .provider("trae").build()), "apiHost"));
        assertEquals(CN_HOST, readField(new TraeChatClient(ChatClientSetting.builder()
                .provider("trae-cn").build()), "apiHost"));
        assertEquals(GLOBAL_HOST, readField(new TraeChatClient(ChatClientSetting.builder()
                .provider("trae-global").build()), "apiHost"));
        assertEquals("https://proxy.example.com", readField(new TraeChatClient(ChatClientSetting.builder()
                .provider("trae-global").baseUrl("https://proxy.example.com/").build()), "apiHost"));
    }

    /**
     * 没有登录态时也能拿到内置模型清单（构造与 {@code models()} 都不触达登录态）。
     */
    @Test
    @DisplayName("无登录态时模型清单仍可读取")
    void modelsAvailableWithoutLoginState() {
        TraeChatClient client = new TraeChatClient(ChatClientSetting.builder().provider("trae").build());
        try {
            assertFalse(client.models().isEmpty(), "内置 model-config.json 应至少包含一个模型");
        } finally {
            client.close();
        }
    }

    /**
     * 出图张数超上限必须在**任何网络调用之前**被拒绝（按张计费，防止参数被放大）。
     */
    @Test
    @DisplayName("出图张数超过上限直接拒绝")
    void rejectsTooManyImages() {
        TraeChatClient client = new TraeChatClient(ChatClientSetting.builder().provider("trae").build());
        try {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> client.generateImage("一只猫", "1:1", 50, 0, 0, null, null));
            assertTrue(error.getMessage().contains("单次最多"), error.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * 空提示词与不合法的自定义尺寸同样是本地校验失败（不发请求、不计费）。
     */
    @Test
    @DisplayName("空提示词与非法尺寸在本地被拒")
    void rejectsInvalidImageArguments() {
        TraeChatClient client = new TraeChatClient(ChatClientSetting.builder().provider("trae").build());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> client.generateImage("", "1:1", 1, 0, 0, null, null));
            // 1024x1024 总像素低于 Ark 下限，必须在本地被拒（否则上游报错且浪费时间）
            IllegalArgumentException sizeError = assertThrows(IllegalArgumentException.class,
                    () -> client.generateImage("一只猫", "1:1", 1, 1024, 1024, null, null));
            assertTrue(sizeError.getMessage().contains("总像素"), sizeError.getMessage());
        } finally {
            client.close();
        }
    }

    /**
     * 本机真实登录态探测（国内版）：解析目录 → 读 storage.json（CN 版为 tc 加密串）→ 得到 JWT。
     *
     * <p>未安装 Trae 的环境自动跳过。</p>
     *
     * @throws Exception 反射 / 读取失败
     */
    @Test
    @DisplayName("本机真实登录态可被解析（未安装则跳过）")
    void realMachineLoginStateResolves() throws Exception {
        assertRealLoginState("cn");
    }

    /**
     * 本机真实登录态探测（国际版）：{@code sg} 必须命中 {@code Trae} 目录且能解出 JWT。
     *
     * <p>本机只装了国内版时自动跳过；同时装了国际版时用来证明「版本 → 目录」映射没有串味。</p>
     *
     * @throws Exception 反射 / 读取失败
     */
    @Test
    @DisplayName("国际版本机登录态可被解析（未安装则跳过）")
    void realMachineGlobalLoginStateResolves() throws Exception {
        assertRealLoginState("sg");
    }

    /**
     * 按版本探测本机真实登录态并断言拿到 JWT。
     *
     * @param edition 版本：{@code cn} / {@code sg}
     * @throws Exception 反射 / 读取失败
     */
    private static void assertRealLoginState(String edition) throws Exception {
        String dataDir = TraeChatClient.resolveDataDir(edition,
                System.getenv("TRAE_DATA_DIR"), System.getenv("APPDATA"), System.getProperty("user.home"));
        Assumptions.assumeTrue(dataDir != null, "本机未检测到 " + edition + " 版 Trae 登录态，跳过真实数据校验");

        AuthManager.AuthSnapshot snapshot = new AuthManager(edition, dataDir, null,
                "sg".equals(edition) ? GLOBAL_HOST : CN_HOST).getAuth();
        assertNotNull(snapshot.token(), "storage.json 中应存在 token");
        assertTrue(snapshot.token().startsWith("eyJ"), "token 应为 JWT（tc 解密结果）");
        assertTrue(snapshot.token().length() > 60, "JWT 长度异常");
        // 版本必须与目录一致：sg 取的是 Trae，cn 取的是 Trae CN
        if ("sg".equals(edition)) {
            assertTrue(dataDir.endsWith("Trae"), "sg 应命中 Trae 目录: " + dataDir);
        } else {
            assertTrue(dataDir.endsWith("Trae CN"), "cn 应命中 Trae CN 目录: " + dataDir);
        }
    }

    /**
     * 在临时目录下伪造一份 Trae 安装数据（仅结构，不含真实凭据）。
     *
     * @param base   根目录
     * @param appName 应用目录名（{@code Trae CN} / {@code Trae}）
     * @param token  token
     * @return 应用数据根目录
     * @throws IOException 写文件失败
     */
    private static Path createFakeTrae(Path base, String appName, String token) throws IOException {
        Path root = base.resolve(appName);
        Path storage = root.resolve("User").resolve("globalStorage").resolve("storage.json");
        Files.createDirectories(storage.getParent());

        Map<String, String> auth = new LinkedHashMap<>(8);
        auth.put("token", token);
        auth.put("refreshToken", "refresh-demo");
        auth.put("expiredAt", "2099-01-01T00:00:00Z");
        auth.put("refreshExpiredAt", "2099-02-01T00:00:00Z");
        auth.put("userId", "user-demo");
        auth.put("host", "https://host.example.com");
        auth.put("userRegion", "CN");
        auth.put("account", "demo");

        // 明文 JSON 字符串（非 tc 加密串）：AuthManager 会直接解析
        ObjectNode node = MAPPER.createObjectNode();
        node.put(AUTH_KEY, MAPPER.writeValueAsString(auth));
        Files.writeString(storage, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
        return root;
    }

    /**
     * 反射调用返回 {@code String} 的私有方法。
     *
     * @param target     实例
     * @param methodName 方法名
     * @return 返回值
     * @throws Exception 反射失败
     */
    private static String invokeString(TraeChatClient target, String methodName) throws Exception {
        Method method = TraeChatClient.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    /**
     * 反射读取 {@code String} 字段。
     *
     * @param target    实例
     * @param fieldName 字段名
     * @return 字段值
     * @throws Exception 反射失败
     */
    private static String readField(TraeChatClient target, String fieldName) throws Exception {
        Field field = TraeChatClient.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(target);
    }
}