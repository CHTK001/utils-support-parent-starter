package com.chua.common.support.network.protocol.storage.image;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * @author CH
 * @since 4.0.0.42
 */

public interface ImageSettingProcessor {

    @Nullable
    byte[] process(@Nonnull byte[] imageData, @Nonnull String settingValue);
}
