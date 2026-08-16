package com.chua.common.support.datasearch.region.spi;

import com.chua.common.support.datasearch.region.model.RegionInfo;

import java.util.List;

/**
 * 行政区划数据提供者 SPI 接口。
 *
 * <p>统一封装中国行政区划（省 / 市 / 区 / 街道）的查询能力，
 * 支持「构造设置几级数据」——实现类可携带一个默认层级，
 * 调用方也可以通过 {@link #getRegions(int)} / {@link #getTree(int)} 显式指定最大层级。
 *
 * <p>各实现通过 SPI 机制注册，例如基于阿里云 DataV GeoAtlas 的在线数据源。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RegionProvider {

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 获取默认层级（由实现构造决定）的行政区划扁平列表。
     *
     * <p>列表中每个节点均携带 {@code level}/{@code parent}，可据此还原层级关系。
     *
     * @return 行政区划列表
     */
    List<RegionInfo> getRegions();

    /**
     * 获取指定最大层级的行政区划扁平列表。
     *
     * <p>层级定义：1=省/直辖市，2=市/地区，3=区/县，4=街道/镇。
     *
     * @param maxLevel 最大层级（1~4）
     * @return 行政区划列表
     */
    List<RegionInfo> getRegions(int maxLevel);

    /**
     * 获取指定父级编码下的下一级行政区划。
     *
     * <p>{@code parentAdcode} 为 {@code null}/空/"100000" 时返回省级列表。
     *
     * @param parentAdcode 父级行政区划代码
     * @return 下级行政区划列表
     */
    List<RegionInfo> getChildren(String parentAdcode);

    /**
     * 获取指定最大层级的行政区划树（嵌套 children）。
     *
     * <p>层级定义同 {@link #getRegions(int)}。注意层级越深，在线数据源触发
     * 的 HTTP 请求越多（市级约 35 次、区县级约 300+ 次），建议按需使用。
     *
     * @param maxLevel 最大层级（1~4）
     * @return 以「中国」为根、嵌套 children 的行政区划树
     */
    RegionInfo getTree(int maxLevel);
}
