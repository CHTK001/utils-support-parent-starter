package com.chua.common.support.ai.chat.usage;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUsageRecordTest {

    @Mock
    private ModelPricingProvider pricingProvider;

    private static final String PROVIDER = "openai";
    private static final String MODEL = "gpt-4";
    private static final BigDecimal INPUT_PRICE = new BigDecimal("0.03");
    private static final BigDecimal OUTPUT_PRICE = new BigDecimal("0.06");
    private static final String CURRENCY = "USD";

    @BeforeEach
    void setUp() {
        ModelDefinition definition = ModelDefinition.builder()
                .id(MODEL)
                .name(MODEL)
                .provider(PROVIDER)
                .inputUnitPrice(INPUT_PRICE)
                .outputUnitPrice(OUTPUT_PRICE)
                .currency(CURRENCY)
                .build();
        when(pricingProvider.getModelPricing(PROVIDER, MODEL)).thenReturn(definition);
    }

    @Test
    void enrichPricing_shouldFillMissingPrices() {
        try (MockedStatic<ServiceProvider> services = mockStatic(ServiceProvider.class)) {
            services.when(() -> ServiceProvider.of(ModelPricingProvider.class))
                    .thenReturn(pricingProvider);

            AiUsageRecord record = AiUsageRecord.builder()
                    .provider(PROVIDER)
                    .model(MODEL)
                    .inputTokens(100L)
                    .outputTokens(200L)
                    .build();

            assertNull(record.getInputUnitPrice());
            assertNull(record.getOutputUnitPrice());
            assertNull(record.getCurrency());

            record.enrichPricing();

            assertEquals(INPUT_PRICE, record.getInputUnitPrice());
            assertEquals(OUTPUT_PRICE, record.getOutputUnitPrice());
            assertEquals(CURRENCY, record.getCurrency());
        }
    }

    @Test
    void enrichPricing_shouldNotOverwriteExistingPrices() {
        try (MockedStatic<ServiceProvider> services = mockStatic(ServiceProvider.class)) {
            services.when(() -> ServiceProvider.of(ModelPricingProvider.class))
                    .thenReturn(pricingProvider);

            AiUsageRecord record = AiUsageRecord.builder()
                    .provider(PROVIDER)
                    .model(MODEL)
                    .inputUnitPrice(new BigDecimal("0.01"))
                    .outputUnitPrice(new BigDecimal("0.02"))
                    .currency("CNY")
                    .inputTokens(100L)
                    .outputTokens(200L)
                    .build();

            record.enrichPricing();

            assertEquals(new BigDecimal("0.01"), record.getInputUnitPrice());
            assertEquals(new BigDecimal("0.02"), record.getOutputUnitPrice());
            assertEquals("CNY", record.getCurrency());
        }
    }

    @Test
    void enrichPricing_shouldHandleProviderNotFound() {
        try (MockedStatic<ServiceProvider> services = mockStatic(ServiceProvider.class)) {
            services.when(() -> ServiceProvider.of(ModelPricingProvider.class))
                    .thenReturn(null);

            AiUsageRecord record = AiUsageRecord.builder()
                    .provider("unknown")
                    .model("model")
                    .inputTokens(100L)
                    .outputTokens(200L)
                    .build();

            record.enrichPricing();

            assertNull(record.getInputUnitPrice());
            assertNull(record.getOutputUnitPrice());
            assertNull(record.getCurrency());
        }
    }

    @Test
    void enrichPricing_shouldHandleModelNotFound() {
        try (MockedStatic<ServiceProvider> services = mockStatic(ServiceProvider.class)) {
            services.when(() -> ServiceProvider.of(ModelPricingProvider.class))
                    .thenReturn(pricingProvider);
            when(pricingProvider.getModelPricing(PROVIDER, "unknown")).thenReturn(null);

            AiUsageRecord record = AiUsageRecord.builder()
                    .provider(PROVIDER)
                    .model("unknown")
                    .inputTokens(100L)
                    .outputTokens(200L)
                    .build();

            record.enrichPricing();

            assertNull(record.getInputUnitPrice());
            assertNull(record.getOutputUnitPrice());
            assertNull(record.getCurrency());
        }
    }

    @Test
    void enrichPricing_shouldHandleNullProvider() {
        try (MockedStatic<ServiceProvider> services = mockStatic(ServiceProvider.class)) {
            // No need to mock, enrichPricing checks provider == null first, so ServiceProvider won't be called.
            AiUsageRecord record = AiUsageRecord.builder()
                    .provider(null)
                    .model(MODEL)
                    .inputTokens(100L)
                    .outputTokens(200L)
                    .build();

            record.enrichPricing();

            assertNull(record.getInputUnitPrice());
            assertNull(record.getOutputUnitPrice());
            assertNull(record.getCurrency());
        }
    }
}
