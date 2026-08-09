package com.uav.feed.controller;

import com.uav.feed.pojo.dto.CommentDto;
import com.uav.feed.pojo.dto.CreateFeedDto;
import com.uav.feed.pojo.dto.UpdateFeedDto;
import com.uav.feed.pojo.vo.CommentPageVO;
import com.uav.feed.pojo.vo.FeedCommentVO;
import com.uav.feed.pojo.vo.FeedPageVO;
import com.uav.feed.pojo.vo.FeedVO;
import com.uav.feed.service.FeedService;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rider/feed")
public class FeedController {

    @Autowired
    private FeedService feedService;

    @PostMapping
    public Result<FeedVO> create(@Valid @RequestBody CreateFeedDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success("发布成功", feedService.create(userId, dto));
    }

    @PutMapping("/{id}")
    public Result<FeedVO> update(@PathVariable Long id, @Valid @RequestBody UpdateFeedDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success("修改成功", feedService.update(id, userId, dto));
    }

    @GetMapping("/{id}")
    public Result<FeedVO> detail(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(feedService.getById(id, userId));
    }

    @GetMapping
    public Result<FeedPageVO> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") @Max(50) int size,
                                    @RequestParam(defaultValue = "latest") String sort,
                                    @RequestParam(required = false) Long userId) {
        Long currentUserId = UserContext.getUserId();
        Page<FeedVO> feedPage = feedService.list(currentUserId, page, size, sort, userId);
        return Result.success(FeedPageVO.builder()
                .feeds(feedPage.getContent())
                .currentPage(feedPage.getNumber())
                .totalPages(feedPage.getTotalPages())
                .totalElements(feedPage.getTotalElements())
                .build());
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        feedService.delete(id, userId);
        return Result.success("删除成功");
    }

    @PostMapping("/{id}/like")
    public Result<FeedVO> toggleLike(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        return Result.success(feedService.toggleLike(id, userId));
    }

    @GetMapping("/{id}/comments")
    public Result<CommentPageVO> listComments(@PathVariable Long id,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") @Max(50) int size) {
        Page<FeedCommentVO> commentPage = feedService.listComments(id, page, size);
        return Result.success(CommentPageVO.builder()
                .comments(commentPage.getContent())
                .currentPage(commentPage.getNumber())
                .totalPages(commentPage.getTotalPages())
                .totalElements(commentPage.getTotalElements())
                .build());
    }

    @PostMapping("/{id}/comments")
    public Result<FeedCommentVO> addComment(@PathVariable Long id, @Valid @RequestBody CommentDto dto) {
        Long userId = UserContext.getUserId();
        return Result.success(feedService.addComment(id, userId, dto));
    }

    @DeleteMapping("/{feedId}/comments/{commentId}")
    public Result<Void> deleteComment(@PathVariable Long feedId, @PathVariable Long commentId) {
        Long userId = UserContext.getUserId();
        feedService.deleteComment(feedId, commentId, userId);
        return Result.success("删除成功");
    }
}
