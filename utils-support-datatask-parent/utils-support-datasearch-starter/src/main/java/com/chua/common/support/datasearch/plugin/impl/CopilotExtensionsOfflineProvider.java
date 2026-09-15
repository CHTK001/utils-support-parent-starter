package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Copilot VS Code extension offline provider.
 *
 * <p>Scans {@code ~/.vscode/extensions/GitHub.copilot-*} for the Copilot extension manifest.</p>
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