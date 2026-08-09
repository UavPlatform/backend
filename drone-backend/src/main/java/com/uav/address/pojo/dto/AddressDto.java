package com.uav.address.pojo.dto;

import lombok.Data;

/**
 * 收货地址新增/编辑入参（编辑时携带 id）
 */
@Data
public class AddressDto {

    /** 编辑时必填，新增时空 */
    private Long id;

    private String contactName;

    private String contactPhone;

    private String province;

    private String city;

    private String district;

    private String detail;

    private Double latitude;

    private Double longitude;

    /** 标签：家/公司/学校 */
    private String label;

    private Boolean isDefault = false;
}
