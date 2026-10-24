package com.uav.dispatch.mapper;

import com.uav.dispatch.pojo.entity.DispatchNotification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

public interface DispatchNotificationRepository extends JpaRepository<DispatchNotification, String> {
    long countByEventKeyAndAudience(String eventKey, String audience);
    Page<DispatchNotification> findByRecipientId(Long recipient, Pageable page);
    long countByRecipientIdAndReadAtIsNull(Long recipient);
    boolean existsByRecipientIdAndEventKey(Long recipient, String eventKey);
    boolean existsByIdAndRecipientId(String id, Long recipient);
    @Modifying(clearAutomatically = true)
    @Query("update DispatchNotification n set n.readAt = :now where n.id = :id and n.recipientId = :recipient and n.readAt is null")
    int markRead(@Param("recipient") Long recipient, @Param("id") String id, @Param("now") LocalDateTime now);
}
