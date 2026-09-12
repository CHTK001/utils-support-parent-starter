package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
* 单个台风详情实体。
*
* <p>对应浙江省水利厅 {@code /Api/TyphoonInfo/{tfid}} 响应，
* 含生成/结束时间、当前中心、登陆记录、历史路径与多机构预报。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonDetail {

    /** 台风编号 */
    private String tfid;

    /** 中文名 */
    private String name;

    /** 英文名 */
    private String enname;

    /** 是否活跃（1=活跃） */
    private String isactive;

    /** 生成时间 */
    private String starttime;

    /** 结束时间 */
    private String endtime;

    /** 预警级别 */
    private String warnlevel;

    /** 当前中心经度 */
    private String centerlng;

    /** 当前中心纬度 */
    private String centerlat;

    /** 登陆记录 */
    private List<TyphoonLand> land;

    /** 历史路径点（含多机构预报） */
    private List<TyphoonPoint> points;
}
