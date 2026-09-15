package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * KiloCode 扩展本地提供者。
 *
 * <p>Kilo Code（kilocode.kilo-code）作为 VS Code 扩展安装，扫描
 * {@code ~/.vscode/extensions/kilocode.kilo-code-X/package.json}
 * （X 为版本号，仅目录扫描，不做通配）。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("kilo-code")
public class KiloCodeExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".vscode").resolve("extensions");
    }

    @Override
    public String name() {
        return "kilo-code";
    }
}