package com.chua.common.support.datasearch.region.model;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 行政区划信息模型。
 *
 * <p>对应国家统计局六级行政区划代码中的前四级：
 * 省/直辖市/自治区/特别行政区(level=1)、市/地区/自治州(level=2)、
 * 区/县/县级市(level=3)、街道/镇/乡(level=4)。
 *
 * <p>节点之间以 {@link #parent}（上级 adcode）与 {@link #children} 形成树结构，
 * 便于按层级构造与遍历「几级数据」。
 *
 * @author CH
 * @since 2026/07/27
 */
public class RegionInfo {

    /**
     * 行政区划代码（adcode，6 位数字）
     */
    private final String adcode;

    /**
     * 行政区划名称
     */
    private final String name;

    /**
     * 层级：1=省级 2=市级 3=区县级 4=街道乡镇级
     */
    private final int level;

    /**
     * 层级名称：province/city/district/street
     */
    private final String levelName;

    /**
     * 上级行政区划代码（省级为 null）
     */
    private final String parent;

    /**
     * 中心点经度
     */
    private final double lng;

    /**
     * 中心点纬度
     */
    private final double lat;

    /**
     * 下级行政区划（按需装载，可能为空）
     */
    private List<RegionInfo> children;

    public RegionInfo(String adcode, String name, int level, String levelName, String parent, double lng, double lat) {
        this.adcode = adcode;
        this.name = name;
        this.level = level;
        this.levelName = levelName;
        this.parent = parent;
        this.lng = lng;
        this.lat = lat;
    }

    public String getAdcode() {
        return adcode;
    }

    public String getName() {
        return name;
    }

    public int getLevel() {
        return level;
    }

    public String getLevelName() {
        return levelName;
    }

    public String getParent() {
        return parent;
    }

    public double getLng() {
        return lng;
    }

    public double getLat() {
        return lat;
    }

    public List<RegionInfo> getChildren() {
        return children;
    }

    public void setChildren(List<RegionInfo> children) {
        this.children = children;
    }

    /**
     * 转换为 Map 用于 JSON 序列化
     *
     * @return Map 表示
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("adcode", adcode);
        map.put("name", name);
        map.put("level", level);
        map.put("levelName", levelName);
        map.put("parent", parent);
        map.put("lng", lng);
        map.put("lat", lat);
        map.put("children", children);
        return map;
    }
}
