package com.uav.rider.service.impl;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.rider.mapper.RiderQualificationRepository;
import com.uav.rider.mapper.RiderRepository;
import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.dto.RiderQualificationDto;
import com.uav.rider.pojo.entity.Rider;
import com.uav.rider.pojo.entity.RiderQualification;
import com.uav.rider.pojo.vo.RiderInfoVO;
import com.uav.rider.pojo.vo.RiderQualificationVO;
import com.uav.rider.service.RiderInfoService;
import com.uav.rider.support.IdNumbers;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 飞手资料与驾驶资质（重写自 PR #14 {@code RiderInfoServiceImpl}）。
 *
 * <ul>
 *   <li>资料 PATCH 语义：DTO 字段为 null 不修改；首次保存时按 user.id 创建 {@code rider} 行。</li>
 *   <li>身份证号：规范化 + GB 11643 校验码校验，出参一律脱敏；错误提示不回显原值。</li>
 *   <li>证件有效期：不得早于今天（已过期证件不允许登记）。</li>
 *   <li>资质：以机型为键做集合差异——请求中没有的删除、新机型新增、同机型等级变化则更新；
 *       新增或改等级的机型须存在且启用（{@code AIRCRAFT_MODEL_NOT_FOUND}），已持有且未变化的资质
 *       即使机型后来停用也原样保留。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class RiderInfoServiceImpl implements RiderInfoService {

    private static final int ROLE_RIDER = 1;

    private final RiderRepository riderRepository;
    private final RiderQualificationRepository qualificationRepository;
    private final AircraftModelRepository aircraftModelRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public RiderInfoVO getInfo(Long userId) {
        User user = requireRider(userId);
        Rider rider = riderRepository.findById(userId).orElse(null);
        return toVO(user, rider, qualificationRepository.findByUserIdOrderByIdAsc(userId));
    }

    @Override
    @Transactional
    public RiderInfoVO editInfo(Long userId, RiderInfoDto dto) {
        User user = requireRider(userId);
        String idNumber = validateIdNumber(dto.getIdNumber());
        validateExpiry(dto.getIdExpiryDate());
        QualificationPlan plan = dto.getQualifications() == null
                ? null : planQualifications(userId, dto.getQualifications());

        Rider rider = riderRepository.findById(userId).orElseGet(() -> {
            Rider r = new Rider();
            r.setId(userId);
            return r;
        });
        if (dto.getAge() != null) {
            rider.setAge(dto.getAge());
        }
        if (idNumber != null) {
            rider.setIdNumber(idNumber);
        }
        if (dto.getIdExpiryDate() != null) {
            rider.setIdExpiryDate(dto.getIdExpiryDate());
        }
        if (dto.getLocation() != null) {
            rider.setLocation(dto.getLocation());
        }
        if (dto.getSelfIntroduction() != null) {
            rider.setSelfIntroduction(dto.getSelfIntroduction());
        }
        rider = riderRepository.save(rider);

        riderRepository.flush();
        List<RiderQualification> qualifications = plan == null
                ? qualificationRepository.findByUserIdOrderByIdAsc(userId)
                : applyQualifications(plan);
        return toVO(user, rider, qualifications);
    }

    /**
     * 资质同步计划：先校验（重复机型、机型存在且启用），全部通过后才落任何写入，保证请求原子性。
     */
    private record QualificationPlan(Long userId,
                                     Map<Long, RiderQualificationDto> wantedByModel,
                                     List<RiderQualification> existing) {
    }

    private QualificationPlan planQualifications(Long userId, List<RiderQualificationDto> wanted) {
        Map<Long, RiderQualificationDto> wantedByModel = new LinkedHashMap<>();
        for (RiderQualificationDto q : wanted) {
            if (wantedByModel.putIfAbsent(q.getAircraftModelId(), q) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "同一机型只能登记一条资质");
            }
        }
        List<RiderQualification> existing = qualificationRepository.findByUserIdOrderByIdAsc(userId);
        Map<Long, RiderQualification> existingByModel = byModel(existing);

        // 新增或改等级的机型须存在且启用（一次批量查询）
        Set<Long> touchedModelIds = new HashSet<>();
        wantedByModel.forEach((modelId, q) -> {
            RiderQualification held = existingByModel.get(modelId);
            if (held == null || held.getLicenseGrade() != q.getLicenseGrade()) {
                touchedModelIds.add(modelId);
            }
        });
        if (!touchedModelIds.isEmpty()) {
            Map<Long, AircraftModel> models = aircraftModelRepository.findAllById(touchedModelIds).stream()
                    .collect(Collectors.toMap(AircraftModel::getId, Function.identity()));
            for (Long modelId : touchedModelIds) {
                AircraftModel model = models.get(modelId);
                if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.AIRCRAFT_MODEL_NOT_FOUND);
                }
            }
        }
        return new QualificationPlan(userId, wantedByModel, existing);
    }

    /** 以机型为键的集合差异：删除请求中缺失的机型、更新等级变化的机型、新增新机型。 */
    private List<RiderQualification> applyQualifications(QualificationPlan plan) {
        Map<Long, RiderQualification> existingByModel = byModel(plan.existing());
        List<RiderQualification> toDelete = plan.existing().stream()
                .filter(e -> !plan.wantedByModel().containsKey(e.getAircraftModelId()))
                .toList();
        if (!toDelete.isEmpty()) {
            qualificationRepository.deleteAll(toDelete);
        }
        plan.wantedByModel().forEach((modelId, q) -> {
            RiderQualification held = existingByModel.get(modelId);
            if (held == null) {
                RiderQualification created = new RiderQualification();
                created.setUserId(plan.userId());
                created.setAircraftModelId(modelId);
                created.setLicenseGrade(q.getLicenseGrade());
                qualificationRepository.save(created);
            } else if (held.getLicenseGrade() != q.getLicenseGrade()) {
                held.setLicenseGrade(q.getLicenseGrade());
            }
        });
        qualificationRepository.flush();
        return qualificationRepository.findByUserIdOrderByIdAsc(plan.userId());
    }

    private static Map<Long, RiderQualification> byModel(List<RiderQualification> qualifications) {
        return qualifications.stream()
                .collect(Collectors.toMap(RiderQualification::getAircraftModelId, Function.identity()));
    }

    private RiderInfoVO toVO(User user, Rider rider, List<RiderQualification> qualifications) {
        List<RiderQualificationVO> qualificationVOs = toQualificationVOs(qualifications);
        if (rider == null) {
            return new RiderInfoVO(user.getId(), user.getUserName(), null, null, null, false,
                    null, null, qualificationVOs, null, null);
        }
        return new RiderInfoVO(user.getId(), user.getUserName(), rider.getAge(),
                IdNumbers.mask(rider.getIdNumber()), rider.getIdExpiryDate(), isExpired(rider.getIdExpiryDate()),
                rider.getLocation(), rider.getSelfIntroduction(), qualificationVOs,
                rider.getCreateTime(), rider.getUpdateTime());
    }

    private List<RiderQualificationVO> toQualificationVOs(List<RiderQualification> qualifications) {
        if (qualifications.isEmpty()) {
            return List.of();
        }
        Set<Long> modelIds = qualifications.stream().map(RiderQualification::getAircraftModelId)
                .collect(Collectors.toSet());
        Map<Long, AircraftModel> models = aircraftModelRepository.findAllById(modelIds).stream()
                .collect(Collectors.toMap(AircraftModel::getId, Function.identity()));
        return qualifications.stream()
                .map(q -> RiderQualificationVO.of(q, models.get(q.getAircraftModelId())))
                .toList();
    }

    static boolean isExpired(LocalDate expiry) {
        return expiry != null && expiry.isBefore(LocalDate.now());
    }

    private User requireRider(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getRole() != null && u.getRole() == ROLE_RIDER)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND));
    }

    /** 返回规范化后的身份证号；null 表示不修改。 */
    private static String validateIdNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = IdNumbers.normalize(raw);
        if (normalized.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "身份证号不能为空字符串");
        }
        if (!IdNumbers.isValid(normalized)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "身份证号格式或校验位不正确");
        }
        return normalized;
    }

    private static void validateExpiry(LocalDate expiry) {
        if (isExpired(expiry)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "证件有效期不能早于今天");
        }
    }
}
