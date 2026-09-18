package com.chua.crypto.support.key;

import com.chua.crypto.support.CryptoException;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
* 服务器硬件指纹
*
* <p>采集本机稳定特征（主机名、网卡 MAC、OS 名称/架构、CPU 核数）并生成 SHA-256 指纹串，
* 作为 {@code SERVER_BOUND} 密钥策略下 KEK 的派生源，使密钥文件离开原服务器后无法解锁。
*
* <p>指纹可通过以下方式显式固定（用于容灾迁移或容器化部署）：
* <ol>
*   <li>系统属性 {@code chua.crypto.server-id}</li>
*   <li>环境变量 {@code CHUA_CRYPTO_SERVER_ID}</li>
*   <li>链式 API {@code Crypto.create().serverId("...")}</li>
* </ol>
*
* @author CH
* @since 2026-08-26
 */
public final class ServerFingerprint {

    /**
    * 指纹固定项的系统属性名
    */
    public static final String PROPERTY_SERVER_ID = "chua.crypto.server-id";

    /**
    * 指纹固定项的环境变量名
    */
    public static final String ENV_SERVER_ID = "CHUA_CRYPTO_SERVER_ID";

    /**
    * 指纹分隔符
    */
    private static final String SEPARATOR = "|";

    private final String fingerprint; // fingerprint

    /**
    * 私有构造，由工厂方法创建
    *
    * @param fingerprint SHA-256 指纹十六进制串
    */
    private ServerFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    /**
    * 采集当前服务器指纹。优先使用显式固定的 服务端id，否则自动采集硬件特征。
    *
    * @return 服务器指纹
    */
    public static ServerFingerprint capture() {
        String pinned = System.getProperty(PROPERTY_SERVER_ID);
        if (pinned == null || pinned.isBlank()) {
            pinned = System.getenv(ENV_SERVER_ID);
        }
        if (pinned != null && !pinned.isBlank()) {
            return new ServerFingerprint(sha256Hex("pinned" + SEPARATOR + pinned.trim()));
        }
        return new ServerFingerprint(sha256Hex(String.join(SEPARATOR, collectAttributes())));
    }

    /**
    * 使用指定固定值构造指纹
    *
    * @param serverId 固定标识
    * @return 服务器指纹
    */
    public static ServerFingerprint of(String serverId) {
        return new ServerFingerprint(sha256Hex("pinned" + SEPARATOR + serverId));
    }

    /**
    * 获取指纹十六进制串
    *
    * @return SHA-256 十六进制指纹
    */
    public String value() {
        return fingerprint;
    }

    /**
    * 采集本机特征列表：主机名、非虚拟网卡 MAC（排序）、OS、架构、CPU 核数
    *
    * @return 特征列表
    */
    private static List<String> collectAttributes() {
        List<String> attributes = new ArrayList<>();
        attributes.add(System.getProperty("os.name", "unknown-os"));
        attributes.add(System.getProperty("os.arch", "unknown-arch"));
        attributes.add(String.valueOf(Runtime.getRuntime().availableProcessors()));
        try {
            attributes.add(InetAddress.getLocalHost().getHostName());
        } catch (Exception ignored) {
            attributes.add("unknown-host");
        }
        for (String mac : collectMacAddresses()) {
            attributes.add(mac);
        }
        return attributes;
    }

    /**
    * 采集启用状态的非回环物理网卡 MAC 地址并排序，保证跨次启动稳定
    *
    * @return MAC 地址列表（可能为空，如纯容器环境）
    */
    private static List<String> collectMacAddresses() {
        List<String> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual() || !ni.isUp()) {
                    continue;
                }
                byte[] hardware = ni.getHardwareAddress();
                if (hardware == null || hardware.length == 0) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (byte b : hardware) {
                    sb.append(String.format("%02x", b));
                }
                macs.add(sb.toString());
            }
        } catch (Exception ignored) {
            // 容器等受限环境可能禁止枚举网卡，忽略后由其余特征兜底
        }
        Collections.sort(macs);
        return macs;
    }

    /**
    * 计算 SHA-256 并转为十六进制小写串
    *
    * @param data 原文
    * @return 十六进制摘要
    */
    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new CryptoException("服务器指纹计算失败", e);
        }
    }

    @Override
    public String toString() {
        // 防止指纹被日志意外泄露：仅输出前 8 位摘要标识
        return "ServerFingerprint{mask=" + fingerprint.substring(0, Math.min(8, fingerprint.length())) + "...}";
    }
}
