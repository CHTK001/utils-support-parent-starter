package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.datasearch.plugin.model.PluginDefinition;
import com.chua.common.support.datasearch.plugin.spi.PluginOfflineProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地插件提供者抽象基类。
 *
 * <p>子类声明插件根目录 {@link #pluginRoots()} 与来源标识 {@link #name()}，
 * 扫描 {@code <root>/<owner>/<plugin>/<version>} 结构下的插件清单。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public abstract class AbstractLocalPluginOfflineProvider implements PluginOfflineProvider {

    /**
     * 日志
    */
    protected static final Logger log = LoggerFactory.getLogger(AbstractLocalPluginOfflineProvider.class);

    /**
     * 用户主目录
    */
    protected static final Path USER_HOME = Paths.get(System.getProperty("user.home", "."));

    /**
     * 插件根目录列表。
     * @return 结果列表，无数据时为空列表
     */
    protected abstract List<Path> pluginRoots();

    /**
     * 清单文件名（如 plugin.json / package.json），子类可选覆写。
     * @return 结果字符串
     */
    protected String manifestFile() {
        return "plugin.json";
    }

    /**
     * 从清单 JSON 中读取 name / description / version（默认实现宽松解析）。
     * @param json 方法入参 json
     * @param key 键，不允许为 null
     * @return 结果字符串
     */
    protected String readManifestField(String json, String key) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            com.chua.common.support.lang.json.JsonNode node =
                    com.chua.common.support.lang.json.Json.parse(json);
            String v = node.get(key).toStringValue();
            return v == null ? "" : v.trim();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public boolean isInstalled() {
        for (Path root : pluginRoots()) {
            if (Files.isDirectory(root)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<PluginDefinition> listPlugins() {
        List<PluginDefinition> result = new ArrayList<>();
        for (Path root : pluginRoots()) {
            scanRoot(root, result);
        }
        return result;
    }

    /**
     * 扫描单个插件根目录：{@code <owner>/<plugin>/<version>} 三层结构。
     *
     * @param root   插件根目录
     * @param result 结果收集器
     */
    private void scanRoot(Path root, List<PluginDefinition> result) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var owners = Files.list(root)) {
            for (Path ownerDir : owners.filter(Files::isDirectory).toList()) {
                try (var plugins = Files.list(ownerDir)) {
                    for (Path pluginDir : plugins.filter(Files::isDirectory).toList()) {
                        Path versionDir = latestVersionDir(pluginDir);
                        if (versionDir == null) {
                            continue;
                        }
                        Path manifest = versionDir.resolve(manifestFile());
                        if (!Files.exists(manifest)) {
                            continue;
                        }
                        String json;
                        try {
                            json = Files.readString(manifest, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            continue;
                        }
                        String name = readManifestField(json, "name");
                        String description = readManifestField(json, "description");
                        String version = readManifestField(json, "version");
                        if (name.isBlank()) {
                            name = pluginDir.getFileName().toString();
                        }
                        String id = ownerDir.getFileName() + "/" + pluginDir.getFileName()
                                + (version.isBlank() ? "" : "@" + version);
                        result.add(new PluginDefinition(
                                id, name, description, ownerDir.getFileName().toString(),
                                version, manifest.toAbsolutePath().toString(),
                                name(), manifest.toAbsolutePath().toString()));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("[{}] 扫描插件目录失败: {}", name(), e.getMessage());
        }
    }

    /**
     * 取插件目录下最新版本子目录（按目录名字典序取最大）。
     *
     * @param pluginDir 插件目录
     * @return 最新版本目录；不存在返回 null
     */
    private Path latestVersionDir(Path pluginDir) {
        List<Path> versions;
        try (var stream = Files.list(pluginDir)) {
            versions = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return null;
        }
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }
}
