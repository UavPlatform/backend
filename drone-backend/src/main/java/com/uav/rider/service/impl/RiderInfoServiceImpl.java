package com.uav.rider.service.impl;

import com.uav.rider.mapper.RiderAircraftQualificationRepository;
import com.uav.rider.mapper.RiderRepository;
import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.entity.Rider;
import com.uav.rider.pojo.entity.RiderAircraftQualification;
import com.uav.rider.pojo.vo.RiderInfoVO;
import com.uav.rider.service.RiderInfoService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.task.service.TaskService;
import com.uav.task.pojo.vo.RiderStatsVO;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RiderInfoServiceImpl implements RiderInfoService {

    private final RiderRepository riderRepository;
    private final RiderAircraftQualificationRepository qualificationRepository;
    private final UserRepository userRepository;
    private final TaskService taskService;

    @Override
    public RiderInfoVO getInfo(Long userId) {
        Rider rider = riderRepository.findById(userId).orElse(Rider.builder().build());
        return buildVO(userId, rider);
    }

    @Override
    @Transactional
    public RiderInfoVO editInfo(Long userId, RiderInfoDto dto) {
        validate(userId, dto);

        Rider rider = riderRepository.findById(userId)
                .orElseGet(() -> {
                    Rider r = Rider.builder().build();
                    r.setId(userId);
                    return r;
                });

        Optional.ofNullable(dto.getAge()).ifPresent(rider::setAge);
        Optional.ofNullable(dto.getCertNumber()).ifPresent(rider::setCertNumber);
        Optional.ofNullable(dto.getCertValidFrom()).ifPresent(rider::setCertValidFrom);
        Optional.ofNullable(dto.getCertValidUntil()).ifPresent(rider::setCertValidUntil);
        Optional.ofNullable(dto.getLocation()).ifPresent(rider::setLocation);
        Optional.ofNullable(dto.getSelfIntroduction()).ifPresent(rider::setSelfIntroduction);
        riderRepository.save(rider);

        if (dto.getQualifications() != null) {
            List<RiderAircraftQualification> existing = qualificationRepository.findByUserId(userId);

            Set<String> dtoKeys = dto.getQualifications().stream()
                    .map(q -> qualKey(q.getCategory(), q.getLicense(), q.getWeight()))
                    .collect(Collectors.toSet());

            List<RiderAircraftQualification> toDelete = existing.stream()
                    .filter(e -> !dtoKeys.contains(qualKey(e.getCategory(), e.getLicense(), e.getWeight())))
                    .toList();
            if (!toDelete.isEmpty()) {
                qualificationRepository.deleteAll(toDelete);
            }

            Set<String> existingKeys = existing.stream()
                    .map(e -> qualKey(e.getCategory(), e.getLicense(), e.getWeight()))
                    .collect(Collectors.toSet());

            List<RiderAircraftQualification> toAdd = dto.getQualifications().stream()
                    .filter(q -> !existingKeys.contains(qualKey(q.getCategory(), q.getLicense(), q.getWeight())))
                    .map(q -> RiderAircraftQualification.builder()
                            .userId(userId)
                            .category(q.getCategory())
                            .license(q.getLicense())
                            .weight(q.getWeight())
                            .build())
                    .toList();
            if (!toAdd.isEmpty()) {
                qualificationRepository.saveAll(toAdd);
            }
        }

        return buildVO(userId, rider);
    }

    private RiderInfoVO buildVO(Long userId, Rider rider) {
        String userName = userRepository.findById(userId).map(User::getUserName).orElse(null);
        RiderStatsVO stats = taskService.getRiderStats(userId);
        List<RiderAircraftQualification> quals = qualificationRepository.findByUserId(userId);
        return RiderInfoVO.from(rider, userName, quals,
                stats.getTodayOrders(), stats.getTotalCompleted(), stats.getTotalEarnings());
    }

    private static String qualKey(Object category, Object license, Object weight) {
        return category + "|" + license + "|" + weight;
    }

    private void validate(Long userId, RiderInfoDto dto) {
        if (dto.getCertNumber() != null && dto.getCertNumber().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "证件编号不能为空字符串");
        }
        if (dto.getCertValidFrom() != null && dto.getCertValidUntil() != null
                && !dto.getCertValidFrom().isBefore(dto.getCertValidUntil())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "证件有效期起始日期必须早于截止日期");
        }
    }
}
