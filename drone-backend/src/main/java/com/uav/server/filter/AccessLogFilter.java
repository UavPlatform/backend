package com.uav.server.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class AccessLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            int status = response.getStatus();
            String method = request.getMethod();
            String path = request.getRequestURI();
            String query = "/dispatch/events".equals(request.getServletPath()) ? null : request.getQueryString();
            String url = query != null ? path + "?" + query : path;

            String traceId = MDC.get("traceId");
            String traceInfo = traceId != null ? "trace=" + traceId + " " : "";

            if (status >= 500) {
                log.error("{} {} {} {}ms", method, url, status, duration);
            } else if (status >= 400) {
                log.warn("{} {} {} {}ms", method, url, status, duration);
            } else {
                log.info("{}{} {} {} {}ms", traceInfo, method, url, status, duration);
            }

            MDC.clear();
        }
    }
}
