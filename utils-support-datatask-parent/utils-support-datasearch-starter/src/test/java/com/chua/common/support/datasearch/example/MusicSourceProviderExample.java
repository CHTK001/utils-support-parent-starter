// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.example;

import com.chua.common.support.datasearch.music.model.MusicSourceOption;
import com.chua.common.support.datasearch.music.spi.MusicSourceProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Set;

/**
 * {@link MusicSourceProvider} SPI 冒烟示例。
 *
 * <p>只做离线校验：经 {@link ServiceProvider} 读取 SPI 注册表，逐个取 {@link MusicSourceProvider#getSource()}
 * 校验音源编码与名称，不发起任何网络请求。用于守住 SPI 注册文件与实现类的对应关系——
 * 实现类改名、删类或漏登记时，这里会直接失败。</p>
 *
 * <p>运行方式（模块目录下，exec 示例）：</p>
 * <pre>{@code
 * mvn -o test-compile exec:java@run-music-spi-test
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.45
 */
public final class MusicSourceProviderExample {

    /**
     * 腾讯音乐（QQ 音乐）音源编码
     */
    private static final String CODE_TENCENT = "tx";

    /**
     * 网易云音乐音源编码
     */
    private static final String CODE_NETEASE = "netease";

    /**
     * 腾讯音乐在 SPI 注册表中的名称（注册表按大写归一，实测为 {@code TX}）
     */
    private static final String NAME_TENCENT = "TX";

    /**
     * 网易云音乐在 SPI 注册表中的名称（实测为 {@code NETEASE}）
     */
    private static final String NAME_NETEASE = "NETEASE";

    /**
     * 注册表内应有的音源实现数
     */
    private static final int EXPECTED_SOURCES = 2;

    /**
     * 断言失败计数
     */
    private static int failures;

    private MusicSourceProviderExample() {
    }

    /**
     * 示例入口：校验音源 SPI 注册表，失败则抛出异常。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        ServiceProvider<MusicSourceProvider> provider = ServiceProvider.of(MusicSourceProvider.class);
        Set<String> names = provider.getExtensions();
        List<MusicSourceProvider> sources = provider.collect();
        System.out.println("registered names=" + names);

        for (MusicSourceProvider source : sources) {
            MusicSourceOption option = source.getSource();
            System.out.println("  " + source.getClass().getSimpleName()
                    + " code=" + option.getCode()
                    + " name=" + option.getName()
                    + " enabled=" + option.getEnabled());
            check("实现 " + source.getClass().getSimpleName() + " 的编码非空",
                    option.getCode() != null && !option.getCode().isBlank());
            check("实现 " + source.getClass().getSimpleName() + " 的名称非空",
                    option.getName() != null && !option.getName().isBlank());
        }

        check("注册名包含 " + NAME_TENCENT, names.contains(NAME_TENCENT));
        check("注册名包含 " + NAME_NETEASE, names.contains(NAME_NETEASE));
        check("音源实现数=" + EXPECTED_SOURCES, sources.size() == EXPECTED_SOURCES);

        MusicSourceProvider tencent = provider.getExtension(CODE_TENCENT);
        MusicSourceProvider netease = provider.getExtension(CODE_NETEASE);
        check("getExtension(tx) 可用", tencent != null);
        check("getExtension(netease) 可用", netease != null);
        if (tencent != null) {
            check("tx 音源编码自洽", CODE_TENCENT.equals(tencent.getSource().getCode()));
        }
        if (netease != null) {
            check("netease 音源编码自洽", CODE_NETEASE.equals(netease.getSource().getCode()));
        }

        if (failures > 0) {
            throw new IllegalStateException("音乐音源 SPI 存在 " + failures + " 处不符合预期");
        }
        System.out.println("MUSIC-SPI-FIXTURE=PASS");
    }

    /**
     * 记录一条断言结果，失败时累计次数。
     *
     * @param name 断言描述
     * @param ok   是否通过
     */
    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
    }
}
