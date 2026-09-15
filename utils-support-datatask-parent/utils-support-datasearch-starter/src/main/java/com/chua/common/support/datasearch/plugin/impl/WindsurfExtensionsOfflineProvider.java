package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Windsurf 扩展本地提供者。
 *
 * <p>扫描 {@code ~/.codeium/windsurf/extensions/{publisher}.{name}-{version}/package.json}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("windsurf")
public class WindsurfExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".codeium").resolve("windsurf").resolve("extensions");
    }

    @Override
    public String name() {
        return "windsurf";
    }
}