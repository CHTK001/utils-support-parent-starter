package com.chua.ollama.support;

import com.chua.common.support.spi.annotations.Spi;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.ModelPullListener;
import io.github.ollama4j.models.ps.ModelProcessesResult;
import io.github.ollama4j.models.request.CustomModelRequest;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.response.ModelDetail;
import lombok.Getter;

import java.util.List;

/**
 * Ollama 服务管理门面。
 *
 * <p>对应 ollama4j {@link Ollama} 的模型 生命周期 管理 能力：
 * 连通 性 探测、模型 列表、版本 查询、拉取/创建/删除/卸载 模型 等。
 * 作为 独立 门面 暴露（不走 {@code ChatClient} SPI 工厂），
 * 供 运维 / 初始化 场景 直接 使用。
 *
 * <p>调用 示例：
 * <pre>{@code
 *   OllamaManager manager = new OllamaManager("http://localhost:11434");
 *   boolean up = manager.ping();
 *   List<Model> models = manager.listModels();
 *   manager.pullModel("minicpm5-2b");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ollama-manager")
public class OllamaManager {

    /**
     * 底层 ollama4j 客户端
     */
    @Getter
    private final Ollama ollama;

    /**
     * 创建 Ollama 管理 门面。
     *
     * @param host Ollama 服务 地址，可为 空（使用 默认 地址）
     */
    public OllamaManager(String host) {
        this.ollama = OllamaSupport.client(host);
    }

    /**
     * 创建 Ollama 管理 门面（默认 地址）。
     */
    public OllamaManager() {
        this(null);
    }

    /**
     * 探测 Ollama 服务 是否 可达。
     *
     * @return 可达 返回 true
     * @throws io.github.ollama4j.exceptions.OllamaException 探测 失败
     */
    public boolean ping() throws io.github.ollama4j.exceptions.OllamaException {
        return ollama.ping();
    }

    /**
     * 查询 当前 内存 中 已 加载 的 模型 进程。
     *
     * @return 模型 进程 结果
     * @throws io.github.ollama4j.exceptions.OllamaException 请求 失败
     */
    public ModelProcessesResult ps() throws io.github.ollama4j.exceptions.OllamaException {
        return ollama.ps();
    }

    /**
     * 列出 已 安装 模型。
     *
     * @return 模型 列表
     * @throws io.github.ollama4j.exceptions.OllamaException 请求 失败
     */
    public List<Model> listModels() throws io.github.ollama4j.exceptions.OllamaException {
        return ollama.listModels();
    }

    /**
     * 查询 Ollama 服务 版本。
     *
     * @return 版本 字符串
     * @throws io.github.ollama4j.exceptions.OllamaException 请求 失败
     */
    public String getVersion() throws io.github.ollama4j.exceptions.OllamaException {
        return ollama.getVersion();
    }

    /**
     * 拉取 模型。
     *
     * @param modelName 模型 名称（如 llama3:latest）
     * @throws io.github.ollama4j.exceptions.OllamaException 拉取 失败
     */
    public void pullModel(String modelName) throws io.github.ollama4j.exceptions.OllamaException {
        ollama.pullModel(modelName);
    }

    /**
     * 拉取 模型（带 进度 监听）。
     *
     * @param modelName 模型 名称
     * @param listener  进度 监听器
     * @throws io.github.ollama4j.exceptions.OllamaException 拉取 失败
     */
    public void pullModel(String modelName, ModelPullListener listener)
            throws io.github.ollama4j.exceptions.OllamaException {
        ollama.pullModel(modelName, listener);
    }

    /**
     * 查询 模型 详情。
     *
     * @param modelName 模型 名称
     * @return 模型 详情
     * @throws io.github.ollama4j.exceptions.OllamaException 请求 失败
     */
    public ModelDetail getModelDetails(String modelName) throws io.github.ollama4j.exceptions.OllamaException {
        return ollama.getModelDetails(modelName);
    }

    /**
     * 创建 自定义 模型。
     *
     * @param request 自定义 模型 规格
     * @throws io.github.ollama4j.exceptions.OllamaException 创建 失败
     */
    public void createModel(CustomModelRequest request) throws io.github.ollama4j.exceptions.OllamaException {
        ollama.createModel(request);
    }

    /**
     * 删除 模型。
     *
     * @param modelName          模型 名称
     * @param ignoreIfNotPresent 不 存在 时 是否 忽略 错误
     * @throws io.github.ollama4j.exceptions.OllamaException 删除 失败
     */
    public void deleteModel(String modelName, boolean ignoreIfNotPresent)
            throws io.github.ollama4j.exceptions.OllamaException {
        ollama.deleteModel(modelName, ignoreIfNotPresent);
    }

    /**
     * 从 内存 卸载 模型。
     *
     * @param modelName 模型 名称
     * @throws io.github.ollama4j.exceptions.OllamaException 卸载 失败
     */
    public void unloadModel(String modelName) throws io.github.ollama4j.exceptions.OllamaException {
        ollama.unloadModel(modelName);
    }
}
