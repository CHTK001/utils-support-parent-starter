package com.chua.common.support.network.http.invoke;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.http.EventSourceListener;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 java.net.URL 的 SSE 客户端实现
 *
 * @author CH
 */
@Slf4j
@Spi(value = "url", order = 1)
public class UrlSseClientInvoker extends AbstractSseClientInvoker implements AutoCloseable {

    private static final String TEXT_EVENT_STREAM = "text/event-stream";
    private static final String DATA_PREFIX = "data:";
    private static final String ID_PREFIX = "id:";
    private static final String EVENT_PREFIX = "event:";
    private static final String RETRY_PREFIX = "retry:";

    private volatile boolean closed = false;
    private volatile HttpURLConnection connection;
    private ExecutorService executorService;

    public UrlSseClientInvoker(HttpRequest request, HttpMethod httpMethod) {
        super(request, httpMethod);
    }

    @Override
    public void execute(EventSourceListener listener) {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
        executorService.execute(() -> {
            try {
                connection = openConnection();
                configureConnection(connection);
                writeBody(connection);

                int status = connection.getResponseCode();
                if (status >= 400) {
                    listener.onFailure(new RuntimeException("HTTP error: " + status));
                    return;
                }

                String contentType = connection.getContentType();
                if (contentType == null || !contentType.contains(TEXT_EVENT_STREAM)) {
                    log.warn("[SSE][URL] Content-Type is not text/event-stream: {}", contentType);
                }

                listener.onOpen();
                parseSseStream(connection, listener);
            } catch (Exception e) {
                if (!closed) {
                    listener.onFailure(e);
                }
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        if (connection != null) {
            connection.disconnect();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    private HttpURLConnection openConnection() throws Exception {
        URL url = new URL(request.getUrl());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout((int) request.getConnectTimeoutMill());
        conn.setReadTimeout((int) request.getReadTimeoutMill());
        conn.setDoInput(true);
        return conn;
    }

    private void configureConnection(HttpURLConnection conn) throws Exception {
        conn.setRequestMethod(httpMethod.name());
        conn.setRequestProperty("Accept", TEXT_EVENT_STREAM);
        conn.setRequestProperty("Cache-Control", "no-cache");

        var headers = request.getHeaders();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.asSimpleMap().entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private void writeBody(HttpURLConnection conn) throws Exception {
        var bodyData = request.getBodyData();
        if (bodyData != null && !bodyData.isEmpty()) {
            conn.setDoOutput(true);
            byte[] body = bodyData.toByteArray();
            if (body != null && body.length > 0) {
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
        }
    }

    private void parseSseStream(HttpURLConnection conn, EventSourceListener listener) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            SseEvent current = new SseEvent();

            while (!closed && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (current.hasData()) {
                        listener.onEvent(current.id, current.type, current.data);
                        current = new SseEvent();
                    }
                    continue;
                }
                if (line.startsWith(DATA_PREFIX)) {
                    String data = line.substring(DATA_PREFIX.length()).trim();
                    current.data = current.data == null ? data : current.data + "\n" + data;
                } else if (line.startsWith(ID_PREFIX)) {
                    current.id = line.substring(ID_PREFIX.length()).trim();
                } else if (line.startsWith(EVENT_PREFIX)) {
                    current.type = line.substring(EVENT_PREFIX.length()).trim();
                } else if (line.startsWith(RETRY_PREFIX)) {
                    // ignore retry hint
                } else if (!line.startsWith(":")) {
                    current.data = current.data == null ? line : current.data + "\n" + line;
                }
            }

            if (current.hasData()) {
                listener.onEvent(current.id, current.type, current.data);
            }
        } catch (Exception e) {
            if (!closed) {
                listener.onFailure(e);
            }
        } finally {
            if (!closed) {
                listener.onClosed();
            }
        }
    }

    private static class SseEvent {
        String id;
        String type;
        String data;

        boolean hasData() {
            return data != null && !data.isEmpty();
        }
    }
}
