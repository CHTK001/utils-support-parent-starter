package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Cursor 扩展本地提供者。
 *
 * <p>扫描 {@code ~/.cursor/extensions/{publisher}.{name}-{version}/package.json}。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("cursor")
public class CursorExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".cursor").resolve("extensions");
    }

    @Override
    public String name() {
        return "cursor";
    }
}