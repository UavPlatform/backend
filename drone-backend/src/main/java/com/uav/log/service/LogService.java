package com.uav.log.service;

import com.uav.log.pojo.vo.LogFileVO;

import java.util.List;

public interface LogService {
    List<String> getApplicationLogs(int lines);

    List<String> getErrorLogs(int lines);

    List<LogFileVO> listLogFiles(String relativePath);

    List<String> readLogFile(String relativePath, int lines);
}
