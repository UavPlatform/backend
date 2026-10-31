package com.uav.equipment.pojo.dto;
import jakarta.validation.constraints.Size;
/** Admin approve: hand-pick droneId, or random=true for one in-stock enabled airframe of the applied model. */
public record BorrowApproveDTO(
        @Size(max = 36) String droneId,
        Boolean random) { }
