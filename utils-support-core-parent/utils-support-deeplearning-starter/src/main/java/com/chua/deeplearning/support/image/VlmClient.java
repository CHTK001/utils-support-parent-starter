package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;

public interface VlmClient {

    static VlmClient create(String name) {
        return ServiceProvider.of(VlmClient.class).create(name);
    }

    VlmClient model(String model);

    UnderstandResult understand(byte[] imageData, UnderstandTask task);
}