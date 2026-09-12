package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;

import java.util.List;

/**
* 包管理器软件搜索提供器。
*
* <p>通过系统包管理器（winget/brew/apt 等）搜索、安装和卸载软件包。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("package-manager")
public class PackageManagerSoftwareProvider extends PackageManagerProvider implements SoftwareProvider {

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        return searchSoftware(keyword);
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        return installSoftware(packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        return uninstallSoftware(packageId);
    }
}