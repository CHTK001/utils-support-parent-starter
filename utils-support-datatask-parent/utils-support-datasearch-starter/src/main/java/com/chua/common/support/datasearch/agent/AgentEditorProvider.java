package com.chua.common.support.datasearch.agent;

import com.chua.common.support.ai.mcp.*;
import com.chua.common.support.ai.skill.*;
import com.chua.common.support.datasearch.music.model.MusicSearchResult;
import com.chua.common.support.datasearch.music.model.MusicPlaylistDetail;
import com.chua.common.support.datasearch.music.model.MusicTrackDetail;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.datasearch.network.lang.code.ListReturnResult;
import com.chua.common.support.datasearch.network.lang.code.ReturnPageResult;
import com.chua.common.support.datasearch.video.model.VideoInfoResult;
import com.chua.common.support.datasearch.video.model.VideoSearch;
import com.chua.common.support.datasearch.video.spi.DownloadLinkProvider;
import com.chua.common.support.datasearch.video.spi.ResourceProvider;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * datasearch 的 MCP 提供器 + Skills 提供器 + Agent Editor 配置读写。
 *
 * <p>合并了以下能力：
 * <ul>
 *   <li>{@link McpProvider} 实现 — 将 datasearch 视频/音乐搜索能力注册为 MCP 工具</li>
 *   <li>Skills 提供器 — 将 datasearch 能力注册为 {@link SkillDefinition}</li>
 *   <li>Agent Editor 配置读取 — 从本地读取各 AI 编辑器的 MCP/Skills 配置</li>
 *   <li>Agent Editor 配置生成 — 将 datasearch 配置写入各编辑器目录</li>
 *   <li>自定义扩展 — 支持添加自定义编辑器配置路径</li>
 *   <li>{@code CodeBuddy} — 扫描/安装/卸载 {@code .codebuddy/mcp.json}（工作区级别）</li>
 *   <li>{@code TRAE-CN} — 扫描 {@code .trae-cn/plugins/} MCP 配置 和 {@code .trae-cn/skills/}</li>
 * </ul>
 *
 * <p>支持的 AI 编辑器：Cursor、Claude Code、Codex、Windsurf、Cline、Roo Code、
 * Trae、Augment Code、Continue、Gemini CLI、Cody、MiMo Code、CodeBuddy、TRAE-CN
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AgentEditorProvider {

    /**
     * 提供器名称标识，用于 SPI 注册与查找
     */
    public static final String NAME = "datasearch";

    /**
     * 用户主目录 Path，作为各编辑器配置目录的根前缀，缺省回退到当前目录
     */
    private static final Path USER_HOME = Paths.get(System.getProperty("user.home", "."));

    /**
     * CodeBuddy 项目配置目录名
     */
    private static final String CODEBUDDY_DIR = ".codebuddy";

    /**
     * CodeBuddy Skills 目录名（在 .codebuddy 下）
     */
    private static final String CODEBUDDY_SKILLS_DIR = "skills";

    /**
     * CodeBuddy Skills 定义文件名
     */
    private static final String SKILL_MD_FILE = "SKILL.md";

    /**
     * TRAE-CN 配置根目录
     */
    private static final Path TRAE_CN_DIR = USER_HOME.resolve(".trae-cn");

    /**
     * TRAE-CN 插件目录
     */
    private static final Path TRAE_CN_PLUGINS_DIR = TRAE_CN_DIR.resolve("plugins");

    /**
     * TRAE-CN Skills 目录
     */
    private static final Path TRAE_CN_SKILLS_DIR = TRAE_CN_DIR.resolve("skills");

    /**
     * Skills 目录前缀，datasearch 自定义技能目录以此前缀命名（如 datasearch_video）
     */
    private static final String PREFIX = "";

    /**
     * 默认视频数据源标识，用于视频搜索时缺省 source 参数（如豆瓣）
     */
    private static final String DEFAULT_VIDEO_SOURCE = "douban";

    /**
     * 默认音乐数据源标识，用于音乐搜索时缺省 source 参数（如 demo）
     */
    private static final String DEFAULT_MUSIC_SOURCE = "demo";

    /**
     * 全量搜索时使用的视频数据源标识
     */
    private static final String SEARCH_ALL_VIDEO_SOURCE = "bilibili";

    /**
     * STDIO 模式下默认可执行 JAR 路径
     */
    private static final String DEFAULT_JAR_PATH = "datasearch-mcp.jar";

    /**
     * SSE 模式下默认 MCP Server URL
     */
    private static final String DEFAULT_SERVER_URL = "http://localhost:8080/mcp";

    /**
     * 默认 datasearch 端口号（字符串形式，用于环境变量）
     */
    private static final String DEFAULT_DATASEARCH_PORT = "8080";

    /**
     * 默认页码，从 1 开始
     */
    private static final int DEFAULT_PAGE = 1;

    /**
     * 默认每页大小
     */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 全量搜索时每个源返回的结果数上限
     */
    private static final int SEARCH_ALL_LIMIT = 5;

    // ==================== 编辑器定义 ====================

    /**
     * AI 编辑器配置定义，封装编辑器名称、配置目录与 MCP 配置文件名，
     * 用于定位各编辑器在用户主目录下的 MCP/Skills 配置文件路径。
     */
    public static class AgentEditor {

        /**
         * 编辑器显示名称，用于日志与用户提示
         */
        private final String name;

        /**
         * 相对于用户主目录的配置目录路径，例如 .cursor、.claude
         */
        private final String configDir;

        /**
         * MCP 配置文件名，例如 mcp.json、settings.json
         */
        private final String mcpConfigFile;

        /**
         * 是否基于工作区目录（如 CodeBuddy），而非 USER_HOME。
         * true 时配置路径为 {workspace}/{configDir}/{mcpConfigFile}
         */
        private final boolean workspaceBased;

        /**
         * 环境变量名，用于覆盖默认配置目录路径。
         * 例如 CODEX_HOME、CLAUDE_CONFIG_DIR 等
         */
        private final String envVar;

        /**
         * Cursor 编辑器配置，配置目录为 .cursor，MCP 配置文件为 mcp.json
         */
        public static final AgentEditor CURSOR = new AgentEditor("Cursor", ".cursor", "mcp.json", false, "CURSOR_CONFIG_DIR");

        /**
         * Claude Code 编辑器配置，配置目录为 .claude，MCP 配置文件为 settings.json
         */
        public static final AgentEditor CLAUDE = new AgentEditor("Claude Code", ".claude", "settings.json", false, "CLAUDE_CONFIG_DIR");

        /**
         * Codex 编辑器配置，配置目录为 .codex，MCP 配置文件为 config.json
         */
        public static final AgentEditor CODEX = new AgentEditor("Codex", ".codex", "config.json", false, "CODEX_HOME");

        /**
         * Windsurf 编辑器配置，配置目录为 .codeium/windsurf，MCP 配置文件为 mcp_config.json
         */
        public static final AgentEditor WINDSURF = new AgentEditor("Windsurf", ".codeium/windsurf", "mcp_config.json", false, null);

        /**
         * Cline 编辑器配置，配置目录为 .cline，MCP 配置文件为 cline_mcp_settings.json
         */
        public static final AgentEditor CLINE = new AgentEditor("Cline", ".cline", "cline_mcp_settings.json", false, "CLINE_DATA_DIR");

        /**
         * Roo Code 编辑器配置，配置目录为 .roo，MCP 配置文件为 mcp.json
         */
        public static final AgentEditor ROO_CODE = new AgentEditor("Roo Code", ".roo", "mcp.json", false, null);

        /**
         * Trae 编辑器配置（国际版），配置目录为 .trae，MCP 配置文件为 mcp.json
         */
        public static final AgentEditor TRAE = new AgentEditor("Trae", ".trae", "mcp.json", false, null);

        /**
         * Augment Code 编辑器配置，配置目录为 .augment，MCP 配置文件为 mcp.json
         */
        public static final AgentEditor AUGMENT = new AgentEditor("Augment Code", ".augment", "mcp.json", false, null);

        /**
         * Continue 编辑器配置，配置目录为 .continue，MCP 配置文件为 config.json
         */
        public static final AgentEditor CONTINUE = new AgentEditor("Continue", ".continue", "config.json", false, null);

        /**
         * Gemini CLI 编辑器配置，配置目录为 .gemini，MCP 配置文件为 settings.json
         */
        public static final AgentEditor GEMINI_CLI = new AgentEditor("Gemini CLI", ".gemini", "settings.json", false, "GEMINI_CLI_HOME");

        /**
         * Cody（Sourcegraph）编辑辑器配置，配置目录为 .cody，MCP 配置文件为 mcp.json
         */
        public static final AgentEditor CODY = new AgentEditor("Cody", ".cody", "mcp.json", false, null);

        /**
         * CodeBuddy 编辑器配置，配置目录为 .codebuddy，MCP 配置文件为 mcp.json。
         * workspaceBased=true，从当前目录向上搜索 .codebuddy/。
         */
        public static final AgentEditor CODEBUDDY = new AgentEditor("CodeBuddy", CODEBUDDY_DIR, "mcp.json", true, null);

        /**
         * TRAE-CN 编辑器配置，配置目录为 .trae-cn，MCP 配置在 plugins 目录下。
         * install/uninstall 为 no-op，仅用于插件发现和 Skills 扫描。
         */
        public static final AgentEditor TRAE_CN = new AgentEditor("TRAE-CN", ".trae-cn", "plugins", false, null);

        /**
         * MiMo Code 编辑器配置，配置目录为 .mimocode，MCP 配置文件为 mimocode.json。
         * 支持 MIMOCODE_HOME 环境变量覆盖默认配置目录。
         */
        public static final AgentEditor MIMO = new AgentEditor("MiMo Code", ".mimocode", "mimocode.json", false, "MIMOCODE_HOME");

        /**
         * 全部内置编辑器列表，按注册顺序排列，用于批量安装/卸载时遍历
         */
        public static final List<AgentEditor> ALL = List.of(
                CURSOR, CLAUDE, CODEX, WINDSURF, CLINE, ROO_CODE,
                TRAE, AUGMENT, CONTINUE, GEMINI_CLI, CODY, MIMO,
                CODEBUDDY, TRAE_CN);

        /**
         * 根据编辑器名称、配置目录、配置文件名与工作区标记构建实例。
         *
         * @param name           编辑器显示名称
         * @param configDir      相对目录的配置目录路径
         * @param mcpConfigFile  MCP 配置文件名
         * @param workspaceBased 是否基于工作区目录（true: 从当前目录向上搜索配置目录）
         */
        public AgentEditor(String name, String configDir, String mcpConfigFile, boolean workspaceBased) {
            this(name, configDir, mcpConfigFile, workspaceBased, null);
        }

        /**
         * 根据编辑器名称、配置目录、配置文件名、工作区标记与环境变量构建实例。
         *
         * @param name           编辑器显示名称
         * @param configDir      相对目录的配置目录路径
         * @param mcpConfigFile  MCP 配置文件名
         * @param workspaceBased 是否基于工作区目录（true: 从当前目录向上搜索配置目录）
         * @param envVar         环境变量名，用于覆盖默认配置目录路径，可为 null
         */
        public AgentEditor(String name, String configDir, String mcpConfigFile, boolean workspaceBased, String envVar) {
            this.name = name;
            this.configDir = configDir;
            this.mcpConfigFile = mcpConfigFile;
            this.workspaceBased = workspaceBased;
            this.envVar = envVar;
        }

        /**
         * 获取编辑器显示名称。
         *
         * @return 编辑器名称字符串
         */
        public String getName() {
            return name;
        }

        /**
         * 获取相对用户主目录的配置目录路径。
         *
         * @return 配置目录路径字符串
         */
        public String getConfigDir() {
            return configDir;
        }

        /**
         * 获取环境变量名。
         *
         * @return 环境变量名，可为 null
         */
        public String getEnvVar() {
            return envVar;
        }

        /**
         * 获取实际的配置目录路径。
         * 优先使用环境变量（如果配置了且非空），否则使用默认的 configDir。
         *
         * @return 配置目录 Path
         */
        public Path getConfigDirPath() {
            if (envVar != null) {
                String envValue = System.getenv(envVar);
                if (envValue != null && !envValue.isBlank()) {
                    return Paths.get(envValue);
                }
            }
            return USER_HOME.resolve(configDir);
        }

        /**
         * 获取 MCP 配置文件名。
         *
         * @return MCP 配置文件名字符串
         */
        public String getMcpConfigFile() {
            return mcpConfigFile;
        }

        /**
         * 是否基于工作区目录解析配置，参见 {@link #discoverWorkspaces()}。
         *
         * @return true 表示从工作区而非 USER_HOME 查找配置
         */
        public boolean isWorkspaceBased() {
            return workspaceBased;
        }

        /**
         * 获取 MCP 配置文件的完整相对路径（configDir/mcpConfigFile）。
         *
         * @return MCP 配置文件相对路径字符串
         */
        public String getMcpConfigPath() {
            return configDir + "/" + mcpConfigFile;
        }

        /**
         * 获取 MCP 配置文件的实际 Path。
         * 优先使用环境变量（如果配置了），否则使用 USER_HOME/configDir/mcpConfigFile。
         *
         * @return MCP 配置文件 Path
         */
        public Path getMcpConfigFilePath() {
            return getConfigDirPath().resolve(mcpConfigFile);
        }
    }

    /**
     * MCP Server 配置模型（从 JSON 反序列化，用于 TRAE-CN 等插件发现场景）
     */
    protected static class McpServerConfig {
        /** 名称 */
        private String name;
        /** 命令 */
        private String command;
        /** 参数 */
        private List<String> args;
        /** env */
        private Map<String, String> env;
        /** URL */
        private String url;
        /** 类型 */
        private String type;

        /** 获取Name */
        public String getName() { return name; }
        /**
         * 设置Name
         * @param name name
         */
        public void setName(String name) { this.name = name; }
        /** 获取Command */
        public String getCommand() { return command; }
        /**
         * 设置Command
         * @param command command
         */
        public void setCommand(String command) { this.command = command; }
        /** 获取Args */
        public List<String> getArgs() { return args; }
        /**
         * 设置Args
         * @param args args
         */
        public void setArgs(List<String> args) { this.args = args; }
        public Map<String, String> getEnv() { return env; }
        /**
         * 设置Env
         * @param env env
         */
        public void setEnv(Map<String, String> env) { this.env = env; }
        /** 获取Url */
        public String getUrl() { return url; }
        /**
         * 设置Url
         * @param url url
         */
        public void setUrl(String url) { this.url = url; }
        /** 获取Type */
        public String getType() { return type; }
        /**
         * 设置Type
         * @param type type
         */
        public void setType(String type) { this.type = type; }

        @Override
        /** ToString */
        public String toString() {
            return name + " [" + (command != null ? command : url) + "]";
        }
    }

    /**
     * MCP 传输模式枚举：STDIO 表示标准输入输出模式，SSE 表示 Server-Sent Events 模式
     */
    public enum McpMode {
        /**
         * 标准输入输出模式，MCP Server 通过子进程 stdin/stdout 通信
         */
        STDIO,

        /**
         * Server-Sent Events 模式，MCP Server 通过 HTTP SSE 推送消息
         */
        SSE
    }

    // ==================== 提供器注册 ====================

    /**
     * 视频搜索处理器，由外部注入实现，用于将搜索结果回调给调用方；
     * 为 null 时回退到 SPI 自动绑定的 ResourceProvider
     */
    private VideoSearchHandler videoSearchHandler;

    /**
     * 音乐搜索提供器注册表，按源名称（如 demo、tx、bd）索引，
     * 支持并发写入，用于 MCP/Skills 调用时按需查找对应音乐源
     */
    private final Map<String, MusicSourceProvider> musicProviders = new ConcurrentHashMap<>();

    /**
     * 自定义编辑器列表，存放用户通过 addCustomEditor 追加的非内置编辑器配置，
     * 用于扩展安装/卸载的目标范围
     */
    private final List<AgentEditor> customEditors = new ArrayList<>();

    /**
     * 视频搜索回调接口，由调用方实现以接收搜索结果与下载链接。
     *
     * <p>当外部需要自定义视频搜索逻辑时，实现此接口并通过
     * {@link #videoSearchHandler(VideoSearchHandler)} 注入。</p>
     */
    public interface VideoSearchHandler {

        /**
         * 根据关键词执行视频搜索，返回分页结果。
         *
         * @param keyword  搜索关键词
         * @param source   数据源标识
         * @param page     页码（从 1 开始）
         * @param pageSize 每页大小
         * @return 搜索结果 Map，至少包含 total 与 items 字段
         */
        Map<String, Object> search(String keyword, String source, int page, int pageSize);

        /**
         * 根据关键词获取视频下载链接列表。
         *
         * @param keyword 视频标题或关键词
         * @return 下载链接信息列表，每项为 Map 结构
         */
        List<Map<String, Object>> getDownloadUrls(String keyword);
    }

    /**
     * 注入视频搜索处理器，用于自定义视频搜索回调。
     *
     * @param handler 视频搜索处理器实例
     * @return 当前 Provider 实例，支持链式调用
     */
    public AgentEditorProvider videoSearchHandler(VideoSearchHandler handler) {
        this.videoSearchHandler = handler;
        return this;
    }

    /**
     * 注册音乐搜索提供器到本地注册表。
     *
     * @param source   音乐源标识（如 demo、tx、bd）
     * @param provider 音乐源提供器实例
     * @return 当前 Provider 实例，支持链式调用
     */
    public AgentEditorProvider registerMusicProvider(String source, MusicSourceProvider provider) {
        musicProviders.put(source, provider);
        return this;
    }

    /**
     * 从 SPI 自动绑定视频/音乐 Provider，保证 MCP/Skills 可返回真实数据。
     *
     * <p>绑定顺序：先绑定音乐源，再绑定视频源。绑定过程中对每个 Provider 做容错处理，
     * 单个 Provider 加载失败不会影响其他 Provider。</p>
     *
     * @return 当前 Provider 实例，支持链式调用
     */
    public AgentEditorProvider autoBind() {
        bindMusicFromSpi();
        bindVideoFromSpi();
        return this;
    }

    /**
     * 从 SPI 加载已知的音乐源 Provider（demo、tx、bd）并注册到本地。
     *
     * <p>先按已知名称逐一加载，再遍历 SPI 全量列表补充未在已知列表中的实现。</p>
     *
     * @return 当前 Provider 实例，支持链式调用
     */
    public AgentEditorProvider bindMusicFromSpi() {
        String[] known = {"demo", "tx", "bd"};
        for (String name : known) {
            try {
                MusicSourceProvider provider = ServiceProvider.of(MusicSourceProvider.class).getNewExtension(name);
                if (provider == null) {
                    provider = ServiceProvider.of(MusicSourceProvider.class).getExtension(name);
                }
                if (provider != null) {
                    musicProviders.put(name, provider);
                }
            } catch (Throwable t) {
                log.warn("加载音乐源 {} 失败: {}", name, t.getMessage());
            }
        }
        try {
            Map<String, MusicSourceProvider> list = ServiceProvider.of(MusicSourceProvider.class).list();
            if (list != null) {
                for (Map.Entry<String, MusicSourceProvider> entry : list.entrySet()) {
                    String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                    musicProviders.putIfAbsent(key, entry.getValue());
                }
            }
        } catch (Throwable t) {
            log.warn("列举音乐源失败: {}", t.getMessage());
        }
        return this;
    }

    /**
     * 从 SPI 自动绑定视频搜索处理器；当外部未通过 {@link #videoSearchHandler(VideoSearchHandler)}
     * 注入自定义实现时，创建基于 {@link ResourceProvider} 的默认 VideoSearchHandler。
     *
     * <p>默认实现通过 SPI 加载所有 {@link ResourceProvider}：
     * <ul>
     *   <li>search 方法按 source 加载对应 Provider 并执行视频搜索，未指定时使用 {@value #DEFAULT_VIDEO_SOURCE}</li>
     *   <li>getDownloadUrls 方法遍历所有 Provider，对实现 {@link DownloadLinkProvider} 的 Provider 调用下载链接查询</li>
     * </ul>
     * </p>
     *
     * @return 当前 Provider 实例，支持链式调用
     */
    public AgentEditorProvider bindVideoFromSpi() {
        if (videoSearchHandler != null) {
            return this;
        }
        videoSearchHandler = new VideoSearchHandler() {
            @Override
            public Map<String, Object> search(String keyword, String source, int page, int pageSize) {
                String type = source == null || source.isBlank() ? DEFAULT_VIDEO_SOURCE : source;
                ResourceProvider provider = ServiceProvider.of(ResourceProvider.class).getNewExtension(type);
                if (provider == null) {
                    provider = ServiceProvider.of(ResourceProvider.class).getExtension(type);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", type);
                result.put("keyword", keyword);
                if (provider == null) {
                    result.put("total", 0);
                    result.put("items", List.of());
                    result.put("error", "未找到 ResourceProvider: " + type);
                    return result;
                }
                VideoSearch search = new VideoSearch();
                search.setKeyword(keyword);
                search.setPage(page);
                search.setPageSize(pageSize);
                ReturnPageResult<VideoInfoResult> pageResult = provider.searchResource(search);
                if (pageResult == null || !pageResult.isSuccess() || pageResult.getData() == null) {
                    result.put("total", 0);
                    result.put("items", List.of());
                    result.put("error", pageResult == null ? "null" : pageResult.getMessage());
                    return result;
                }
                List<Map<String, Object>> items = new ArrayList<>();
                List<VideoInfoResult> data = pageResult.getData().getData();
                if (data != null) {
                    for (VideoInfoResult video : data) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("name", video.getVideoName());
                        item.put("score", video.getVideoScore());
                        item.put("year", video.getVideoYear());
                        items.add(item);
                    }
                }
                result.put("total", pageResult.getData().getTotal());
                result.put("items", items);
                return result;
            }

            @Override
            public List<Map<String, Object>> getDownloadUrls(String keyword) {
                List<Map<String, Object>> urls = new ArrayList<>();
                Map<String, ResourceProvider> providers = ServiceProvider.of(ResourceProvider.class).list();
                if (providers == null) {
                    return urls;
                }
                for (Map.Entry<String, ResourceProvider> entry : providers.entrySet()) {
                    ResourceProvider provider = entry.getValue();
                    if (!(provider instanceof DownloadLinkProvider downloadLinkProvider)) {
                        continue;
                    }
                    try {
                        var result = downloadLinkProvider.searchDownloadUrls(keyword);
                        if (result != null && result.getData() != null) {
                            for (String url : result.getData()) {
                                Map<String, Object> item = new LinkedHashMap<>();
                                item.put("source", entry.getKey().toLowerCase(Locale.ROOT));
                                item.put("url", url);
                                urls.add(item);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                return urls;
            }
        };
        return this;
    }

    // ==================== install/uninstall ====================

    /**
     * 根据客户端标识查找对应的编辑器配置，查找时忽略大小写。
     *
     * <p>查找顺序：先在内置编辑器列表中按名称或配置目录匹配，
     * 再在自定义编辑器列表中按名称匹配。</p>
     *
     * @param clientId 客户端标识，可为编辑器名称或配置目录
     * @return 匹配到的 AgentEditor 实例；未找到返回 null
     */
    protected AgentEditor findEditor(String clientId) {
        for (AgentEditor editor : supportedEditors()) {
            if (editor.getName().equalsIgnoreCase(clientId)
                    || editor.getConfigDir().equalsIgnoreCase(clientId)) {
                return editor;
            }
        }
        // 尝试匹配自定义编辑器
        for (AgentEditor custom : customEditors) {
            if (custom.getName().equalsIgnoreCase(clientId)) {
                return custom;
            }
        }
        return null;
    }

    /**
     * 获取 datasearch 提供的全部技能定义列表，包含视频搜索、视频下载、音乐搜索、
     * 歌单详情、歌曲详情、全量搜索 6 个技能。
     *
     * @return 技能定义不可变列表
     */
    protected List<SkillDefinition> listSkills() {
        return List.of(
                videoSearchSkill(),
                videoDownloadSkill(),
                musicSearchSkill(),
                musicPlaylistSkill(),
                musicTrackSkill(),
                searchAllSkill()
        );
    }

    // ==================== Skills 注册 ====================

    /**
     * 将 datasearch 全部技能注册到指定的技能管理器，注册完成后记录技能总数。
     *
     * @param skillManager 技能管理器实例，用于技能注册与统一管理
     */
    public void registerSkills(SkillManager skillManager) {
        skillManager.register(videoSearchSkill());
        skillManager.register(videoDownloadSkill());
        skillManager.register(musicSearchSkill());
        skillManager.register(musicPlaylistSkill());
        skillManager.register(musicTrackSkill());
        skillManager.register(searchAllSkill());
        log.info("datasearch 技能注册完成，共 {} 个技能", skillManager.getAll().size());
    }

    // ==================== 配置读取 ====================

    /**
     * 获取所有受支持的 AI 编辑器列表，包含内置编辑器与通过 addCustomEditor 追加的自定义编辑器。
     *
     * @return 编辑器列表（新建副本，对返回值的修改不影响内部状态）
     */
    public List<AgentEditor> supportedEditors() {
        List<AgentEditor> all = new ArrayList<>(AgentEditor.ALL);
        all.addAll(customEditors);
        return all;
    }

    /**
     * 添加自定义 AI 编辑器配置，追加到自定义编辑器列表，扩展后续安装/卸载的目标范围。
     *
     * @param name      编辑器显示名称
     * @param configDir 相对用户主目录的配置目录路径
     * @param mcpFile   MCP 配置文件名
     * @return 新创建并加入列表的自定义编辑器实例
     */
    public AgentEditor addCustomEditor(String name, String configDir, String mcpFile) {
        return addCustomEditor(name, configDir, mcpFile, false);
    }

    /**
     * 添加自定义 AI 编辑器配置，支持 workspaceBased 模式。
     *
     * @param name           编辑器显示名称
     * @param configDir      配置目录路径
     * @param mcpFile        MCP 配置文件名
     * @param workspaceBased 是否基于工作区目录
     * @return 新创建并加入列表的自定义编辑器实例
     */
    public AgentEditor addCustomEditor(String name, String configDir, String mcpFile, boolean workspaceBased) {
        AgentEditor custom = new AgentEditor(name, configDir, mcpFile, workspaceBased);
        customEditors.add(custom);
        return custom;
    }

    /**
     * 读取指定编辑器的 MCP 配置文件，返回 mcpServers 等配置项的 Map 结构。
     *
     * <p>配置文件不存在或读取/解析失败时返回空 Map，并记录警告日志。</p>
     *
     * @param editor 编辑器配置
     * @return 配置内容 Map；文件不存在或解析失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMcpConfig(AgentEditor editor) {
        Path file = editor.getMcpConfigFilePath();
        if (!Files.exists(file)) {
            return Collections.emptyMap();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return (Map<String, Object>) new JsonParser(json).parse();
        } catch (Exception e) {
            log.warn("读取编辑器 MCP 配置失败: {} - {}", editor.getName(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 从指定文件路径读取 MCP 配置 JSON 并解析为 Map。
     *
     * @param filePath MCP 配置文件路径
     * @return 配置内容 Map；文件不存在或解析失败时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> readMcpConfig(Path filePath) {
        if (!Files.exists(filePath)) {
            return Collections.emptyMap();
        }
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            return (Map<String, Object>) new JsonParser(json).parse();
        } catch (Exception e) {
            log.warn("读取 MCP 配置失败: {} - {}", filePath, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * 读取所有受支持编辑器的 MCP 配置，返回编辑器到配置 Map 的有序映射。
     *
     * <p>仅包含配置非空的编辑器，按 {@link #supportedEditors()} 顺序返回。
     * 对于工作区级别的编辑器（如 CodeBuddy），会遍历发现的所有工作区。</p>
     *
     * @return 编辑器到 MCP 配置的 LinkedHashMap，保留插入顺序
     */
    public Map<AgentEditor, Map<String, Object>> readAllMcpConfigs() {
        Map<AgentEditor, Map<String, Object>> result = new LinkedHashMap<>();
        for (AgentEditor editor : supportedEditors()) {
            Map<String, Object> config = readMcpConfig(editor);
            if (!config.isEmpty()) {
                result.put(editor, config);
            }
        }
        return result;
    }

    // ==================== 工作区发现 (CodeBuddy) ====================

    /**
     * 发现所有包含 {@code .codebuddy/} 目录的工作区路径。
     *
     * <p>从当前目录向上搜索，最多向上搜索 64 级；支持通过逗号分隔的路径字符串指定多个起始目录。</p>
     *
     * @param workspacePaths 逗号分隔的额外搜索起始路径，可为 null
     * @return 已发现的工作区 Path 列表（去重）
     */
    public List<Path> discoverCodebuddyWorkspaces(String workspacePaths) {
        List<Path> workspaces = new ArrayList<>();
        // 1. 从当前目录向上搜索
        Path current = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 64 && current != null; i++) {
            Path codebuddyDir = current.resolve(CODEBUDDY_DIR);
            if (Files.isDirectory(codebuddyDir)) {
                workspaces.add(current);
            }
            current = current.getParent();
        }
        // 2. 额外指定的路径
        if (StringUtils.isNotEmpty(workspacePaths)) {
            for (String p : workspacePaths.split(",")) {
                Path ws = Paths.get(p.trim()).toAbsolutePath();
                Path cbDir = ws.resolve(CODEBUDDY_DIR);
                if (Files.isDirectory(cbDir) && !workspaces.contains(ws)) {
                    workspaces.add(ws);
                }
            }
        }
        return workspaces;
    }

    /**
     * 获取 CodeBuddy 各工作区下的 MCP 配置文件路径列表（{workspace}/.codebuddy/mcp.json）。
     *
     * @param workspacePaths 逗号分隔的工作区路径，可为 null
     * @return MCP 配置文件 Path 列表
     */
    public List<Path> getCodebuddyConfigPaths(String workspacePaths) {
        List<Path> paths = new ArrayList<>();
        for (Path ws : discoverCodebuddyWorkspaces(workspacePaths)) {
            Path configFile = ws.resolve(CODEBUDDY_DIR).resolve("mcp.json");
            paths.add(configFile);
        }
        return paths;
    }

    /**
     * 扫描 CodeBuddy 工作区的 Skills 目录（{workspace}/.codebuddy/skills/），
     * 通过解析 SKILL.md 生成 SkillDefinition。
     *
     * @param workspacePaths 逗号分隔的工作区路径，可为 null
     * @return 发现的 Skills 定义列表
     */
    public List<SkillDefinition> scanCodebuddySkills(String workspacePaths) {
        List<SkillDefinition> skills = new ArrayList<>();
        for (Path ws : discoverCodebuddyWorkspaces(workspacePaths)) {
            Path skillsDir = ws.resolve(CODEBUDDY_DIR).resolve(CODEBUDDY_SKILLS_DIR);
            if (!Files.isDirectory(skillsDir)) {
                continue;
            }
            try (var stream = Files.list(skillsDir)) {
                stream.filter(Files::isDirectory).forEach(skillDir -> {
                    Path skillMd = skillDir.resolve(SKILL_MD_FILE);
                    if (Files.exists(skillMd)) {
                        SkillDefinition def = parseSkillMd(skillMd, skillDir.getFileName().toString());
                        if (def != null) {
                            skills.add(def);
                        }
                    }
                });
            } catch (IOException e) {
                log.warn("扫描 CodeBuddy Skills 失败: {}", e.getMessage());
            }
        }
        return skills;
    }

    // ==================== TRAE-CN 扫描 ====================

    /**
     * 扫描 TRAE-CN 的 {@code .trae-cn/plugins/} 目录，发现已安装插件的 MCP Server 配置。
     *
     * <p>插件目录结构：{@code {registry}/{plugin}/{version}/.mcp.json}</p>
     *
     * @return 已发现的 MCP Server 配置列表
     */
    public List<McpServerConfig> scanTraeCnMcpServers() {
        List<McpServerConfig> servers = new ArrayList<>();
        if (!Files.isDirectory(TRAE_CN_PLUGINS_DIR)) {
            return servers;
        }
        try (var registries = Files.list(TRAE_CN_PLUGINS_DIR)) {
            registries.filter(Files::isDirectory).forEach(registry -> {
                try (var plugins = Files.list(registry)) {
                    plugins.filter(Files::isDirectory).forEach(plugin -> {
                        try (var versions = Files.list(plugin)) {
                            versions.filter(Files::isDirectory).forEach(version -> {
                                Path mcpJson = version.resolve(".mcp.json");
                                if (Files.exists(mcpJson)) {
                                    List<McpServerConfig> configs = parseMcpServerConfigs(mcpJson);
                                    servers.addAll(configs);
                                }
                            });
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            log.warn("扫描 TRAE-CN MCP 配置失败: {}", e.getMessage());
        }
        return servers;
    }

    /**
     * 扫描 TRAE-CN 的 {@code .trae-cn/skills/} 目录，解析 SKILL.md 生成 SkillDefinition。
     *
     * @return 已发现的 Skills 定义列表
     */
    public List<SkillDefinition> scanTraeCnSkills() {
        List<SkillDefinition> skills = new ArrayList<>();
        if (!Files.isDirectory(TRAE_CN_SKILLS_DIR)) {
            return skills;
        }
        try (var stream = Files.list(TRAE_CN_SKILLS_DIR)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> {
                Path skillMd = skillDir.resolve(SKILL_MD_FILE);
                if (Files.exists(skillMd)) {
                    SkillDefinition def = parseSkillMd(skillMd, skillDir.getFileName().toString());
                    if (def != null) {
                        skills.add(def);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("扫描 TRAE-CN Skills 失败: {}", e.getMessage());
        }
        return skills;
    }

    /**
     * 解析 SKILL.md 文件为 SkillDefinition。
     *
     * @param skillMdFile SKILL.md 文件路径
     * @param skillName   技能名称（目录名）
     * @return SkillDefinition 实例；解析失败返回 null
     */
    private SkillDefinition parseSkillMd(Path skillMdFile, String skillName) {
        try {
            String content = Files.readString(skillMdFile, StandardCharsets.UTF_8);
            String description = "";
            for (String line : content.lines().toList()) {
                if (line.startsWith("##") || line.startsWith("description:")) {
                    description = line.replaceFirst("^#{1,4}\\s*", "")
                            .replaceFirst("^description:\\s*", "").trim();
                    break;
                }
            }
            return SkillDefinition.skill(skillName, description, skillMdFile.toString());
        } catch (IOException e) {
            log.warn("解析 SKILL.md 失败: {}", skillMdFile);
            return null;
        }
    }

    /**
     * 从 MCP 配置 JSON 文件中解析出 MCP Server 配置列表。
     *
     * @param mcpJsonPath mcp.json 文件路径
     * @return McpServerConfig 列表
     */
    @SuppressWarnings("unchecked")
    private List<McpServerConfig> parseMcpServerConfigs(Path mcpJsonPath) {
        try {
            String json = Files.readString(mcpJsonPath, StandardCharsets.UTF_8);
            Map<String, Object> config = (Map<String, Object>) new JsonParser(json).parse();
            Map<String, Object> servers = (Map<String, Object>) config.get("mcpServers");
            if (servers == null) {
                return List.of();
            }
            return servers.entrySet().stream().map(e -> {
                McpServerConfig sc = new McpServerConfig();
                sc.name = e.getKey();
                if (e.getValue() instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) e.getValue();
                    sc.command = (String) m.get("command");
                    sc.args = (List<String>) m.get("args");
                    if (m.get("env") instanceof Map) {
                        sc.env = (Map<String, String>) m.get("env");
                    }
                    sc.url = (String) m.get("url");
                    sc.type = (String) m.get("type");
                }
                return sc;
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("解析 MCP Server 配置失败: {} - {}", mcpJsonPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * 判断 datasearch MCP 是否已安装到指定编辑器，
     * 通过检查 mcpServers 配置中是否包含 {@link #NAME} 键确定。
     *
     * @param editor 编辑器配置
     * @return 已安装返回 true；未安装或读取配置失败返回 false
     */
    public boolean isInstalled(AgentEditor editor) {
        if (editor.isWorkspaceBased()) {
            for (Path ws : discoverCodebuddyWorkspaces(null)) {
                Path configFile = ws.resolve(CODEBUDDY_DIR).resolve("mcp.json");
                Map<String, Object> config = readMcpConfig(configFile);
                Object servers = config.get("mcpServers");
                if (servers instanceof Map && ((Map<?, ?>) servers).containsKey(NAME)) {
                    return true;
                }
            }
            return false;
        }
        Map<String, Object> config = readMcpConfig(editor);
        Object servers = config.get("mcpServers");
        return servers instanceof Map && ((Map<?, ?>) servers).containsKey(NAME);
    }

    /**
     * 列出所有受支持编辑器上 datasearch 的安装状态。
     *
     * @return 编辑器名称到安装状态（true=已安装, false=未安装）的有序映射
     */
    public Map<String, Boolean> listInstalled() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (AgentEditor editor : supportedEditors()) {
            result.put(editor.getName(), isInstalled(editor));
        }
        return result;
    }

    /**
     * 列出当前机器上已安装（配置目录存在）的 AI 编辑器。
     * <p>对于 USER_HOME 级别的编辑器，检查 {@code USER_HOME/{configDir}} 是否存在；
     * 对于工作区级别的编辑器（如 CodeBuddy），检查是否能发现至少一个工作区。</p>
     *
     * @return 配置目录实际存在的编辑器名称列表，按 supportedEditors 顺序
     */
    public List<String> listAvailableEditors() {
        List<String> result = new ArrayList<>();
        for (AgentEditor editor : supportedEditors()) {
            if (editor.isWorkspaceBased()) {
                if (!discoverCodebuddyWorkspaces(null).isEmpty()) {
                    result.add(editor.getName());
                }
            } else {
                Path dir = editor.getConfigDirPath();
                if (Files.isDirectory(dir)) {
                    result.add(editor.getName());
                }
            }
        }
        return result;
    }

    // ==================== 配置生成 ====================

    /**
     * 生成 datasearch MCP 配置 JSON 字符串，使用默认 JAR 路径与默认 Server URL。
     *
     * @param mode MCP 传输模式（STDIO 或 SSE）
     * @return MCP 配置 JSON 字符串
     */
    public String generateMcpConfig(McpMode mode) {
        return generateMcpConfig(mode, null, null);
    }

    /**
     * 生成 datasearch MCP 配置 JSON 字符串，可指定 JAR 路径（STDIO 模式）与 Server URL（SSE 模式）。
     *
     * <p>STDIO 模式生成 command/args/env 三段配置；
     * SSE 模式生成 url/transport 两段配置。</p>
     *
     * @param mode      MCP 传输模式
     * @param jarPath   STDIO 模式下的可执行 JAR 路径，为 null 时使用默认值 {@link #DEFAULT_JAR_PATH}
     * @param serverUrl SSE 模式下的 MCP Server URL，为 null 时使用默认值 {@link #DEFAULT_SERVER_URL}
     * @return MCP 配置 JSON 字符串
     */
    public String generateMcpConfig(McpMode mode, String jarPath, String serverUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"mcpServers\": {\n    \"").append(NAME).append("\": {\n");
        if (mode == McpMode.STDIO) {
            sb.append("      \"command\": \"java\",\n");
            sb.append("      \"args\": [\"-jar\", \"").append(jarPath != null ? jarPath : DEFAULT_JAR_PATH).append("\"],\n");
            sb.append("      \"env\": {\"DATASEARCH_PORT\": \"").append(DEFAULT_DATASEARCH_PORT).append("\"}\n");
        } else {
            sb.append("      \"url\": \"").append(serverUrl != null ? serverUrl : DEFAULT_SERVER_URL).append("\",\n");
            sb.append("      \"transport\": \"sse\"\n");
        }
        sb.append("    }\n  }\n}");
        return sb.toString();
    }

    /**
     * 将 datasearch MCP 配置写入指定编辑器，使用默认 JAR 路径与默认 Server URL。
     *
     * @param editor 目标编辑器配置
     * @param mode   MCP 传输模式
     * @return 安装成功返回 true；IO 异常时返回 false
     */
    public boolean installTo(AgentEditor editor, McpMode mode) {
        return installTo(editor, mode, null, null);
    }

    /**
     * 将 datasearch MCP 配置写入指定编辑器，可指定 JAR 路径与 Server URL。
     *
     * <p>若编辑器配置目录不存在则自动创建；读取已有配置并合并 datasearch 项后写回，
     * 保留其他已有的 MCP Server 配置。</p>
     *
     * @param editor    目标编辑器配置
     * @param mode      MCP 传输模式
     * @param jarPath   STDIO 模式下的可执行 JAR 路径，为 null 时使用默认值 {@link #DEFAULT_JAR_PATH}
     * @param serverUrl SSE 模式下的 MCP Server URL，为 null 时使用默认值 {@link #DEFAULT_SERVER_URL}
     * @return 安装成功返回 true；IO 异常时返回 false
     */
    @SuppressWarnings("unchecked")
    public boolean installTo(AgentEditor editor, McpMode mode, String jarPath, String serverUrl) {
        // 工作区级别的编辑器（如 CodeBuddy），安装在所有发现的工作区中
        if (editor.isWorkspaceBased()) {
            List<Path> workspaces = discoverCodebuddyWorkspaces(null);
            if (workspaces.isEmpty()) {
                log.warn("未找到 CodeBuddy 工作区，无法安装 MCP");
                return false;
            }
            boolean allOk = true;
            for (Path ws : workspaces) {
                if (!installToWorkspace(editor, ws, mode, jarPath, serverUrl)) {
                    allOk = false;
                }
            }
            return allOk;
        }
        // TRAE-CN：只读发现，不写入
        if (editor == AgentEditor.TRAE_CN) {
            return true;
        }
        return installToWorkspace(editor, USER_HOME, mode, jarPath, serverUrl);
    }

    /**
     * 在指定根目录下安装 datasearch MCP，适用于工作区级别和 USER_HOME 级别。
     *
     * @param editor    目标编辑器配置
     * @param basePath  根目录（USER_HOME 或工作区路径）
     * @param mode      MCP 传输模式
     * @param jarPath   STDIO 模式下的可执行 JAR 路径
     * @param serverUrl SSE 模式下的 MCP Server URL
     * @return 安装成功返回 true
     */
    @SuppressWarnings("unchecked")
    private boolean installToWorkspace(AgentEditor editor, Path basePath, McpMode mode, String jarPath, String serverUrl) {
        try {
            // 优先使用环境变量指定的路径，否则使用 basePath/configDir
            Path configDir;
            Path configFile;
            String envValue = editor.getEnvVar() != null ? System.getenv(editor.getEnvVar()) : null;
            if (envValue != null && !envValue.isBlank()) {
                configDir = Paths.get(envValue);
                configFile = configDir.resolve(editor.getMcpConfigFile());
            } else {
                configDir = basePath.resolve(editor.getConfigDir());
                configFile = basePath.resolve(editor.getMcpConfigPath());
            }
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            // 工作区模式下读取工作区级别的配置文件
            Map<String, Object> existing = editor.isWorkspaceBased()
                    ? new LinkedHashMap<>(readMcpConfig(configFile))
                    : new LinkedHashMap<>(readMcpConfig(editor));
            Map<String, Object> servers = new LinkedHashMap<>();
            Object existingServers = existing.get("mcpServers");
            if (existingServers instanceof Map) {
                servers.putAll((Map<String, Object>) existingServers);
            }
            Map<String, Object> datasearchConfig = new LinkedHashMap<>();
            if (mode == McpMode.STDIO) {
                datasearchConfig.put("command", "java");
                datasearchConfig.put("args", List.of("-jar", jarPath != null ? jarPath : DEFAULT_JAR_PATH));
                datasearchConfig.put("env", Map.of("DATASEARCH_PORT", DEFAULT_DATASEARCH_PORT));
            } else {
                datasearchConfig.put("url", serverUrl != null ? serverUrl : DEFAULT_SERVER_URL);
                datasearchConfig.put("transport", "sse");
            }
            servers.put(NAME, datasearchConfig);
            existing.put("mcpServers", servers);
            Files.writeString(configFile, mapToJson(existing, 0), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("已安装 datasearch MCP 到 {} : {}", editor.getName(), configFile);
            return true;
        } catch (IOException e) {
            log.error("安装 datasearch MCP 到 {} 失败: {}", editor.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 将 datasearch MCP 配置批量写入所有受支持的编辑器，返回每个编辑器的安装结果。
     *
     * @param mode      MCP 传输模式
     * @param jarPath   STDIO 模式下的可执行 JAR 路径，为 null 时使用默认值
     * @param serverUrl SSE 模式下的 MCP Server URL，为 null 时使用默认值
     * @return 编辑器到安装结果的 Map，true 表示安装成功，按 {@link #supportedEditors()} 顺序返回
     */
    public Map<AgentEditor, Boolean> installToAll(McpMode mode, String jarPath, String serverUrl) {
        Map<AgentEditor, Boolean> result = new LinkedHashMap<>();
        for (AgentEditor editor : supportedEditors()) {
            result.put(editor, installTo(editor, mode, jarPath, serverUrl));
        }
        return result;
    }

    /**
     * 从指定编辑器中移除 datasearch MCP 配置项，保留其他 MCP Server 配置。
     *
     * <p>若配置文件不存在视为已卸载成功；否则读取已有配置、移除 mcpServers 中的 datasearch 项后写回。</p>
     *
     * @param editor 目标编辑器配置
     * @return 卸载成功返回 true；IO 异常时返回 false
     */
    public boolean uninstallFrom(AgentEditor editor) {
        // 工作区级别的编辑器，从所有工作区卸载
        if (editor.isWorkspaceBased()) {
            List<Path> workspaces = discoverCodebuddyWorkspaces(null);
            if (workspaces.isEmpty()) {
                // 没有工作区，视为已卸载
                return true;
            }
            boolean allOk = true;
            for (Path ws : workspaces) {
                if (!uninstallFromWorkspace(editor, ws)) {
                    allOk = false;
                }
            }
            return allOk;
        }
        // TRAE-CN：只读发现，不操作
        if (editor == AgentEditor.TRAE_CN) {
            return true;
        }
        return uninstallFromWorkspace(editor, USER_HOME);
    }

    /**
     * 从指定根目录下卸载 datasearch MCP，适用于工作区级别和 USER_HOME 级别。
     */
    private boolean uninstallFromWorkspace(AgentEditor editor, Path basePath) {
        try {
            // 优先使用环境变量指定的路径，否则使用 basePath/configDir
            Path configFile;
            String envValue = editor.getEnvVar() != null ? System.getenv(editor.getEnvVar()) : null;
            if (envValue != null && !envValue.isBlank()) {
                configFile = Paths.get(envValue).resolve(editor.getMcpConfigFile());
            } else {
                configFile = basePath.resolve(editor.getMcpConfigPath());
            }
            if (!Files.exists(configFile)) {
                return true;
            }
            Map<String, Object> existing = editor.isWorkspaceBased()
                    ? new LinkedHashMap<>(readMcpConfig(configFile))
                    : new LinkedHashMap<>(readMcpConfig(editor));
            Object servers = existing.get("mcpServers");
            if (servers instanceof Map) {
                ((Map<String, Object>) servers).remove(NAME);
            }
            Files.writeString(configFile, mapToJson(existing, 0), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("已从 {} 卸载 datasearch MCP", editor.getName());
            return true;
        } catch (IOException e) {
            log.error("从 {} 卸载 datasearch MCP 失败: {}", editor.getName(), e.getMessage());
            return false;
        }
    }

    // ==================== MCP 工具描述 ====================

    /**
     * 获取 datasearch MCP 全部工具描述符列表，包含 6 个工具：
     * datasearch_video_search、datasearch_video_download、datasearch_music_search、
     * datasearch_music_playlist、datasearch_music_track、datasearch_search_all。
     *
     * <p>每个描述符定义工具名称、说明及 JSON Schema 参数结构，供 MCP 协议调用方发现工具。</p>
     *
     * @return 工具描述符不可变列表
     */
    public static List<McpToolDescriptor> toolDescriptors() {
        return List.of(
                new McpToolDescriptor(PREFIX + "video_search", "搜索视频资源",
                        Map.of("type", "object", "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索关键词"),
                                "source", Map.of("type", "string", "description", "视频来源"),
                                "page", Map.of("type", "integer", "description", "页码"),
                                "pageSize", Map.of("type", "integer", "description", "每页数量")),
                                "required", List.of("query"))),
                new McpToolDescriptor(PREFIX + "video_download", "获取视频下载链接",
                        Map.of("type", "object", "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "视频名称")),
                                "required", List.of("keyword"))),
                new McpToolDescriptor(PREFIX + "music_search", "搜索音乐资源",
                        Map.of("type", "object", "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "搜索关键词"),
                                "source", Map.of("type", "string", "description", "音源"),
                                "page", Map.of("type", "integer", "description", "页码")),
                                "required", List.of("keyword"))),
                new McpToolDescriptor(PREFIX + "music_playlist", "获取歌单详情",
                        Map.of("type", "object", "properties", Map.of(
                                "source", Map.of("type", "string", "description", "音源"),
                                "playlistId", Map.of("type", "string", "description", "歌单 ID")),
                                "required", List.of("source", "playlistId"))),
                new McpToolDescriptor(PREFIX + "music_track", "获取歌曲详情",
                        Map.of("type", "object", "properties", Map.of(
                                "source", Map.of("type", "string", "description", "音源"),
                                "trackId", Map.of("type", "string", "description", "歌曲 ID")),
                                "required", List.of("source", "trackId"))),
                new McpToolDescriptor(PREFIX + "search_all", "全量搜索",
                        Map.of("type", "object", "properties", Map.of(
                                "keyword", Map.of("type", "string", "description", "搜索关键词")),
                                "required", List.of("keyword")))
        );
    }

    // ==================== MCP 客户端实现 ====================

    /**
     * datasearch MCP 客户端实现，将 datasearch 视频/音乐搜索能力暴露为 MCP 工具，
     * 供 MCP 协议调用方通过 listTools 与 callTool 调用。
     *
     * <p>工具调用按 toolName 分发到外部类的 handle* 方法，
     * 支持的工具详见 {@link #toolDescriptors()}。</p>
     *
     */
    protected class DatasearchMcpClient implements McpClient {

        /**
         * 客户端初始化标志，true 表示已调用 {@link #init()} 完成初始化；
         * 使用 volatile 保证多线程可见性
         */
        private volatile boolean initialized = false;

        /**
         * 初始化客户端，标记为已初始化并记录当前已注册的音乐源。
         */
        @Override
        public void init() {
            initialized = true;
            log.info("datasearch MCP 客户端已初始化，音乐源: {}", musicProviders.keySet());
        }

        /**
         * 列出客户端可调用的全部 MCP 工具。
         *
         * @return 工具描述符列表
         */
        @Override
        public List<McpToolDescriptor> listTools() { return toolDescriptors(); }

        /**
         * 调用指定 MCP 工具，按工具名称分发到对应的处理方法。
         *
         * @param toolCall 工具调用请求，包含工具名称与参数
         * @return 工具调用结果，包含成功数据或错误信息
         */
        @Override
        public McpToolResult callTool(McpToolCall toolCall) {
            try {
                return switch (toolCall.getToolName()) {
                    case PREFIX + "video_search" -> handleVideoSearch(toolCall.getArguments());
                    case PREFIX + "video_download" -> handleVideoDownload(toolCall.getArguments());
                    case PREFIX + "music_search" -> handleMusicSearch(toolCall.getArguments());
                    case PREFIX + "music_playlist" -> handleMusicPlaylist(toolCall.getArguments());
                    case PREFIX + "music_track" -> handleMusicTrack(toolCall.getArguments());
                    case PREFIX + "search_all" -> handleSearchAll(toolCall.getArguments());
                    default -> McpToolResult.error("未知工具: " + toolCall.getToolName());
                };
            } catch (Exception e) {
                return McpToolResult.error("调用异常: " + e.getMessage());
            }
        }

        /**
         * 判断客户端是否已初始化。
         *
         * @return 已调用 {@link #init()} 完成初始化返回 true；否则返回 false
         */
        @Override
        public boolean isInitialized() { return initialized; }
    }

    // ==================== 工具处理 ====================

    /**
     * 处理视频搜索工具调用，从参数解析 query/source/page/pageSize 后委托给
     * {@link VideoSearchHandler} 执行搜索。
     *
     * @param args 工具参数 Map，支持 query、source、page、pageSize 四个键
     * @return MCP 工具调用结果，包含搜索结果或错误信息
     */
    private McpToolResult handleVideoSearch(Map<String, Object> args) {
        if (videoSearchHandler == null) {
            return McpToolResult.error("未配置视频搜索处理器");
        }
        String query = (String) args.get("query");
        String source = (String) args.getOrDefault("source", DEFAULT_VIDEO_SOURCE);
        int page = args.containsKey("page") ? ((Number) args.get("page")).intValue() : DEFAULT_PAGE;
        int pageSize = args.containsKey("pageSize") ? ((Number) args.get("pageSize")).intValue() : DEFAULT_PAGE_SIZE;
        return McpToolResult.success(videoSearchHandler.search(query, source, page, pageSize));
    }

    /**
     * 处理视频下载链接获取工具调用，根据 keyword 调用 VideoSearchHandler 获取下载链接列表。
     *
     * @param args 工具参数 Map，需包含 keyword 键
     * @return MCP 工具调用结果，包含 keyword 与 urls 字段
     */
    private McpToolResult handleVideoDownload(Map<String, Object> args) {
        if (videoSearchHandler == null) {
            return McpToolResult.error("未配置视频搜索处理器");
        }
        String keyword = (String) args.get("keyword");
        return McpToolResult.success(Map.of("keyword", keyword, "urls", videoSearchHandler.getDownloadUrls(keyword)));
    }

    /**
     * 根据音乐源标识解析对应的 MusicSourceProvider，支持大小写不敏感匹配。
     *
     * @param source 音乐源标识（如 demo、tx、bd），可为 null
     * @return 对应的音乐源提供器；未注册或 source 为 null 时返回 null
     */
    private MusicSourceProvider resolveMusicProvider(String source) {
        if (source == null) {
            return null;
        }
        MusicSourceProvider provider = musicProviders.get(source);
        if (provider != null) {
            return provider;
        }
        return musicProviders.get(source.toLowerCase(Locale.ROOT));
    }

    /**
     * 处理音乐搜索工具调用，根据 keyword/source/page/pageSize 调用对应音乐源进行搜索。
     *
     * @param args 工具参数 Map，支持 keyword、source、page、pageSize 四个键
     * @return MCP 工具调用结果，包含 source、keyword、total、tracks 字段
     */
    private McpToolResult handleMusicSearch(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        String source = (String) args.getOrDefault("source", DEFAULT_MUSIC_SOURCE);
        int page = args.containsKey("page") ? ((Number) args.get("page")).intValue() : DEFAULT_PAGE;
        int pageSize = args.containsKey("pageSize") ? ((Number) args.get("pageSize")).intValue() : DEFAULT_PAGE_SIZE;
        MusicSourceProvider provider = resolveMusicProvider(source);
        if (provider == null) {
            return McpToolResult.error("未注册音乐源: " + source + "，可用: " + musicProviders.keySet());
        }
        MusicSearchResult result = provider.search(keyword, page, pageSize);
        return McpToolResult.success(Map.of("source", source, "keyword", keyword,
                "total", result.getTotal() != null ? result.getTotal() : 0,
                "tracks", result.getTracks() != null ? result.getTracks() : List.of()));
    }

    /**
     * 处理歌单详情获取工具调用，根据 source 与 playlistId 查询指定音乐源的歌单详情。
     *
     * @param args 工具参数 Map，需包含 source 与 playlistId 键
     * @return MCP 工具调用结果，包含 source、playlistId、detail 字段
     */
    private McpToolResult handleMusicPlaylist(Map<String, Object> args) {
        String source = (String) args.get("source");
        String playlistId = (String) args.get("playlistId");
        MusicSourceProvider provider = resolveMusicProvider(source);
        if (provider == null) {
            return McpToolResult.error("未注册音乐源: " + source);
        }
        MusicPlaylistDetail detail = provider.getPlaylistDetail(playlistId);
        return McpToolResult.success(Map.of("source", source, "playlistId", playlistId, "detail", detail));
    }

    /**
     * 处理歌曲详情获取工具调用，根据 source 与 trackId 查询指定音乐源的歌曲详情。
     *
     * @param args 工具参数 Map，需包含 source 与 trackId 键
     * @return MCP 工具调用结果，包含 source、trackId、detail 字段
     */
    private McpToolResult handleMusicTrack(Map<String, Object> args) {
        String source = (String) args.get("source");
        String trackId = (String) args.get("trackId");
        MusicSourceProvider provider = resolveMusicProvider(source);
        if (provider == null) {
            return McpToolResult.error("未注册音乐源: " + source);
        }
        MusicTrackDetail detail = provider.getTrackDetail(trackId);
        return McpToolResult.success(Map.of("source", source, "trackId", trackId, "detail", detail));
    }

    /**
     * 处理全量搜索工具调用，根据 keyword 同时执行视频搜索与所有已注册音乐源的搜索，汇总结果后返回。
     *
     * <p>视频部分调用 {@value #SEARCH_ALL_VIDEO_SOURCE} 源并取前 {@value #SEARCH_ALL_LIMIT} 条；
     * 音乐部分遍历所有已注册音乐源，每个源取前 {@value #SEARCH_ALL_LIMIT} 条；
     * 单个音乐源异常不影响其他源。</p>
     *
     * @param args 工具参数 Map，需包含 keyword 键
     * @return MCP 工具调用结果，包含 keyword、video、music 字段
     */
    private McpToolResult handleSearchAll(Map<String, Object> args) {
        String keyword = (String) args.get("keyword");
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("keyword", keyword);
        if (videoSearchHandler != null) {
            results.put("video", videoSearchHandler.search(keyword, SEARCH_ALL_VIDEO_SOURCE, DEFAULT_PAGE, SEARCH_ALL_LIMIT));
        }
        Map<String, Object> musicResults = new LinkedHashMap<>();
        for (Map.Entry<String, MusicSourceProvider> entry : musicProviders.entrySet()) {
            try {
                MusicSearchResult r = entry.getValue().search(keyword, DEFAULT_PAGE, SEARCH_ALL_LIMIT);
                musicResults.put(entry.getKey(), Map.of("total", r.getTotal() != null ? r.getTotal() : 0,
                        "tracks", r.getTracks() != null ? r.getTracks() : List.of()));
            } catch (Exception e) {
                musicResults.put(entry.getKey(), Map.of("error", e.getMessage()));
            }
        }
        results.put("music", musicResults);
        return McpToolResult.success(results);
    }

    // ==================== Skills 定义 ====================

    /**
     * 构建视频搜索 SkillDefinition，封装工具名为 datasearch_video_search、
     * 必填参数为 query 的技能定义。
     *
     * @return 视频搜索技能定义
     */
    protected SkillDefinition videoSearchSkill() {
        return new SkillDefinition(PREFIX + "video_search", "搜索视频资源",
                List.of(new SkillArgumentSchema("query", "搜索关键词", "string", true, null)),
                args -> toSkillResult(handleVideoSearch(args)));
    }

    /**
     * 构建视频下载链接获取 SkillDefinition，封装工具名为 datasearch_video_download、
     * 必填参数为 keyword 的技能定义。
     *
     * @return 视频下载技能定义
     */
    protected SkillDefinition videoDownloadSkill() {
        return new SkillDefinition(PREFIX + "video_download", "获取视频下载链接",
                List.of(new SkillArgumentSchema("keyword", "视频名称", "string", true, null)),
                args -> toSkillResult(handleVideoDownload(args)));
    }

    /**
     * 构建音乐搜索 SkillDefinition，封装工具名为 datasearch_music_search、
     * 必填参数为 keyword 的技能定义。
     *
     * @return 音乐搜索技能定义
     */
    protected SkillDefinition musicSearchSkill() {
        return new SkillDefinition(PREFIX + "music_search", "搜索音乐资源",
                List.of(new SkillArgumentSchema("keyword", "搜索关键词", "string", true, null)),
                args -> toSkillResult(handleMusicSearch(args)));
    }

    /**
     * 构建歌单详情 SkillDefinition，封装工具名为 datasearch_music_playlist、
     * 必填参数为 source 与 playlistId 的技能定义。
     *
     * @return 歌单详情技能定义
     */
    protected SkillDefinition musicPlaylistSkill() {
        return new SkillDefinition(PREFIX + "music_playlist", "获取歌单详情",
                List.of(new SkillArgumentSchema("source", "音源", "string", true, null),
                        new SkillArgumentSchema("playlistId", "歌单 ID", "string", true, null)),
                args -> toSkillResult(handleMusicPlaylist(args)));
    }

    /**
     * 构建歌曲详情 SkillDefinition，封装工具名为 datasearch_music_track、
     * 必填参数为 source 与 trackId 的技能定义。
     *
     * @return 歌曲详情技能定义
     */
    protected SkillDefinition musicTrackSkill() {
        return new SkillDefinition(PREFIX + "music_track", "获取歌曲详情",
                List.of(new SkillArgumentSchema("source", "音源", "string", true, null),
                        new SkillArgumentSchema("trackId", "歌曲 ID", "string", true, null)),
                args -> toSkillResult(handleMusicTrack(args)));
    }

    /**
     * 构建全量搜索 SkillDefinition，封装工具名为 datasearch_search_all、
     * 必填参数为 keyword 的技能定义。
     *
     * @return 全量搜索技能定义
     */
    protected SkillDefinition searchAllSkill() {
        return new SkillDefinition(PREFIX + "search_all", "全量搜索",
                List.of(new SkillArgumentSchema("keyword", "搜索关键词", "string", true, null)),
                args -> toSkillResult(handleSearchAll(args)));
    }

    /**
     * 将 MCP 工具调用结果转换为 Skill 调用结果，保留成功数据或错误信息。
     *
     * @param mcpResult MCP 工具调用结果
     * @return Skill 调用结果，成功或失败与原 MCP 结果一致
     */
    protected SkillResult toSkillResult(McpToolResult mcpResult) {
        if (mcpResult.isSuccess()) {
            return SkillResult.success(mcpResult.getContent());
        }
        return SkillResult.error(mcpResult.getErrorMessage());
    }

    // ==================== JSON 工具 ====================

    /**
     * 将 Map 序列化为格式化的 JSON 字符串，使用指定缩进层级控制输出对齐。
     *
     * @param map    待序列化的 Map，键为 String
     * @param indent 当前缩进层级（每级 2 个空格）
     * @return JSON 对象字符串
     */
    private static String mapToJson(Map<String, Object> map, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        List<Map.Entry<String, Object>> entries = new ArrayList<>(map.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            sb.append("  ".repeat(indent + 1));
            sb.append("\"").append(escapeJson(entry.getKey())).append("\": ");
            sb.append(valueToJson(entry.getValue(), indent + 1));
            if (i < entries.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ".repeat(indent)).append("}");
        return sb.toString();
    }

    /**
     * 将任意 Java 对象序列化为 JSON 字符串片段，支持 String、Number、Boolean、
     * List、Map 及 null 等类型，对未知类型调用 toString 后按字符串处理。
     *
     * @param value  待序列化的对象
     * @param indent 当前缩进层级（每级 2 个空格）
     * @return JSON 字符串片段
     */
    @SuppressWarnings("unchecked")
    private static String valueToJson(Object value, int indent) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                return "[]";
            }
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append("  ".repeat(indent + 1));
                sb.append(valueToJson(list.get(i), indent + 1));
                if (i < list.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ".repeat(indent)).append("]");
            return sb.toString();
        }
        if (value instanceof Map) {
            return mapToJson((Map<String, Object>) value, indent);
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    /**
     * 转义 JSON 字符串中的特殊字符，包括反斜杠、双引号、换行、回车、制表符，
     * 防止生成 JSON 时出现语法错误。
     *
     * @param s 待转义的字符串，为 null 时返回空串
     * @return 转义后的安全 JSON 字符串
     */
    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ==================== 简易 JSON 解析器 ====================

    /**
     * 简易 JSON 解析器，递归下降解析 JSON 字符串为 Java 对象，
     * 支持 Object、Array、String、Number、Boolean、null 六种类型。
     *
     * <p>用于读取编辑器 MCP 配置文件，避免引入第三方 JSON 库依赖。</p>
     *
     */
    static class JsonParser {

        /**
         * 待解析的 JSON 字符串，构造后不可变
         */
        private final String json;

        /**
         * 当前解析位置索引，从 0 开始，解析过程中递增
         */
        private int pos;

        /**
         * 根据待解析的 JSON 字符串构建解析器实例，初始位置为 0。
         *
         * @param json 待解析的 JSON 字符串
         */
        JsonParser(String json) { this.json = json; this.pos = 0; }

        /**
         * 解析 JSON 字符串为 Java 对象，根据首字符分发到对应的解析方法。
         *
         * @return 解析得到的 Java 对象（Map、List、String、Number、Boolean 或 null）；输入为空时返回 null
         */
        Object parse() {
            skipWhitespace();
            if (pos >= json.length()) {
                return null;
            }
            return switch (json.charAt(pos)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> { pos += 4; yield null; }
                default -> parseNumber();
            };
        }

        /**
         * 解析 JSON 对象字面量为 LinkedHashMap，键为 String，值为任意 Java 对象。
         *
         * <p>解析完成后 pos 停在闭合括号 } 之后。</p>
         *
         * @return 解析得到的 Map，保留键的插入顺序
         */
        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == '}') { pos++; return map; }
            while (pos < json.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++;
                map.put(key, parse());
                skipWhitespace();
                if (pos < json.length() && json.charAt(pos) == ',') { pos++; continue; }
                break;
            }
            skipWhitespace();
            if (pos < json.length()) {
                pos++;
            }
            return map;
        }

        /**
         * 解析 JSON 数组字面量为 ArrayList，元素为任意 Java 对象。
         *
         * <p>解析完成后 pos 停在闭合中括号 ] 之后。</p>
         *
         * @return 解析得到的 List，保留元素顺序
         */
        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (pos < json.length() && json.charAt(pos) == ']') { pos++; return list; }
            while (pos < json.length()) {
                list.add(parse());
                skipWhitespace();
                if (pos < json.length() && json.charAt(pos) == ',') { pos++; continue; }
                break;
            }
            skipWhitespace();
            if (pos < json.length()) {
                pos++;
            }
            return list;
        }

        /**
         * 解析 JSON 字符串字面量，处理转义字符（\n、\r、\t 等）后返回原始字符串内容。
         *
         * <p>解析完成后 pos 停在闭合双引号之后；若遇到非法转义符则按字面字符处理。</p>
         *
         * @return 解析得到的字符串
         */
        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < json.length()) {
                char c = json.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char next = pos < json.length() ? json.charAt(pos++) : ' ';
                    sb.append(switch (next) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> next; });
                } else sb.append(c);
            }
            return sb.toString();
        }

        /**
         * 解析 JSON 数字字面量，支持负号、小数点与科学计数法。
         *
         * <p>解析完成后根据是否包含小数点或指数符号返回 Double 或 Long/Integer：
         * 整数范围在 int 表示范围内时返回 Integer，否则返回 Long。</p>
         *
         * @return 解析得到的 Number（Double、Long 或 Integer）
         */
        private Number parseNumber() {
            int start = pos;
            if (pos < json.length() && json.charAt(pos) == '-') {
                pos++;
            }
            while (pos < json.length() && (Character.isDigit(json.charAt(pos)) || ".eE+-".indexOf(json.charAt(pos)) >= 0)) {
                if ((json.charAt(pos) == '-' || json.charAt(pos) == '+') && pos > start && "eE".indexOf(json.charAt(pos - 1)) < 0) {
                    break;
                }
                pos++;
            }
            String num = json.substring(start, pos);
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            long l = Long.parseLong(num);
            return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (int) l : l;
        }

        /**
         * 解析 JSON 布尔字面量 true 或 false，解析完成后 pos 停在字面量之后。
         *
         * @return 解析得到的 Boolean（true 或 false）
         */
        private Boolean parseBoolean() {
            if (json.startsWith("true", pos)) { pos += 4; return true; }
            pos += 5; return false;
        }

        /**
         * 跳过当前位置开始的连续空白字符（空格、制表符、换行等），直到非空白字符或字符串末尾。
         */
        private void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }
    }
}
