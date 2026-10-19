package com.uav.contract;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.uav.server.ws.WsMessageNames;
import com.uav.server.ws.handler.WsMessageHandler;
import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket protocol contract gate.
 *
 * <p>Keeps {@code backend/spec/ws/} (manifest, schemas, examples, SHA256SUMS) in
 * sync with the handlers and constants. Additive-only: the wire format must not
 * change without a downstream coordination task.
 */
class WsProtocolContractIT extends IntegrationTestBase {

    private static final Path SPEC = Path.of("..", "spec", "ws");
    private static final Path SHA256SUMS = SPEC.resolve("SHA256SUMS");

    @Autowired
    private List<WsMessageHandler> handlers;

    @Test
    @DisplayName("每个 WsMessageHandler 都在清单中登记")
    void everyHandlerIsRegistered() {
        JSONObject manifest = manifest();
        Set<String> registered = new TreeSet<>();
        for (String channel : manifest.getJSONObject("channels").keySet()) {
            JSONArray inbound = manifest.getJSONObject("channels").getJSONObject(channel).getJSONArray("inbound");
            for (int index = 0; index < inbound.size(); index++) {
                registered.add(inbound.getJSONObject(index).getString("handler"));
            }
        }
        assertThat(handlers).as("Spring 上下文必须发现 handler").isNotEmpty();
        for (WsMessageHandler handler : handlers) {
            assertThat(registered)
                    .as("handler %s 缺少 spec/ws 清单条目", handler.getClass().getSimpleName())
                    .contains(handler.getClass().getSimpleName());
        }
    }

    @Test
    @DisplayName("清单中的每个入站消息都由对应 handler 支持")
    void everyInboundEntryHasHandler() {
        JSONObject manifest = manifest();
        Map<String, WsMessageHandler> byName = new LinkedHashMap<>();
        handlers.forEach(handler -> byName.put(handler.getClass().getSimpleName(), handler));
        for (String channel : manifest.getJSONObject("channels").keySet()) {
            JSONArray inbound = manifest.getJSONObject("channels").getJSONObject(channel).getJSONArray("inbound");
            for (int index = 0; index < inbound.size(); index++) {
                JSONObject entry = inbound.getJSONObject(index);
                String handlerName = entry.getString("handler");
                assertThat(byName).as("%s 引用了不存在的 handler %s", channel, handlerName).containsKey(handlerName);
                WsMessageHandler handler = byName.get(handlerName);
                assertThat(handler.supports(entry.getString("type"), entry.getString("name")))
                        .as("%s/%s/%s 未被 %s 支持", channel, entry.getString("type"), entry.getString("name"), handlerName)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("schema 与示例文件存在且可解析，示例的 type/name 与清单一致")
    void schemasAndExamplesMatchManifest() throws Exception {
        JSONObject manifest = manifest();
        Set<String> referenced = new LinkedHashSet<>();
        for (String channel : manifest.getJSONObject("channels").keySet()) {
            JSONObject definition = manifest.getJSONObject("channels").getJSONObject(channel);
            for (String direction : List.of("inbound", "outbound")) {
                JSONArray entries = definition.getJSONArray(direction);
                if (entries == null) {
                    continue;
                }
                for (int index = 0; index < entries.size(); index++) {
                    JSONObject entry = entries.getJSONObject(index);
                    for (String key : List.of("schema", "example")) {
                        String relative = entry.getString(key);
                        assertThat(relative).as("%s/%s 缺少 %s", channel, entry.getString("name"), key).isNotBlank();
                        Path path = SPEC.resolve(relative);
                        assertThat(Files.isRegularFile(path)).as("缺少文件 %s", relative).isTrue();
                        referenced.add(relative);
                        JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
                    }
                    JSONObject example = JSON.parseObject(
                            Files.readString(SPEC.resolve(entry.getString("example")), StandardCharsets.UTF_8));
                    assertThat(example.getString("type")).as("示例 type").isEqualTo(entry.getString("type"));
                    if (entry.getString("name") != null) {
                        assertThat(example.getString("name")).as("示例 name").isEqualTo(entry.getString("name"));
                    }
                }
            }
        }
        Set<String> actual = new TreeSet<>();
        for (String directory : List.of("schemas", "examples")) {
            try (var stream = Files.walk(SPEC.resolve(directory))) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> actual.add(SPEC.relativize(path).toString().replace('\\', '/')));
            }
        }
        assertThat(actual).as("协议目录存在未登记文件").isEqualTo(new TreeSet<>(referenced));
    }

    @Test
    @DisplayName("SHA256SUMS 覆盖全部协议文件且哈希一致")
    void checksumsMatch() throws Exception {
        assertThat(Files.isRegularFile(SHA256SUMS)).as("缺少 spec/ws/SHA256SUMS").isTrue();
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(SHA256SUMS, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf("  ");
            assertThat(separator).as("SHA256SUMS 行格式错误: %s", line).isPositive();
            expected.put(line.substring(separator + 2).trim(), line.substring(0, separator).trim());
        }
        Set<String> actual = new TreeSet<>();
        try (var stream = Files.walk(SPEC)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("SHA256SUMS"))
                    .forEach(path -> actual.add(SPEC.relativize(path).toString().replace('\\', '/')));
        }
        assertThat(expected.keySet()).as("SHA256SUMS 与实际文件不一致").isEqualTo(actual);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            byte[] hash = digest.digest(Files.readAllBytes(SPEC.resolve(entry.getKey())));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            assertThat(hex.toString()).as("哈希不一致: %s", entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test
    @DisplayName("WsMessageNames 常量与清单取值一致")
    void constantsMatchManifest() throws Exception {
        JSONObject manifest = manifest();
        Set<String> manifestValues = new LinkedHashSet<>();
        for (String channel : manifest.getJSONObject("channels").keySet()) {
            JSONObject definition = manifest.getJSONObject("channels").getJSONObject(channel);
            for (String direction : List.of("inbound", "outbound")) {
                JSONArray entries = definition.getJSONArray(direction);
                if (entries == null) {
                    continue;
                }
                for (int index = 0; index < entries.size(); index++) {
                    JSONObject entry = entries.getJSONObject(index);
                    manifestValues.add(entry.getString("type"));
                    if (entry.getString("name") != null) {
                        manifestValues.add(entry.getString("name"));
                    }
                }
            }
        }
        Set<String> constants = new LinkedHashSet<>();
        for (Field field : WsMessageNames.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class) {
                constants.add((String) field.get(null));
            }
        }
        assertThat(constants).as("WsMessageNames 与清单取值不一致").isEqualTo(manifestValues);
    }

    private JSONObject manifest() {
        try {
            return JSON.parseObject(Files.readString(SPEC.resolve("ws-manifest.json"), StandardCharsets.UTF_8));
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException("cannot read ws-manifest.json", error);
        }
    }
}
