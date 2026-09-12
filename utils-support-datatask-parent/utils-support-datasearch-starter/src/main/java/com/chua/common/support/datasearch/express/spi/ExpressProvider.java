package com.chua.common.support.datasearch.express.spi;

import com.chua.common.support.datasearch.express.model.ExpressTrace;

import java.util.List;

/**
 * 快递物流查询 SPI 接口。
 *
 * <p>封装快递单号物流轨迹查询能力。各实现通过 SPI 机制注册，
   * 例如基于快递100 / 快递鸟等在线查询接口。生产环境通常需配置 API 键，
 * 具体数据源地址与鉴权通过实现类构造参数注入。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ExpressProvider {

    /**
     * 获取数据源名称
     *
     * @return 数据源名称
     */
    String name();

    /**
     * 根据快递单号查询物流轨迹（自动识别快递公司）。
     *
     * @param trackingNo 快递单号
     * @return 物流轨迹列表（识别失败或无轨迹时返回空列表）
     */
    List<ExpressTrace> query(String trackingNo);

    /**
     * 根据快递公司与单号查询物流轨迹。
     *
     * @param companyCode 快递公司编码（如 sf、yd、zt、ems）
     * @param trackingNo  快递单号
     * @return 物流轨迹列表
     */
    List<ExpressTrace> query(String companyCode, String trackingNo);
}
