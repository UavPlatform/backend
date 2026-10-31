package com.uav.equipment.mapper;
import com.uav.equipment.pojo.entity.EquipmentNotification;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface EquipmentNotificationRepository extends JpaRepository<EquipmentNotification, String> {
    boolean existsByRecipientIdAndEventKey(Long recipientId, String eventKey);
    Page<EquipmentNotification> findByRecipientId(Long recipientId, Pageable page);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
    boolean existsByIdAndRecipientId(String id, Long recipientId);

    @Modifying(clearAutomatically = true)
    @Query("update EquipmentNotification n set n.readAt = :now where n.id = :id and n.recipientId = :recipient and n.readAt is null")
    int markRead(@Param("recipient") Long recipient, @Param("id") String id, @Param("now") java.time.LocalDateTime now);
}
