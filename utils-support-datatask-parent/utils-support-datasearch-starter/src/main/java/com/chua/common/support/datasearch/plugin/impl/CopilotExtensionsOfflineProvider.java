package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Copilot 的 VS Code 扩展离线提供者。
 *
 * <p>扫描 {@code ~/.vscode/extensions/GitHub.copilot-*} 目录，查找 Copilot 扩展的清单文件。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("copilot")
public class CopilotExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".vscode").resolve("extensions");
    }

    @Override
    public String name() {
        return "copilot";
    }
}