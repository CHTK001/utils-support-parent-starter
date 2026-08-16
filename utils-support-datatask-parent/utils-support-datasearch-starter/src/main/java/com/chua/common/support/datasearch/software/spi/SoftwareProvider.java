package com.chua.common.support.datasearch.software.spi;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;

import java.util.List;

/**
 * 软件搜索 SPI 接口
 *
 * <p>定义软件搜索的统一入口，支持搜索、安装、卸载软件包。
 * 各实现通过 SPI 机制注册，如系统包管理器（winget/brew/apt）、
 * 软件市场（Chocolatey/npm/pip）等。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SoftwareProvider {

    /**
     * 获取软件源名称
     *
     * @return 软件源名称
     */
    String name();

    /**
     * 搜索软件
     *
     * @param keyword 搜索关键词
     * @return 软件信息列表
     */
    List<SoftwareInfo> search(String keyword);

    /**
     * 安装软件包
     *
     * @param packageId 包 ID
     * @return 是否安装成功
     */
    default boolean install(String packageId) {
        return false;
    }

    /**
     * 卸载软件包
     *
     * @param packageId 包 ID
     * @return 是否卸载成功
     */
    default boolean uninstall(String packageId) {
        return false;
    }
}