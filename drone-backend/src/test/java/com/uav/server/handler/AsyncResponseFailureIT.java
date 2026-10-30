package com.uav.server.handler;

import com.uav.support.IntegrationTestBase;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import java.io.IOException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REQ-ALL-XC-004: unusable streams bypass the JSON error envelope. */
@Import(AsyncResponseFailureIT.Failures.class)
@ExtendWith(OutputCaptureExtension.class)
class AsyncResponseFailureIT extends IntegrationTestBase {
    @Test void disconnectedStreamIsHandledWithoutJsonOrSecondaryConverterError(CapturedOutput output) throws Exception {
        var owner = accounts().registerUser();
        var result = mockMvc.perform(get("/test/async-unusable")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(content().string(": ready\n\n"))
                .andReturn();
        // MVC can clear negotiation headers during exception resolution; never replace them with JSON.
        assertThat(result.getResponse().getContentType()).isNotEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class);
        assertThat(output.getAll()).doesNotContain("Failure in @ExceptionHandler", "No converter for", "系统内部错误");
    }

    @Test void ordinaryServerFailureStillReturnsTheJsonErrorEnvelope() throws Exception {
        var owner = accounts().registerUser();
        mockMvc.perform(get("/test/ordinary-failure")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(500));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @RestController
    static class Failures {
        @GetMapping("/test/async-unusable")
        void disconnected(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.getWriter().write(": ready\n\n");
            response.flushBuffer();
            throw new AsyncRequestNotUsableException("Response not usable after response errors.");
        }

        @GetMapping("/test/ordinary-failure")
        void ordinaryFailure() { throw new IllegalStateException("controlled failure"); }
    }
}
