package com.uav.feed.mapper;

import com.uav.feed.pojo.entity.FeedComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {
    List<FeedComment> findByFeedIdOrderByCreateTimeAsc(Long feedId);
    Page<FeedComment> findByFeedIdOrderByCreateTimeAsc(Long feedId, Pageable pageable);
    long countByFeedId(Long feedId);

    @Query("SELECT c.feedId, COUNT(c) FROM FeedComment c WHERE c.feedId IN :feedIds GROUP BY c.feedId")
    List<Object[]> countGroupByFeedIdIn(@Param("feedIds") List<Long> feedIds);

    void deleteByFeedId(Long feedId);
}
