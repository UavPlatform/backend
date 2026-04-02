package com.drone.service;

import java.util.List;

public interface LogService {
    List<String> getApplicationLogs(int lines);

    List<String> getErrorLogs(int lines);
}
