package com.uav.dispatch;

import com.sun.net.httpserver.HttpServer;
import com.uav.attachment.service.AttachmentObjectStorage;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Actual HTTP PUT/GET and real disk metadata, only for isolated tests; never a production provider. */
final class DispatchObjectStoreFixture implements AttachmentObjectStorage, AutoCloseable {
    private final Path root;
    private final HttpServer server;
    DispatchObjectStoreFixture() throws IOException {
        root = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "dispatch-object-it-").normalize();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/objects/", exchange -> {
            try {
                String key = exchange.getRequestURI().getPath().substring("/objects/".length());
                Path file = path(key);
                if (exchange.getRequestMethod().equals("PUT")) {
                    String expectedType = URLDecoder.decode(exchange.getRequestURI().getRawQuery().substring(5), StandardCharsets.UTF_8);
                    if (exchange.getRequestHeaders().getFirst("Authorization") != null
                            || !expectedType.equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                        exchange.sendResponseHeaders(403, -1); return;
                    }
                    Files.createDirectories(file.getParent()); Files.write(file, exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                } else if (exchange.getRequestMethod().equals("GET") && Files.exists(file)) {
                    byte[] bytes = Files.readAllBytes(file); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } else exchange.sendResponseHeaders(404, -1);
            } finally { exchange.close(); }
        });
        server.start();
    }
    private Path path(String key) {
        Path file = root.resolve(key).normalize();
        if (!key.matches("[A-Za-z0-9/-]+") || !file.startsWith(root)) throw new IllegalArgumentException("invalid key");
        return file;
    }
    public boolean isConfigured() { return server.getAddress().getPort() > 0 && Files.isDirectory(root); }
    public String presignedPut(String key, int seconds, String type) { return presignedGet(key, seconds) + "?type=" + URLEncoder.encode(type, StandardCharsets.UTF_8); }
    public String presignedGet(String key, int seconds) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/objects/" + key; }
    public boolean isValidObject(String key, Long size) {
        try { return size != null && size > 0 && Files.isRegularFile(path(key)) && Files.size(path(key)) == size; }
        catch (IOException e) { return false; }
    }
    public void remove(String key) throws IOException { Files.deleteIfExists(path(key)); }
    public void close() { server.stop(0); }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean(destroyMethod = "close") @Primary DispatchObjectStoreFixture dispatchTestObjectStore() throws IOException { return new DispatchObjectStoreFixture(); }
    }
}
