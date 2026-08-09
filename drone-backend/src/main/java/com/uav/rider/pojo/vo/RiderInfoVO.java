package com.uav.rider.pojo.vo;

import com.uav.rider.pojo.entity.Rider;
import com.uav.rider.pojo.entity.RiderAircraftQualification;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class RiderInfoVO {
    private Long id;
    private String userName;
    private Integer age;
    private String certNumber;
    private LocalDate certValidFrom;
    private LocalDate certValidUntil;
    private String location;
    private String selfIntroduction;
    private List<QualificationEntry> qualifications;
    private long todayOrders;
    private long totalCompleted;
    private Double totalEarnings;

    @Data
    @Builder
    public static class QualificationEntry {
        private Long id;
        private String category;
        private String license;
        private String weight;
    }

    public static RiderInfoVO from(Rider rider, String userName,
                                   List<RiderAircraftQualification> qualifications,
                                   long todayOrders, long totalCompleted, Double totalEarnings) {
        List<QualificationEntry> entries = qualifications.stream()
                .map(q -> QualificationEntry.builder()
                        .id(q.getId())
                        .category(q.getCategory() != null ? q.getCategory().name() : null)
                        .license(q.getLicense() != null ? q.getLicense().name() : null)
                        .weight(q.getWeight() != null ? q.getWeight().name() : null)
                        .build())
                .toList();

        return RiderInfoVO.builder()
                .id(rider.getId())
                .userName(userName)
                .age(rider.getAge())
                .certNumber(rider.getCertNumber())
                .certValidFrom(rider.getCertValidFrom())
                .certValidUntil(rider.getCertValidUntil())
                .location(rider.getLocation())
                .selfIntroduction(rider.getSelfIntroduction())
                .qualifications(entries)
                .todayOrders(todayOrders)
                .totalCompleted(totalCompleted)
                .totalEarnings(totalEarnings)
                .build();
    }
}
