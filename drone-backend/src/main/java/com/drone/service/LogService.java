package com.drone.service;

import com.drone.pojo.vo.log.LogFileVO;

import java.util.List;

public interface LogService {
    List<String> getApplicationLogs(int lines);

    List<String> getErrorLogs(int lines);

    List<LogFileVO> listLogFiles(String relativePath);

    List<String> readLogFile(String relativePath, int lines);
}
