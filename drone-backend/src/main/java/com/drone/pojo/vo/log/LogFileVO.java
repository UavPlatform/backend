package com.drone.pojo.vo.log;

public record LogFileVO(
        String name,
        String path,
        boolean directory,
        long size,
        String lastModified
) {}
