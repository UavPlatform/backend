package com.drone.service.impl;

import com.drone.service.LogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class LogServiceImpl implements LogService {

    private static final String LOG_DIR = "logs";
    private static final String APPLICATION_LOG = "application.log";
    private static final String ERROR_LOG = "error.log";

    @Override
    public List<String> getApplicationLogs(int lines) {
        return readLogFile(LOG_DIR + File.separator + APPLICATION_LOG, lines);
    }

    @Override
    public List<String> getErrorLogs(int lines) {
        return readLogFile(LOG_DIR + File.separator + ERROR_LOG, lines);
    }

    private List<String> readLogFile(String filePath, int lines) {
        List<String> logLines = new ArrayList<>();
        File logFile = new File(filePath);

        if (!logFile.exists()) {
            log.warn("Log file not found: {}", filePath);
            return logLines;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLines.add(line);
            }
        } catch (IOException e) {
            log.error("Failed to read log file: {}", e.getMessage());
        }

        if (logLines.size() > lines) {
            return logLines.subList(logLines.size() - lines, logLines.size());
        }

        return logLines;
    }
}
