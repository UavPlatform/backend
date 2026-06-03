package com.uav.log.controller;

import com.uav.server.result.Result;
import com.uav.log.pojo.vo.LogFileVO;
import com.uav.log.pojo.vo.LogVO;
import com.uav.server.annotation.OperationLog;
import com.uav.log.service.LogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Log API")
@RestController
@RequestMapping("/admin/logs")
@Slf4j
public class LogController {

    @Autowired
    private LogService logService;

    @OperationLog("查看日志文件列表")
    @Operation(
            summary = "获取日志文件列表",
            description = "列出日志目录下的文件和子目录",
            parameters = {
                    @Parameter(name = "path", description = "相对路径，留空表示根目录")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "获取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"data\": [{\"name\": \"2026-06-01\", \"path\": \"logs/2026-06-01\", \"directory\": true, \"size\": 0, \"lastModified\": \"2026-06-01 16:00:00\"}]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/files")
    public Result<List<LogFileVO>> listFiles(@RequestParam(defaultValue = "") String path) {
        return Result.success(logService.listLogFiles(path));
    }

    @OperationLog("读取日志文件")
    @Operation(
            summary = "读取日志文件内容",
            description = "读取指定日志文件的最后 N 行",
            parameters = {
                    @Parameter(name = "file", description = "日志文件相对路径", required = true),
                    @Parameter(name = "lines", description = "读取行数，默认 200")
            },
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "读取成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"data\": {\"logs\": [\"2026-06-01 10:00:00 INFO ...\", \"...\"]}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/read")
    public Result<LogVO> readLog(@RequestParam String file,
                                  @RequestParam(defaultValue = "200") int lines) {
        List<String> logs = logService.readLogFile(file, lines);
        return Result.success(new LogVO(logs));
    }

    @OperationLog("获取应用日志")
    @GetMapping("/application")
    public Result<LogVO> getApplicationLogs(@RequestParam(defaultValue = "100") int lines) {
        List<String> logs = logService.getApplicationLogs(lines);
        return Result.success(new LogVO(logs));
    }

    @OperationLog("获取错误日志")
    @GetMapping("/error")
    public Result<LogVO> getErrorLogs(@RequestParam(defaultValue = "100") int lines) {
        List<String> logs = logService.getErrorLogs(lines);
        return Result.success(new LogVO(logs));
    }
}
