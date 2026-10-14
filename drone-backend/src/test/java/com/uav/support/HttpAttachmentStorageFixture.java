package com.uav.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.uav.attachment.service.AttachmentObjectStorage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** External storage fixture only: real HTTP bytes, with actual existence/size checks (REQ-BACKEND-001). */
public final class HttpAttachmentStorageFixture implements AttachmentObjectStorage, AutoCloseable {
    private final HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    public HttpAttachmentStorageFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/objects/", this::handle);
        server.start();
    }

    @Override public boolean isConfigured() { return true; }
    @Override public String presignedPut(String key, int seconds, String type) { return address(key); }
    @Override public String presignedGet(String key, int seconds) { return address(key); }
    @Override public boolean isValidObject(String key, Long expectedSize) {
        byte[] bytes = objects.get(key);
        return bytes != null && expectedSize != null && expectedSize > 0 && bytes.length == expectedSize;
    }

    private String address(String key) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/objects/"
                + URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Storage requests must not receive platform credentials.
            if (exchange.getRequestHeaders().containsKey("Authorization")) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            String key = URLDecoder.decode(exchange.getRequestURI().getRawPath().substring("/objects/".length()),
                    StandardCharsets.UTF_8);
            if ("PUT".equals(exchange.getRequestMethod())) {
                objects.put(key, exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                byte[] bytes = objects.get(key);
                if (bytes == null) exchange.sendResponseHeaders(404, -1);
                else {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } else exchange.sendResponseHeaders(405, -1);
        }
    }

    @Override public void close() {
        server.stop(0);
        objects.clear();
    }
}
