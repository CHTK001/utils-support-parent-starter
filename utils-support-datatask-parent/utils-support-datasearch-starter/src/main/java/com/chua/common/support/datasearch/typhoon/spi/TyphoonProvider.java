package com.chua.common.support.datasearch.typhoon.spi;

import com.chua.common.support.datasearch.typhoon.model.TyphoonActivity;
import com.chua.common.support.datasearch.typhoon.model.TyphoonDetail;

import java.util.List;

/**
* 台风数据提供者 SPI 接口。
*
* <p>提供当前活跃台风列表与单个台风完整信息（历史路径 + 多机构预报），
* 各实现通过 SPI 机制注册（如浙江省水利厅台风 API）。</p>
*
* @author CH
* @since 4.0.0.42
 */
public interface TyphoonProvider {

    /**
    * 获取数据源名称。
    *
    * @return 数据源名称
    */
    String name();

    /**
    * 获取当前活跃台风列表。
    *
    * @return 活跃台风列表；数据源不可达时返回空列表
    */
    List<TyphoonActivity> getActiveTyphoons();

    /**
    * 获取单个台风完整详情（含历史路径与预报）。
    *
    * @param tfid 台风编号（如 202618）
    * @return 台风详情；数据源不可达或编号不存在时返回 空
    */
    TyphoonDetail getTyphoon(String tfid);
}
