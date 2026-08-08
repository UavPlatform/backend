package com.uav.feed.mapper;

import com.uav.feed.pojo.entity.FeedLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    Optional<FeedLike> findByFeedIdAndUserId(Long feedId, Long userId);
    long countByFeedIdAndDeletedFalse(Long feedId);
    List<FeedLike> findByFeedIdInAndUserIdAndDeletedFalse(List<Long> feedIds, Long userId);

    @Query("SELECT l.feedId, COUNT(l) FROM FeedLike l WHERE l.feedId IN :feedIds AND l.deleted = false GROUP BY l.feedId")
    List<Object[]> countGroupByFeedIdIn(@Param("feedIds") List<Long> feedIds);

    void deleteByFeedId(Long feedId);
}
