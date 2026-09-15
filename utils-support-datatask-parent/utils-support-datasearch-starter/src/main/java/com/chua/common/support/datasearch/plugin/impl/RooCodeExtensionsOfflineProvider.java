package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * RooCode 扩展本地提供者。
 *
 * <p>Roo Code（rooveterinaryinc.roo-cline）作为 VS Code 扩展安装，扫描
 * {@code ~/.vscode/extensions/rooveterinaryinc.roo-cline-X/package.json}
 * （X 为版本号，仅目录扫描，不做通配）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("roo-code")
public class RooCodeExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".vscode").resolve("extensions");
    }

    @Override
    public String name() {
        return "roo-code";
    }
}