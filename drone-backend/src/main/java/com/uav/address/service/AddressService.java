package com.uav.address.service;

import com.uav.address.pojo.dto.AddressDto;
import com.uav.address.pojo.entity.Address;

import java.util.List;

public interface AddressService {

    /** 当前用户地址列表（默认在前） */
    List<Address> list(Long userId);

    Address create(Long userId, AddressDto dto);

    Address update(Long userId, AddressDto dto);

    void delete(Long userId, Long id);

    /** 设为默认（同用户其他默认自动取消） */
    void setDefault(Long userId, Long id);
}
