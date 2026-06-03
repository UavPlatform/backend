package com.uav.log.pojo.vo;

public record LogFileVO(
        String name,
        String path,
        boolean directory,
        long size,
        String lastModified
) {}
