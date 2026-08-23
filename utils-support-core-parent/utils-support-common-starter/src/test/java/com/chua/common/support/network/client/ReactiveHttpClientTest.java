package com.chua.common.support.network.client;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 响应式 HTTP 客户端测试（executeAsync 返回 Mono）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
class ReactiveHttpClientTest {

    @Test
    void testExecuteAsyncReturnsMono() {
        HttpClient client = HttpClientFactory.getClient();
        Mono<ClientResponse> mono = client.executeAsync(ClientRequest.of("http://localhost:1/_ping"));
        assertNotNull(mono);
    }

    @Test
    void testGetAndPostStillWork() {
        HttpClient client = HttpClientFactory.getClient();
        // 同步方法不受影响
        assertThrows(Exception.class, () -> client.get("http://localhost:1/"));
        assertThrows(Exception.class, () -> client.post("http://localhost:1/", "body"));
    }

    @Test
    void testCallbackStyleStillWorks() {
        HttpClient client = HttpClientFactory.getClient();
        boolean[] called = {false};
        client.executeAsync(ClientRequest.of("http://localhost:1/"),
                new Callback<ClientResponse>() {
                    @Override public void onSuccess(ClientResponse resp) {}
                    @Override public void onError(Throwable err) { called[0] = true; }
                });
        assertTrue(called[0]);
    }
}
