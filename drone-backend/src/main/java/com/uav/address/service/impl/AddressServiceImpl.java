package com.uav.address.service.impl;

import com.uav.address.mapper.AddressRepository;
import com.uav.address.pojo.dto.AddressDto;
import com.uav.address.pojo.entity.Address;
import com.uav.address.service.AddressService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class AddressServiceImpl implements AddressService {

    @Autowired
    private AddressRepository addressRepository;

    @Override
    @Transactional(readOnly = true)
    public List<Address> list(Long userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescCreateTimeDesc(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Address create(Long userId, AddressDto dto) {
        validate(dto);

        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            addressRepository.findByUserIdAndIsDefaultTrue(userId)
                    .ifPresent(a -> a.setIsDefault(false));
        }

        Address address = new Address();
        address.setUserId(userId);
        apply(address, dto);
        Address saved = addressRepository.save(address);
        log.info("用户ID {} 新增地址 {}（{}）", userId, saved.getId(), saved.getContactName());
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Address update(Long userId, AddressDto dto) {
        if (dto.getId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "地址ID不能为空");
        }
        validate(dto);

        Address address = addressRepository.findByIdAndUserId(dto.getId(), userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "地址不存在"));

        if (Boolean.TRUE.equals(dto.getIsDefault()) && !Boolean.TRUE.equals(address.getIsDefault())) {
            addressRepository.clearOtherDefaults(userId, address.getId());
        }
        apply(address, dto);
        Address saved = addressRepository.save(address);
        log.info("用户ID {} 更新地址 {}", userId, saved.getId());
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        Address address = addressRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "地址不存在"));
        addressRepository.delete(address);
        log.info("用户ID {} 删除地址 {}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        Address address = addressRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "地址不存在"));
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            return;
        }
        addressRepository.clearOtherDefaults(userId, address.getId());
        address.setIsDefault(true);
        addressRepository.save(address);
        log.info("用户ID {} 将地址 {} 设为默认", userId, id);
    }

    /** 联系人/电话/详细地址必填，电话 1 开头的 11 位数字 */
    private void validate(AddressDto dto) {
        if (dto.getContactName() == null || dto.getContactName().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "联系人不能为空");
        }
        if (dto.getContactPhone() == null || dto.getContactPhone().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "手机号不能为空");
        }
        if (!dto.getContactPhone().trim().matches("1\\d{10}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "手机号格式不正确");
        }
        if (dto.getDetail() == null || dto.getDetail().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "详细地址不能为空");
        }
    }

    private void apply(Address address, AddressDto dto) {
        address.setContactName(dto.getContactName().trim());
        address.setContactPhone(dto.getContactPhone().trim());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetail(dto.getDetail().trim());
        address.setLatitude(dto.getLatitude());
        address.setLongitude(dto.getLongitude());
        address.setLabel(dto.getLabel());
        address.setIsDefault(Boolean.TRUE.equals(dto.getIsDefault()));
    }
}
