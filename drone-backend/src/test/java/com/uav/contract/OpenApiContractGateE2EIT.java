package com.uav.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.uav.support.OpenApiContract;
import com.uav.support.RealProtocolTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构契约（OpenAPI）的导出与漂移门禁。
 *
 * <p>两个职责刻意分开，避免「构建顺手改规格」：
 * <ul>
 *   <li>{@link #exportContract()}：把运行中的 {@code /v3/api-docs} 归一化后写入
 *       {@code target/openapi/}；<b>仅当</b>显式给定 {@code -Dopenapi.export=true}（或 {@code OPENAPI_EXPORT=true}）
 *       才落到 {@code spec/openapi/drone-backend.openapi.json}。日常只读不写。</li>
 *   <li>{@link #frozenSpecMatchesRunningImplementation()}：把运行中实现归一化后与冻结规格做整文档深比较。
 *       任何端点/参数/字段/枚举的增删改都会让门禁变红——这就是「实现漂移」的唯一判据。</li>
 * </ul>
 *
 * <p>驱动方式与分层（R4/R9/O4/O6）：本测试经 {@code @LocalServerPort} + {@code java.net.http} 真实拉取
 * {@code http://127.0.0.1:<port>/v3/api-docs}，属真实 TCP 驱动的端到端门禁，因此命名 {@code *E2EIT} 并继承
 * {@link RealProtocolTestBase}（{@code RANDOM_PORT}、无 {@code @Transactional}）。它是真实的进程间
 * 结构契约校验，不是进程内 MockMvc 测试，故不自称「集成测试」。
 *
 * <p>跑在本机 H2（profile=test）上，不依赖 T4_DB_* 真库环境变量，因此可以在 CI 里常态阻断。
 */
class OpenApiContractGateE2EIT extends RealProtocolTestBase {

    @Test
    @DisplayName("契约导出：/v3/api-docs → 归一化 → target/openapi（-Dopenapi.export=true 时才写 spec/）")
    void exportContract() throws Exception {
        JsonNode live = OpenApiContract.canonicalize(OpenApiContract.fetchLiveSpec(port));
        String document = OpenApiContract.pretty(live);

        Files.createDirectories(OpenApiContract.BUILD_OUTPUT.getParent());
        Files.writeString(OpenApiContract.BUILD_OUTPUT, document, StandardCharsets.UTF_8);

        assertThat(live.path("openapi").asText()).as("OpenAPI 版本").startsWith("3.");
        assertThat(operations(live)).as("业务端点数量（防护：扫描配置失效导致契约空转）").hasSizeGreaterThan(80);

        if (OpenApiContract.exportRequested()) {
            Path spec = OpenApiContract.specPath();
            Files.createDirectories(spec.getParent());
            Files.writeString(spec, document, StandardCharsets.UTF_8);
            System.out.println("[openapi] 契约已导出：" + spec.toAbsolutePath());
            System.out.println("[openapi] sha256=" + OpenApiContract.sha256(document));
            System.out.println("[openapi] 端点数=" + operations(live).size());
        } else {
            System.out.println("[openapi] 只读模式：已写入 " + OpenApiContract.BUILD_OUTPUT.toAbsolutePath()
                    + "（加 -Dopenapi.export=true 才会更新 spec/）");
        }
    }

    @Test
    @DisplayName("契约门禁：冻结规格与运行中实现逐字段一致（任何漂移即红）")
    void frozenSpecMatchesRunningImplementation() throws Exception {
        Path spec = OpenApiContract.specPath();
        assertThat(Files.exists(spec))
                .as("冻结规格缺失：%s（先运行 bash tools/openapi-export.sh 并评审提交）", spec.toAbsolutePath())
                .isTrue();

        JsonNode frozen = OpenApiContract.readFrozen();
        JsonNode live = OpenApiContract.canonicalize(OpenApiContract.fetchLiveSpec(port));

        assertThat(operations(live))
                .as("端点集合漂移（实现新增/删除了接口，或 controller 未被 springdoc 扫描到）")
                .isEqualTo(operations(frozen));
        assertThat(diffSummary(frozen, live))
                .as("实现已漂移：请重新导出并在评审后更新 %s", spec)
                .isEmpty();
        assertThat(live).as("契约文档与冻结规格不一致").isEqualTo(frozen);
    }

    private static Set<String> operations(JsonNode spec) {
        Set<String> out = new TreeSet<>();
        spec.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(op ->
                        out.add(op.getKey().toUpperCase() + " " + path.getKey())));
        return out;
    }

    /** 逐路径/逐方法定位第一处差异，便于在失败信息里直接看出漂移位置。 */
    private static String diffSummary(JsonNode frozen, JsonNode live) {
        StringBuilder out = new StringBuilder();
        Set<String> paths = new TreeSet<>();
        frozen.path("paths").properties().forEach(p -> paths.add(p.getKey()));
        live.path("paths").properties().forEach(p -> paths.add(p.getKey()));

        for (String path : paths) {
            JsonNode frozenPath = frozen.path("paths").path(path);
            JsonNode livePath = live.path("paths").path(path);
            if (!frozenPath.equals(livePath)) {
                Set<String> methods = new TreeSet<>();
                frozenPath.properties().forEach(m -> methods.add(m.getKey()));
                livePath.properties().forEach(m -> methods.add(m.getKey()));
                for (String method : methods) {
                    if (!frozenPath.path(method).equals(livePath.path(method))) {
                        out.append("  - ").append(method.toUpperCase()).append(' ').append(path).append('\n');
                    }
                }
            }
        }
        if (!frozen.path("components").equals(live.path("components"))) {
            out.append("  - components.schemas 存在差异\n");
        }
        return out.toString();
    }
}
