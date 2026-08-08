package com.uav.feed.pojo.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.uav.server.enums.FeedMediaType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "feed_media")
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMedia {

    @Setter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feed_id", nullable = false)
    private Feed feed;

    @Setter
    @Column(name = "media_url", nullable = false, length = 512)
    private String mediaUrl;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 16)
    private FeedMediaType mediaType;

    @Setter
    @Column(name = "sort_order")
    private Integer sortOrder;

    public Long getId() { return id; }
    public Feed getFeed() { return feed; }
    public Long getFeedId() { return feed != null ? feed.getId() : null; }
    public String getMediaUrl() { return mediaUrl; }
    public FeedMediaType getMediaType() { return mediaType; }
    public Integer getSortOrder() { return sortOrder; }
}
