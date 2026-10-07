package com.chua.deeplearning.support.engine;

import com.chua.deeplearning.support.model.HardwareConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelRegistry downloadFiles 多文件下载能力验收。
 *
 * <p>覆盖全部受本能力影响的公开入口：</p>
 * <ul>
 *   <li>{@link ModelRegistry.Entry}：防御性拷贝 / null 语义 / 不可变；</li>
 *   <li>{@code register} 新旧全部重载：downloadFiles 存取与旧重载 null 兼容；</li>
 *   <li>{@code registerExternalDirectory}：外部目录条目 downloadFiles 恒为 null；</li>
 *   <li>{@code ensureDownloaded}：新下载（相对+绝对+混合清单）、镜像目录回退、
 *       附属文件失败不阻断主文件、幂等、缓存命中补拉缺失附属文件；</li>
 *   <li>{@code resolveModelPath}：缓存命中路径补拉缺失附属文件。</li>
 * </ul>
 *
 * <p>通过 {@link ModelRegistry#setDownloader} 注入假下载器，全部用例离线可跑；
 * 测试结束恢复 extractRoot / modelRootDir / downloader 默认值，避免污染同 JVM
 * 其他测试类（本模块 surefire 未配置 reuseForks=false）。</p>
 */
class ModelRegistryDownloadFilesTest {

    /** 主文件地址（unit.test 为保留测试域名，假下载器按 URL 精确供数） */
    private static final String MAIN = "http://unit.test/models/unit-main.bin";

    /** 主文件镜像地址 */
    private static final String MIRROR = "http://mirror.test/repo/unit-main.bin";

    /** 相对附属文件（相对主文件同目录） */
    private static final String RELATIVE = "vocab.txt";

    /** 主文件目录下的相对附属文件全地址 */
    private static final String RELATIVE_URL = "http://unit.test/models/vocab.txt";

    /** 绝对附属文件（带查询串，落盘文件名应剥离查询串） */
    private static final String ABSOLUTE = "http://unit.test/shared/labels.txt?dl=1";

    /** 绝对附属文件落盘名（剥离查询串） */
    private static final String ABSOLUTE_NAME = "labels.txt";

    /** 保存的模型根目录（@AfterAll 恢复用） */
    private static String savedModelRootDir;

    /** 隔离用临时目录 */
    private static Path tempRoot;

    /**
     * 假下载器：按 URL 精确供数，未登记的 URL 抛 404（模拟镜像不可达），
     * 并记录全部被请求的 URL 供断言。
     */
    private static final class FakeDownloader implements ModelRegistry.ModelDownloader {

        private final Map<String, byte[]> remote = new LinkedHashMap<>();

        private final List<String> requested = new ArrayList<>();

        FakeDownloader serve(String url, String content) {
            remote.put(url, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        List<String> requested() {
            return requested;
        }

        @Override
        public Path download(String url, Path target) throws Exception {
            requested.add(url);
            byte[] body = remote.get(url);
            if (body == null) {
                throw new IOException("HTTP 404: " + url);
            }
            Files.createDirectories(target.getParent());
            Files.write(target, body);
            return target;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        savedModelRootDir = ModelRegistry.getModelRootDir();
        tempRoot = Files.createTempDirectory("dlfiles-test");
        ModelRegistry.setExtractRoot(tempRoot);
        // 模型根目录指向空目录，避免 resolveModelPath 扫到仓内真实模型
        ModelRegistry.setModelRootDir(tempRoot.resolve("models").toString());
    }

    @AfterAll
    static void tearDown() throws Exception {
        ModelRegistry.setExtractRoot(defaultExtractRoot());
        ModelRegistry.setModelRootDir(savedModelRootDir);
        ModelRegistry.setDownloader(new ModelRegistry.DefaultModelDownloader());
        if (tempRoot != null) {
            try (var walk = Files.walk(tempRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /**
     * 复刻 {@code initExtractRoot} 逻辑——extractRoot 无 getter，
     * 只能按同规则推导默认值恢复。
     *
     * @return 默认下载缓存目录
     */
    private static Path defaultExtractRoot() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        return (prop != null && !prop.isBlank())
                ? Paths.get(prop.trim())
                : Paths.get(System.getProperty("java.io.tmpdir"), "chua-dl-models");
    }

    /**
     * 注册一个带下载信息的测试模型。
     *
     * @param modelId      模型标识
     * @param downloadUrl  主地址
     * @param mirrors      镜像，可为 空
     * @param files        附属文件清单，可为 空
     * @param relativePath relative路径，可为 空
     */
    private static void register(String modelId, String downloadUrl, List<String> mirrors,
                                 List<String> files, String relativePath) {
        ModelRegistry.register(modelId, "com.chua.fake.FakeTranslator",
                String.class, String.class, null, relativePath,
                downloadUrl, mirrors, files, false, null);
    }

    // ============ Entry record 契约 ============

    @Test
    void entryDownloadFilesIsDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of(RELATIVE, ABSOLUTE));
        ModelRegistry.Entry entry = new ModelRegistry.Entry("dlfiles-entry", "com.chua.fake.T",
                String.class, String.class, null, null, MAIN, null, mutable, false, null, null);

        // 注册后修改源列表，entry 不受影响（防御性拷贝）
        mutable.add("later.txt");
        assertEquals(2, entry.downloadFiles().size());
        assertEquals(RELATIVE, entry.downloadFiles().getFirst());
    }

    @Test
    void entryDownloadFilesNullStaysNullAndIsUnmodifiable() {
        ModelRegistry.Entry nullEntry = new ModelRegistry.Entry("dlfiles-entry-null", "com.chua.fake.T",
                String.class, String.class, null, null, MAIN, null, null, false, null, null);
        assertNull(nullEntry.downloadFiles(), "显式 null 应保留 null 语义");

        ModelRegistry.Entry entry = new ModelRegistry.Entry("dlfiles-entry-imm", "com.chua.fake.T",
                String.class, String.class, null, null, MAIN, null, List.of(RELATIVE), false, null, null);
        assertThrows(UnsupportedOperationException.class, () -> entry.downloadFiles().add("x.txt"),
                "downloadFiles 应为不可变视图");
    }

    // ============ register 重载：新旧全兼容 ============

    @Test
    void registerNewOverloadWithDownloadFilesStoresList() {
        register("dlfiles-reg-files", MAIN, null, List.of(RELATIVE, ABSOLUTE), null);
        ModelRegistry.Entry entry = ModelRegistry.get("dlfiles-reg-files");
        assertNotNull(entry);
        assertEquals(List.of(RELATIVE, ABSOLUTE), entry.downloadFiles());
        assertFalse(entry.compress());
        assertNull(entry.hardwareConfig());
    }

    @Test
    void registerCanonicalOverloadStoresFilesAndHardware() {
        HardwareConfig hw = HardwareConfig.builder().recommended(true).minVramMb(8192L).build();
        ModelRegistry.register("dlfiles-reg-canonical", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, null,
                MAIN, List.of(MIRROR), List.of(RELATIVE), false, null, hw);

        ModelRegistry.Entry entry = ModelRegistry.get("dlfiles-reg-canonical");
        assertNotNull(entry);
        assertEquals(List.of(RELATIVE), entry.downloadFiles());
        assertNotNull(entry.hardwareConfig());
        assertTrue(entry.isRecommended());
        assertEquals(List.of(MIRROR), entry.downloadMirrors());
    }

    @Test
    void registerLegacyOverloadsKeepDownloadFilesNull() {
        // 9 参：(url, compress, fileName)
        ModelRegistry.register("dlfiles-reg-9", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, null, MAIN, false, null);
        // 10 参：(url, mirrors, compress, fileName)
        ModelRegistry.register("dlfiles-reg-10", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, null, MAIN, List.of(MIRROR), false, null);
        // 11 参旧：(url, mirrors, compress, fileName, hardwareConfig)——第 9 参 boolean 与
        // 新 11 参第 9 参 List 区分，此调用同时守住重载解析不歧义
        ModelRegistry.register("dlfiles-reg-11hw", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, null, MAIN, List.of(MIRROR), false, null,
                HardwareConfig.builder().build());
        // 6 参：仅 relativePath
        ModelRegistry.register("dlfiles-reg-6", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, "nlp/dummy/model.onnx");

        assertNull(ModelRegistry.get("dlfiles-reg-9").downloadFiles());
        assertNull(ModelRegistry.get("dlfiles-reg-10").downloadFiles());
        assertNull(ModelRegistry.get("dlfiles-reg-11hw").downloadFiles());
        assertNull(ModelRegistry.get("dlfiles-reg-6").downloadFiles());
    }

    @Test
    void registerExternalDirectoryHasNullDownloadFiles() {
        String key = tempRoot.resolve("ext-model").toString();
        register("dlfiles-ext-template", MAIN, null, List.of(RELATIVE), null);
        try {
            String registered = ModelRegistry.registerExternalDirectory(key, "dlfiles-ext-template");
            assertEquals(key, registered);
            ModelRegistry.Entry external = ModelRegistry.get(key);
            assertNotNull(external);
            assertNull(external.downloadFiles(), "外部目录条目不应携带下载清单");
        } finally {
            ModelRegistry.unregisterExternal(key);
        }
        assertFalse(ModelRegistry.isRegistered(key));
    }

    // ============ ensureDownloaded：新下载路径（finishDownload 挂接点） ============

    @Test
    void ensureDownloadedFetchesMainRelativeAndAbsoluteFiles() throws Exception {
        register("dlfiles-fresh", MAIN, null, Arrays.asList(RELATIVE, ABSOLUTE, "", null), null);
        FakeDownloader fake = new FakeDownloader()
                .serve(MAIN, "main-bytes")
                .serve(RELATIVE_URL, "vocab-bytes")
                .serve(ABSOLUTE, "labels-bytes");
        ModelRegistry.setDownloader(fake);

        Path main = ModelRegistry.ensureDownloaded("dlfiles-fresh");
        assertNotNull(main);
        assertTrue(Files.isRegularFile(main));
        assertEquals("main-bytes", Files.readString(main));

        Path downloadDir = tempRoot.resolve("download").resolve("dlfiles-fresh");
        assertEquals("vocab-bytes", Files.readString(downloadDir.resolve(RELATIVE)),
                "相对附属文件应按主文件同目录拼接下载");
        assertEquals("labels-bytes", Files.readString(downloadDir.resolve(ABSOLUTE_NAME)),
                "绝对附属文件应剥离查询串后落盘");

        // 空白/null 清单元素被跳过，不产生额外请求
        assertTrue(fake.requested().contains(MAIN));
        assertTrue(fake.requested().contains(RELATIVE_URL));
        assertTrue(fake.requested().contains(ABSOLUTE));
        assertEquals(3, fake.requested().size(), "空白元素不应触发下载: " + fake.requested());
    }

    @Test
    void relativeFileResolvesAgainstMirrorDirectoryWhenPrimaryFails() throws Exception {
        register("dlfiles-mirror", MAIN, List.of(MIRROR), List.of(RELATIVE), null);
        FakeDownloader fake = new FakeDownloader()
                // 主地址整体 404，主文件与附属文件都只能从镜像取
                .serve(MIRROR, "main-bytes")
                .serve("http://mirror.test/repo/" + RELATIVE, "vocab-bytes");
        ModelRegistry.setDownloader(fake);

        Path main = ModelRegistry.ensureDownloaded("dlfiles-mirror");
        assertNotNull(main, "主地址失败、镜像成功时应返回镜像下载的主文件");
        assertTrue(fake.requested().contains(MAIN), "应先尝试主地址");
        assertTrue(fake.requested().contains(MIRROR));
        assertTrue(fake.requested().contains("http://mirror.test/repo/" + RELATIVE),
                "相对附属文件应以实际下载成功的镜像地址为基准: " + fake.requested());

        Path vocab = tempRoot.resolve("download").resolve("dlfiles-mirror").resolve(RELATIVE);
        assertEquals("vocab-bytes", Files.readString(vocab));
    }

    @Test
    void declaredFileFailureDoesNotBlockMainFile() throws Exception {
        register("dlfiles-fail-soft", MAIN, null, List.of(RELATIVE), null);
        // 仅提供主文件，vocab.txt 全地址 404
        FakeDownloader fake = new FakeDownloader().serve(MAIN, "main-bytes");
        ModelRegistry.setDownloader(fake);

        Path main = ModelRegistry.ensureDownloaded("dlfiles-fail-soft");
        assertNotNull(main, "附属文件失败不应阻断主文件");
        assertEquals("main-bytes", Files.readString(main));
        assertFalse(Files.exists(tempRoot.resolve("download").resolve("dlfiles-fail-soft").resolve(RELATIVE)));
        assertTrue(fake.requested().contains(RELATIVE_URL), "应尝试过附属文件下载");
    }

    @Test
    void secondEnsureDownloadedIsIdempotent() {
        register("dlfiles-idem", MAIN, null, List.of(RELATIVE), null);
        FakeDownloader fake = new FakeDownloader()
                .serve(MAIN, "main-bytes")
                .serve(RELATIVE_URL, "vocab-bytes");
        ModelRegistry.setDownloader(fake);

        Path first = ModelRegistry.ensureDownloaded("dlfiles-idem");
        assertNotNull(first);
        int afterFirst = fake.requested().size();
        assertEquals(2, afterFirst);

        fake.requested().clear();
        Path second = ModelRegistry.ensureDownloaded("dlfiles-idem");
        assertEquals(first, second, "缓存命中应返回同一路径");
        assertTrue(fake.requested().isEmpty(), "主文件与附属文件均存在时不应重复下载: " + fake.requested());
    }

    // ============ 缓存命中补拉（ensureDownloaded / resolveModelPath 挂接点） ============

    @Test
    void cacheHitFetchesMissingDeclaredFileOnly() throws Exception {
        register("dlfiles-backfill", MAIN, null, List.of(RELATIVE), null);
        FakeDownloader fake = new FakeDownloader()
                .serve(MAIN, "main-bytes")
                .serve(RELATIVE_URL, "vocab-bytes");
        ModelRegistry.setDownloader(fake);

        Path main = ModelRegistry.ensureDownloaded("dlfiles-backfill");
        assertNotNull(main);

        // 删掉附属文件模拟历史版本只下载了主文件
        Path vocab = main.getParent().resolve(RELATIVE);
        Files.delete(vocab);
        fake.requested().clear();

        Path again = ModelRegistry.ensureDownloaded("dlfiles-backfill");
        assertEquals(main, again);
        assertTrue(Files.isRegularFile(vocab), "缓存命中应补拉缺失的附属文件");
        assertEquals(List.of(RELATIVE_URL), fake.requested(),
                "只应补拉附属文件，主文件不应重新下载: " + fake.requested());
    }

    @Test
    void resolveModelPathCacheHitFetchesMissingDeclaredFile() throws Exception {
        register("dlfiles-resolve", MAIN, null, List.of(RELATIVE), null);
        FakeDownloader fake = new FakeDownloader()
                .serve(MAIN, "main-bytes")
                .serve(RELATIVE_URL, "vocab-bytes");
        ModelRegistry.setDownloader(fake);

        Path main = ModelRegistry.ensureDownloaded("dlfiles-resolve");
        assertNotNull(main);
        Path vocab = main.getParent().resolve(RELATIVE);
        Files.delete(vocab);
        fake.requested().clear();

        // relativePath 为空 → 走 downloadUrl 缓存命中分支
        Path resolved = ModelRegistry.resolveModelPath("dlfiles-resolve");
        assertEquals(main, resolved);
        assertTrue(Files.isRegularFile(vocab), "resolveModelPath 缓存命中应补齐附属文件");
        assertEquals(List.of(RELATIVE_URL), fake.requested(),
                "主文件不应重新下载: " + fake.requested());
    }

    @Test
    void ensureDownloadedReturnsNullForMissingEntryOrNoUrl() {
        assertNull(ModelRegistry.ensureDownloaded("dlfiles-not-registered"));

        ModelRegistry.register("dlfiles-no-url", "com.chua.fake.FakeTranslator",
                String.class, String.class, null, "nlp/dummy/model.onnx");
        assertNull(ModelRegistry.ensureDownloaded("dlfiles-no-url"), "无 downloadUrl 时应返回空");
    }
}
