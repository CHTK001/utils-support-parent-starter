package com.chua.common.support.nativeapi;

import com.chua.common.support.spi.ServiceProvider;

/**
 * native 能力 SPI 查找入口。
 *
 * <p>统一收敛三件事：</p>
 * <ul>
 *   <li>只依赖接口类型，不出现 {@code Class.forName("com.chua.native...")}；</li>
 *   <li>native 制品缺失时返回 {@code null}，而不是把 {@code NoClassDefFoundError}
 *       抛给业务调用方；</li>
 *   <li>实现类自身声明不可用时，按缺失处理。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.50
 */
public final class NativeServices {

    private NativeServices() {
    }

    /**
     * 查找可用的 native 能力实现。
     *
     * @param type 能力接口
     * @param <T>  能力类型
     * @return 可用实现；未引入 native 制品或实现声明不可用时返回 {@code null}
     */
    public static <T extends NativeService> T get(Class<T> type) {
        if (type == null) {
            return null;
        }
        try {
            T service = ServiceProvider.of(type).getExtension((String) null);
            return service != null && service.available() ? service : null;
        } catch (Throwable e) {
            // 契约层只做能力探测，native 加载失败的详细原因由实现自己记录日志
            return null;
        }
    }

    /**
     * 查找可用的 native 能力实现，缺失时抛出带能力名的异常。
     *
     * @param type       能力接口
     * @param capability 能力名称，用于异常文案
     * @param <T>        能力类型
     * @return 可用实现
     */
    public static <T extends NativeService> T require(Class<T> type, String capability) {
        T service = get(type);
        if (service == null) {
            throw new IllegalStateException(capability
                    + "不可用：请自行添加对应的 utils-support-native-* 依赖");
        }
        return service;
    }

    /**
     * 能力是否可用。
     *
     * @param type 能力接口
     * @return 可用返回 {@code true}
     */
    public static boolean available(Class<? extends NativeService> type) {
        return get(type) != null;
    }
}
