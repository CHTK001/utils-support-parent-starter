package com.chua.common.support.utils;

import com.chua.common.support.host.HostAnchor;
import com.chua.common.support.host.HostFacts;
import com.chua.common.support.host.HostIdentity;
import com.chua.common.support.host.HostInfoProvider;
import com.chua.common.support.host.HostInfoRouter;
import com.chua.common.support.lang.cmd.OsFamily;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统工具类：主机不可变信息采集、机器唯一 ID 的生成与校验。
 *
 * <p>本类是 {@link HostInfoRouter} / {@link HostIdentity} 的门面，
 * 把「读硬件」与「算指纹」两件事分开：采集走 {@link HostInfoProvider} SPI（各平台一条实现），
 * 编码与判定走 {@link HostIdentity}（与平台无关）。</p>
 *
 * <h2>一级不可变信息</h2>
 * <p>一级信息指出厂或装机时固化、换主板/硬盘/CPU 之外不会变化的项，
 * 是判定「同一台机器」的依据；易变信息（主机名、MAC、卷序列号）只作说明：</p>
 * <table border="1">
 *   <tr><th>锚点</th><th>Windows</th><th>Linux</th><th>macOS</th></tr>
 *   <tr><td>整机 UUID</td><td>SMBIOS UUID</td><td>/sys/class/dmi/id/product_uuid</td><td>IOPlatformUUID</td></tr>
 *   <tr><td>主板序列号</td><td>Win32_BaseBoard</td><td>/sys/class/dmi/id/board_serial</td><td>—（无独立字段）</td></tr>
 *   <tr><td>BIOS 序列号</td><td>Win32_BIOS</td><td>/sys/class/dmi/id/product_serial</td><td>IOPlatformSerialNumber</td></tr>
 *   <tr><td>机器标识</td><td>MachineGuid</td><td>/etc/machine-id</td><td>—</td></tr>
 *   <tr><td>磁盘序列号</td><td>Win32_DiskDrive</td><td>sys/class/block 下的 device/serial</td><td>—（需特权）</td></tr>
 *   <tr><td>CPU ID</td><td>Win32_Processor</td><td>—</td><td>—</td></tr>
 * </table>
 *
 * <h2>唯一 ID 怎么用</h2>
 * <pre>{@code
 * // 首次激活：生成并登记
 * String id = SystemUtils.uniqueId();
 * // 之后每次启动：校验是不是同一台
 * if (!SystemUtils.verifyUniqueId(savedId)) {
 *     // 换硬件或迁移，走重新绑定流程
 * }
 * }</pre>
 *
 * <p>判定不是「全等」而是「按比例」：换网卡、换主机名、重装系统都不会让 ID 判定失败；
 * 换主板会同时丢掉多项一级信息，跌破阈值后判定为不同机器——这样合法换硬件不会误伤客户，
 * 整机搬迁或克隆也仍然能被识别出来。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
@Slf4j
public final class SystemUtils {

    /**
     * 私有构造，禁止实例化
     */
    private SystemUtils() {
    }

    // ==================== 主机信息 ====================

    /**
     * 获取当前主机的完整信息快照（含一级与易变项）。
     *
     * <p>结果在进程内缓存，重复调用没有采集开销；需要重新读取时先调
     * {@link #refreshHostFacts()}（例如换了网卡或热插拔硬盘后）。</p>
     *
     * @return 快照，永不为 null
     */
    public static HostFacts hostFacts() {
        return HostInfoRouter.current();
    }




    /**
     * 获取当前主机信息来自哪个采集通道（如 {@code windows-cim}）。
     *
     * @return 通道名；尚未采集时为空串
     */
    public static String hostInfoChannel() {
        return HostInfoRouter.channel();
    }

    /**
     * 获取一级不可变信息（只含出厂/装机固化的锚点）。
     *
     * @return 锚点 → 取值，保持枚举顺序；取不到的锚点不出现
     */
    public static Map<String, String> immutableInfo() {
        return export(true);
    }

    /**
     * 获取易变信息（主机名、MAC、卷序列号等，不参与 ID 判定）。
     *
     * @return 锚点 → 取值；取不到的锚点不出现
     */
    public static Map<String, String> mutableInfo() {
        return export(false);
    }


    /**
     * 按层级导出锚点取值。
     *
     * @param strong true 导出不可变项，false 导出易变项
     * @return 字段名 → 归一化取值
     */
    private static Map<String, String> export(boolean strong) {
        HostFacts facts = hostFacts();
        Map<String, String> result = new LinkedHashMap<>();
        for (HostAnchor anchor : HostAnchor.values()) {
            if (anchor.strong() != strong) {
                continue;
            }
            String value = facts.value(anchor);
            if (!value.isEmpty()) {
                result.put(anchor.field(), value);
            }
        }
        return result;
    }

    /**
     * 判断当前环境能否采集到一级不可变信息。
     *
     * <p>容器、精简内核、权限受限的账号可能一项都取不到；此时不应生成 ID，
     * 否则会得到一个只由易变项算出的「假唯一 ID」，换主机名就变。</p>
     *
     * @return 至少取到一项返回 true
     */
    public static boolean hasImmutableInfo() {
        return hostFacts().strongCount() > 0;
    }


    // ==================== 唯一 ID ====================

    /**
     * 采集一级不可变信息并生成主机唯一 ID。
     *
     * <p>返回值可直接存放（授权文件、数据库、配置中心），格式固定为
     * {@code CH1-XXXX-XXXX-...}，共 43 个字符，含校验位，可识别抄写错误。</p>
     *
     * @return 唯一 ID
     * @throws IllegalStateException 当前环境取不到任何一级不可变信息时抛出
     */
    public static String uniqueId() {
        return HostIdentity.generate();
    }

    /**
     * 用指定快照生成唯一 ID。
     *
     * @param facts 主机信息快照
     * @return 唯一 ID
     * @throws IllegalStateException 快照中没有一级不可变信息
     */
    public static String uniqueId(HostFacts facts) {
        return HostIdentity.generate(facts);
    }

    /**
     * 用默认策略校验唯一 ID 是否仍属当前机器。
     *
     * <p>默认要求「可比项中至少 75% 匹配」且「至少 2 项匹配」。
     * 换网卡/改主机名不影响判定；换整块主板会跌破阈值。</p>
     *
     * @param id 待校验的 ID
     * @return 判定结果，含匹配率、不一致项与可读原因
     */
    public static HostIdentity.Match verifyId(String id) {
        return HostIdentity.match(id);
    }

    /**
     * 用指定快照校验唯一 ID。
     *
     * @param id    待校验的 ID
     * @param facts 当前机器快照
     * @return 判定结果
     */
    public static HostIdentity.Match verifyId(String id, HostFacts facts) {
        return HostIdentity.match(id, facts);
    }

    /**
     * 用自定义阈值校验唯一 ID。
     *
     * @param id       待校验的 ID
     * @param ratio    匹配率下限，取值范围 (0, 1]
     * @param minMatch 匹配数量下限，至少为 1
     * @return 判定结果
     */
    public static HostIdentity.Match verifyId(String id, double ratio, int minMatch) {
        return HostIdentity.match(id, hostFacts(), ratio, minMatch);
    }

    /**
     * 便捷校验：是否仍是同一台机器（默认策略）。
     *
     * @param id 待校验的 ID
     * @return 同一台返回 true
     */
    public static boolean verifyUniqueId(String id) {
        return HostIdentity.verify(id);
    }

    /**
     * 解析唯一 ID 中承载的锚点摘要。
     *
     * <p>只给出各锚点的截断摘要，不含原值——ID 可以公开传递，不泄露硬件序列号。
     * 主要用途是排查「为什么这台机器被判成换了」：对比
     * {@link HostIdentity.Parsed#anchors()} 与 {@link #immutableInfo()} 即可看出
     * ID 是拿哪几项算出来的。</p>
     *
     * @param id 唯一 ID
     * @return 解析结果；格式非法时 {@link HostIdentity.Parsed#valid()} 为 false
     */
    public static HostIdentity.Parsed parseId(String id) {
        return HostIdentity.parse(id);
    }


    // ==================== 通用信息 ====================







}
