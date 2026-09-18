package com.uav.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * OpenAPI 结构契约（SSOT）的读取 / 归一化 / 落盘工具。
 *
 * <p>导出与门禁约定：
 * <ul>
 *   <li><b>唯一事实源</b>：{@code backend/spec/openapi/drone-backend.openapi.json}（后端仓库持有并提交）。</li>
 *   <li><b>导出物必须归一化</b>：springdoc 在 RANDOM_PORT 下生成 {@code servers[0].url} 含临时端口，
 *       且示例里可能夹带本机端口；不归一化则漂移门禁永远是脏的（T2-R2）。</li>
 *   <li><b>门禁形态</b>：归一化后与冻结规格做<b>整文档</b>深比较（不只是操作集合），
 *       这样字段级增删改同样会红——字段级漂移门禁的缺口由此关闭。</li>
 * </ul>
 *
 * <p>归一化的三条规则（顺序执行，保证同一份实现多次导出<b>逐字节</b>一致）：
 * <ol>
 *   <li>字符串擦洗：{@code http(s)://127.0.0.1|localhost|0.0.0.0(:端口)} → {@code http://localhost}；</li>
 *   <li>{@code servers} 固定为相对基址 {@code /}（运行时基址由部署环境决定，不属于契约）；</li>
 *   <li>对象键按字典序、数组元素按规范化 JSON 文本排序（OpenAPI 中数组顺序无语义）。</li>
 * </ol>
 */
public final class OpenApiContract {

    /** 归一化后冻结规格的默认位置：Maven 以模块目录为工作目录，故上一级即 backend 仓库根。 */
    public static final Path DEFAULT_SPEC =
            Paths.get("..", "spec", "openapi", "drone-backend.openapi.json");

    /** 每次构建都写一份到 target，便于人工比对（不参与门禁）。 */
    public static final Path BUILD_OUTPUT =
            Paths.get("target", "openapi", "drone-backend.openapi.json");

    private static final String SERVERS_PLACEHOLDER =
            "[{\"url\":\"/\",\"description\":\"相对基址：由部署环境（网关/反向代理）决定\"}]";

    private static final Pattern HOST_WITH_PORT = Pattern.compile(
            "https?://(?:127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0)(?::\\d+)?");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenApiContract() {
    }

    /** 冻结规格路径：{@code -Dopenapi.spec=...} 或 {@code OPENAPI_SPEC} 可覆盖（用于跨仓对账）。 */
    public static Path specPath() {
        String override = System.getProperty("openapi.spec");
        if (override == null || override.isBlank()) {
            override = System.getenv("OPENAPI_SPEC");
        }
        return (override == null || override.isBlank()) ? DEFAULT_SPEC : Paths.get(override);
    }

    /** 显式导出开关：默认<b>只校验不落盘</b>，避免构建过程静默改写 spec/（规格变更必须有人评审）。 */
    public static boolean exportRequested() {
        return Boolean.getBoolean("openapi.export") || "true".equalsIgnoreCase(System.getenv("OPENAPI_EXPORT"));
    }

    /** 从运行中的服务拉取 {@code /v3/api-docs} 原文。 */
    public static JsonNode fetchLiveSpec(int port) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/v3/api-docs"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("/v3/api-docs 返回 " + response.statusCode() + "：" + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    public static JsonNode parse(String raw) throws IOException {
        return MAPPER.readTree(raw);
    }

    /** 归一化（擦洗 → servers 占位 → 全树排序），结果可逐字节复现。 */
    public static JsonNode canonicalize(JsonNode node) {
        return sort(scrub(node, true));
    }

    public static String pretty(JsonNode node) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + System.lineSeparator();
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算 sha256", e);
        }
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    private static JsonNode scrub(JsonNode node, boolean root) {
        if (node.isTextual()) {
            String text = node.asText();
            String scrubbed = HOST_WITH_PORT.matcher(text).replaceAll("http://localhost");
            return TextNode.valueOf(scrubbed);
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.properties().forEach(entry -> {
                String key = entry.getKey();
                if (root && "servers".equals(key)) {
                    try {
                        out.set(key, MAPPER.readTree(SERVERS_PLACEHOLDER));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                } else {
                    out.set(key, scrub(entry.getValue(), false));
                }
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            node.forEach(child -> out.add(scrub(child, false)));
            return out;
        }
        return node.deepCopy();
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(e -> sorted.put(e.getKey(), sort(e.getValue())));
            ObjectNode out = MAPPER.createObjectNode();
            sorted.forEach(out::set);
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            node.forEach(child -> children.add(sort(child)));
            children.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode out = MAPPER.createArrayNode();
            children.forEach(out::add);
            return out;
        }
        return node.deepCopy();
    }

    /** 便捷入口：读取冻结规格（不存在时报错信息可操作）。 */
    public static JsonNode readFrozen() throws IOException {
        Path spec = specPath();
        if (!Files.exists(spec)) {
            throw new IllegalStateException(
                    "冻结规格不存在：" + spec.toAbsolutePath() + "；请先运行 bash tools/openapi-export.sh 并评审后提交");
        }
        return canonicalize(parse(Files.readString(spec, StandardCharsets.UTF_8)));
    }
}
