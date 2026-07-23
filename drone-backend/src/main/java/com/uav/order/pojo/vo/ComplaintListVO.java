package com.uav.order.pojo.vo;

import com.uav.order.pojo.entity.OrderComplaint;
import lombok.Data;

import java.util.List;

@Data
public class ComplaintListVO {
    private List<OrderComplaint> complaints;
    private int currentPage;
    private int totalPages;
    private long totalElements;
}
