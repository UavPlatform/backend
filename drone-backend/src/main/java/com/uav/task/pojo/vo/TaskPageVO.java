package com.uav.task.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskPageVO {
    private List<TaskVo> tasks;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}
