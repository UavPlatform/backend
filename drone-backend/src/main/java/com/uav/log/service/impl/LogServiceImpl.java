package com.uav.log.service.impl;

import com.uav.server.enums.ApiErrorCode;
import com.uav.log.pojo.vo.LogFileVO;
import com.uav.server.exception.BusinessException;
import com.uav.log.service.LogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
public class LogServiceImpl implements LogService {

    @Value("${log.dir:logs}")
    private String logDirConfig;

    private Path logsRoot;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    @PostConstruct
    void initLogDir() {
        Path path = Paths.get(logDirConfig);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path);
        }
        this.logsRoot = path.toAbsolutePath().normalize();
        log.info("Log directory resolved to: {}", logsRoot);
    }

    @Override
    public List<String> getApplicationLogs(int lines) {
        return readLogFile("application.log", lines);
    }

    @Override
    public List<String> getErrorLogs(int lines) {
        return readLogFile("error.log", lines);
    }

    @Override
    public List<LogFileVO> listLogFiles(String relativePath) {
        File dir = resolveSafe(relativePath);
        if (!dir.exists()) {
            return List.of();
        }
        if (!dir.isDirectory()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "路径不是目录");
        }

        File[] children = dir.listFiles();
        if (children == null) {
            return List.of();
        }

        Arrays.sort(children, Comparator.comparing(File::getName));

        List<LogFileVO> result = new ArrayList<>();
        for (File child : children) {
            if (child.isDirectory()) {
                result.add(toVO(child, true));
            } else if (child.getName().endsWith(".log")) {
                result.add(toVO(child, false));
            }
        }
        return result;
    }

    @Override
    public List<String> readLogFile(String relativePath, int lines) {
        File file = resolveSafe(relativePath);
        if (!file.exists()) {
            return List.of();
        }
        if (file.isDirectory()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "不能读取目录");
        }

        List<String> logLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLines.add(line);
            }
        } catch (IOException e) {
            log.error("Failed to read log file: {}", e.getMessage(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ApiErrorCode.INTERNAL_ERROR, "日志文件读取失败");
        }

        if (logLines.size() > lines) {
            return logLines.subList(logLines.size() - lines, logLines.size());
        }
        return logLines;
    }

    private File resolveSafe(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return logsRoot.toFile();
        }

        Path resolved = logsRoot.resolve(relativePath).normalize();

        if (!resolved.startsWith(logsRoot)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "非法路径");
        }
        return resolved.toFile();
    }

    private String relativePath(File file) {
        Path rel = logsRoot.relativize(file.toPath());
        return rel.toString().replace('\\', '/');
    }

    private LogFileVO toVO(File file, boolean directory) {
        String lastModified = "";
        if (file.lastModified() > 0) {
            lastModified = DATE_FMT.format(Instant.ofEpochMilli(file.lastModified()));
        }
        return new LogFileVO(
                file.getName(),
                relativePath(file),
                directory,
                directory ? 0 : file.length(),
                lastModified
        );
    }
}
