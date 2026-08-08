package com.uav.feed.service;

import com.uav.feed.pojo.dto.CommentDto;
import com.uav.feed.pojo.dto.CreateFeedDto;
import com.uav.feed.pojo.dto.UpdateFeedDto;
import com.uav.feed.pojo.vo.FeedCommentVO;
import com.uav.feed.pojo.vo.FeedVO;
import org.springframework.data.domain.Page;

public interface FeedService {
    FeedVO create(Long userId, CreateFeedDto dto);
    FeedVO update(Long feedId, Long userId, UpdateFeedDto dto);
    FeedVO getById(Long feedId, Long currentUserId);
    Page<FeedVO> list(Long currentUserId, int page, int size, String sort, Long targetUserId);
    void delete(Long feedId, Long userId);
    FeedVO toggleLike(Long feedId, Long userId);
    Page<FeedCommentVO> listComments(Long feedId, int page, int size);
    FeedCommentVO addComment(Long feedId, Long userId, CommentDto dto);
    void deleteComment(Long feedId, Long commentId, Long userId);
}
