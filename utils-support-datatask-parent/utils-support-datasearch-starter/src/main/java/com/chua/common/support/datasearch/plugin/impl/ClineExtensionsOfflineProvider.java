package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Cline 扩展本地提供者。
 *
 * <p>Cline 作为 VS Code 扩展安装，扫描
 * {@code ~/.vscode/extensions/{publisher}.{name}-{version}/package.json}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("cline")
public class ClineExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".vscode").resolve("extensions");
    }

    @Override
    public String name() {
        return "cline";
    }
}