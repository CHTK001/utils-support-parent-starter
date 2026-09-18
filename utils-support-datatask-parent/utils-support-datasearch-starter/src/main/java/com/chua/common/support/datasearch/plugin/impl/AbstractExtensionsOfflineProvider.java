package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * VS Code 风格扩展目录本地提供者抽象基类。
 *
 * <p>VS Code / Cursor / Windsurf 共享扩展布局
 * {@code <extensions>/{publisher}.{name}-{version}/package.json}，目录名即扩展标识，
 * 元信息从 {@code package.json} 的 name/displayName/description/version/publisher 读取。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public abstract class AbstractExtensionsOfflineProvider implements com.chua.common.support.datasearch.plugin.spi.PluginOfflineProvider {

    /** 日志 */
    protected static final Logger log = LoggerFactory.getLogger(AbstractExtensionsOfflineProvider.class);

    /** 用户主目录 */
    protected static final Path USER_HOME = Path.of(System.getProperty("user.home", "."));

    /**
     * 扩展根目录。
     * @return 路径 对象
     */
    protected abstract Path extensionsDir();

    @Override
    public boolean isInstalled() {
        return Files.isDirectory(extensionsDir());
    }

    @Override
    public List<PluginDefinition> listPlugins() {
        List<PluginDefinition> result = new ArrayList<>();
        Path dir = extensionsDir();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            for (Path extDir : stream.filter(Files::isDirectory).toList()) {
                Path pkg = extDir.resolve("package.json");
                if (!Files.exists(pkg)) {
                    continue;
                }
                String json;
                try {
                    json = Files.readString(pkg, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                com.chua.common.support.lang.json.JsonNode node;
                try {
                    node = com.chua.common.support.lang.json.Json.parse(json);
                } catch (Exception e) {
                    continue;
                }
                String displayName = node.get("displayName").toStringValue();
                String name = node.get("name").toStringValue();
                String description = node.get("description").toStringValue();
                String version = node.get("version").toStringValue();
                String publisher = node.get("publisher").toStringValue();
                String extId = extDir.getFileName().toString();
                String display = (displayName == null || displayName.isBlank())
                        ? extId : displayName;
                result.add(new PluginDefinition(
                        extId, display, description == null ? "" : description,
                        publisher == null ? "" : publisher,
                        version == null ? "" : version,
                        extDir.toAbsolutePath().toString(), name(),
                        pkg.toAbsolutePath().toString()));
            }
        } catch (IOException e) {
            log.debug("[{}] 扫描扩展目录失败: {}", name(), e.getMessage());
        }
        return result;
    }
}