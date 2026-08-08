package com.uav.feed.pojo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "rider_feed_like", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"feed_id", "user_id"})
})
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedLike {

    @Setter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "feed_id", nullable = false)
    private Long feedId;

    @Setter
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getFeedId() { return feedId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getCreateTime() { return createTime; }
}
