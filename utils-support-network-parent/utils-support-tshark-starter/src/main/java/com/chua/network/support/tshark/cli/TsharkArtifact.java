package com.chua.network.support.tshark.cli;

import javax.annotation.Nonnull;

/**
 * 待下载安装的 tshark 制品描述。
 *
 * <p>把"去哪里下、下的是什么格式、装完去哪儿找可执行文件"固化为一份数据，
 * 使 {@link TsharkArtifactResolver} 只负责拼地址、{@link TsharkArtifactInstaller} 只负责落盘，
 * 两者互不感知平台细节。</p>
 *
 * @param version       Wireshark 版本号，如 {@code 4.6.9}
 * @param kind          制品格式，决定安装方式
 * @param url           完整下载地址
 * @param fileName      下载文件名，为空时取 URL 末段
 * @param sha256        期望的 SHA-256，为空表示跳过校验
 * @param executableName 安装完成后要查找的可执行文件名，如 {@code tshark.exe}
 * @author CH
 * @since 4.0.0.42
 */
public record TsharkArtifact(
        String version,
        Kind kind,
        String url,
        String fileName,
        String sha256,
        String executableName
) {

    /**
     * 制品格式。
     *
     * <p>Windows 与 macOS 官方只发布安装包（NSIS / DMG）而不发布免安装压缩包，
     * 因此这两类平台的下载兜底走"静默安装"；类 Unix 侧优先走包管理器，
     * 压缩包格式仅在配置了自定义下载地址时使用。</p>
     */
    public enum Kind {

        /**
         * PortableApps 便携版安装包（NSIS，支持 {@code /S /D=} 静默安装到指定目录）
         */
        PORTABLE_NSIS("paf"),

        /**
         * 官方安装包（NSIS，支持 {@code /S /D=} 静默安装到指定目录）
         */
        INSTALLER_NSIS("exe"),

        /**
         * ZIP 压缩包
         */
        ZIP("zip"),

        /**
         * TAR.GZ 压缩包
         */
        TAR_GZ("tar.gz"),

        /**
         * TAR.XZ 压缩包
         */
        TAR_XZ("tar.xz"),

        /**
         * macOS 磁盘映像
         */
        DMG("dmg");

        /**
         * 文件名后缀
         */
        private final String extension;

        /**
         * 构造枚举值。
         *
         * @param extension 文件名后缀
         */
        Kind(String extension) {
            this.extension = extension;
        }

        /**
         * 文件名后缀。
         *
         * @return 后缀，不含点
         */
        @Nonnull
        public String extension() {
            return extension;
        }

        /**
         * 是否为需要"执行"而非"解压"的安装包。
         *
         * @return NSIS 安装包或 DMG 返回 true
         */
        public boolean requiresExecution() {
            return this == PORTABLE_NSIS || this == INSTALLER_NSIS || this == DMG;
        }
    }
}
