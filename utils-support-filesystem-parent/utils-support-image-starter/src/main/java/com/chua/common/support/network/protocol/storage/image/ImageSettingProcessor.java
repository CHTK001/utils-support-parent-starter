package com.chua.common.support.network.protocol.storage.image;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface ImageSettingProcessor {

    /**
     * 处理。
     *
     * @param imageData image数据，不允许为 null
     * @param settingValue setting值，不允许为 null
     * @return 结果值
     */
    @Nullable
    byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue);
}
