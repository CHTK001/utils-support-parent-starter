package com.chua.common.support.datasearch.typhoon;

import com.chua.common.support.datasearch.typhoon.model.TyphoonActivity;
import com.chua.common.support.datasearch.typhoon.model.TyphoonDetail;
import com.chua.common.support.datasearch.typhoon.spi.TyphoonProvider;
import com.chua.common.support.datasearch.typhoon.spi.impl.ZhejiangTyphoonProvider;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;

/**
 * 台风真实数据验证:SPI 发现 + 浙江省水利厅活跃列表 + 单个详情。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TyphoonVerifyTest {

    /**
     * 运行验证。
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        TyphoonProvider provider = ServiceProvider.of(TyphoonProvider.class)
                .getExtension("zhejiang-typhoon");
        System.out.println("SPI 发现 zhejiang-typhoon: " + (provider != null));
        if (provider == null) {
            provider = new ZhejiangTyphoonProvider();
        }
        List<TyphoonActivity> list = provider.getActiveTyphoons();
        System.out.println("活跃台风数: " + list.size());
        for (TyphoonActivity t : list) {
            System.out.printf("  %s %s(%s) 强度=%s 风力%s级 中心(%s,%s) 移向%s 速度%skm/h 气压%shPa%n",
                    t.getTfid(), t.getName(), t.getEnname(), t.getStrong(), t.getPower(),
                    t.getLat(), t.getLng(), t.getMovedirection(), t.getMovespeed(), t.getPressure());
        }
        if (!list.isEmpty()) {
            TyphoonDetail detail = provider.getTyphoon(list.get(0).getTfid());
            if (detail != null) {
                System.out.printf("详情 %s(%s): 活跃=%s 生成=%s 结束=%s 预警=%s 中心(%s,%s)%n",
                        detail.getName(), detail.getEnname(), detail.getIsactive(),
                        detail.getStarttime(), detail.getEndtime(), detail.getWarnlevel(),
                        detail.getCenterlat(), detail.getCenterlng());
                System.out.println("  历史路径点: " + (detail.getPoints() == null ? 0 : detail.getPoints().size()));
                System.out.println("  登陆记录: " + (detail.getLand() == null ? 0 : detail.getLand().size()));
                if (detail.getLand() != null && !detail.getLand().isEmpty()) {
                    System.out.println("  首次登陆: " + detail.getLand().get(0).getLandaddress()
                            + " @ " + detail.getLand().get(0).getLandtime());
                }
            }
        }
    }
}
