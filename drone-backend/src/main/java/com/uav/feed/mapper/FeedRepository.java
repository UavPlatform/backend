package com.uav.feed.mapper;

import com.uav.feed.pojo.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedRepository extends JpaRepository<Feed, Long> {
    Page<Feed> findByUserIdOrderByCreateTimeDesc(Long userId, Pageable pageable);
    Page<Feed> findByUserIdOrderByLikeCountDescCreateTimeDesc(Long userId, Pageable pageable);
    Page<Feed> findAllByOrderByCreateTimeDesc(Pageable pageable);
    Page<Feed> findAllByOrderByLikeCountDescCreateTimeDesc(Pageable pageable);

    @Modifying
    @Query("UPDATE Feed f SET f.likeCount = f.likeCount + :delta WHERE f.id = :feedId")
    int updateLikeCount(@Param("feedId") Long feedId, @Param("delta") int delta);
}
