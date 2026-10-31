package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.EquipmentNotification;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EquipmentNotificationRepository extends JpaRepository<EquipmentNotification, String> {
    boolean existsByRecipientIdAndAudienceAndEventKey(Long recipientId, String audience, String eventKey);
    Page<EquipmentNotification> findByRecipientIdAndAudience(Long recipientId, String audience, Pageable page);
    long countByRecipientIdAndAudienceAndReadAtIsNull(Long recipientId, String audience);
    boolean existsByIdAndRecipientIdAndAudience(String id, Long recipientId, String audience);

    @Modifying(clearAutomatically = true)
    @Query("update EquipmentNotification n set n.readAt = :now where n.id = :id and n.recipientId = :recipient "
            + "and n.audience = :audience and n.readAt is null")
    int markRead(@Param("recipient") Long recipient, @Param("audience") String audience,
                 @Param("id") String id, @Param("now") java.time.LocalDateTime now);
}
