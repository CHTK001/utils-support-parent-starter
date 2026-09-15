package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Trae（国际版）扩展本地提供者。
 *
 * <p>扫描 {@code ~/.trae/extensions/{publisher}.{name}-{version}/package.json}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("trae")
public class TraeExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".trae").resolve("extensions");
    }

    @Override
    public String name() {
        return "trae";
    }
}