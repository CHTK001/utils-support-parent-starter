package com.chua.deeplearning.support.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModelParamsProviderDeviceTest {

    @BeforeEach
    @AfterEach
    void reset() {
        ModelParams.clear();
    }

    @Test
    void providerOverrideTakesPrecedenceOverModelParams() {
        ModelParams.put("model-a", Map.of(ModelParams.KEY_DEVICE, "cpu"));
        assertEquals("cpu", ModelParams.deviceOf("model-a"));
        assertEquals("cpu", ModelParams.resolveDevice("model-a", null));

        ModelParams.setDeviceOverride("model-a", "gpu");
        assertEquals("gpu", ModelParams.deviceOf("model-a"));
        assertEquals("gpu", ModelParams.resolveDevice("model-a", null));
    }

    @Test
    void clearingOverrideRestoresModelParamsValue() {
        ModelParams.put("model-a", Map.of(ModelParams.KEY_DEVICE, "cpu"));
        ModelParams.setDeviceOverride("model-a", "gpu");
        assertEquals("gpu", ModelParams.deviceOf("model-a"));

        ModelParams.setDeviceOverride("model-a", "auto");
        assertEquals("cpu", ModelParams.deviceOf("model-a"));
        assertEquals("cpu", ModelParams.resolveDevice("model-a", null));
    }

    @Test
    void explicitOptionsStillWinOverProviderOverride() {
        ModelParams.setDeviceOverride("model-a", "gpu");
        assertEquals("cpu", ModelParams.resolveDevice("model-a", Map.of(ModelParams.KEY_DEVICE, "cpu")));
    }
}