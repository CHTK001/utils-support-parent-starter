package com.chua.common.support.datasearch.plugin.impl;

import com.chua.common.support.spi.annotations.Spi;

import java.nio.file.Path;

/**
 * Zed 扩展本地提供者。
 *
 * <p>扫描 {@code ~/.local/share/zed/extensions} 下的扩展目录。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
@Spi("zed")
public class ZedExtensionsOfflineProvider extends AbstractExtensionsOfflineProvider {

    @Override
    protected Path extensionsDir() {
        return USER_HOME.resolve(".local").resolve("share").resolve("zed").resolve("extensions");
    }

    @Override
    public String name() {
        return "zed";
    }
}