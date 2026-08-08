package com.uav.feed.service.impl;

import com.uav.feed.mapper.*;
import com.uav.feed.pojo.dto.CommentDto;
import com.uav.feed.pojo.dto.CreateFeedDto;
import com.uav.feed.pojo.dto.UpdateFeedDto;
import com.uav.feed.pojo.entity.*;
import com.uav.feed.pojo.vo.FeedCommentVO;
import com.uav.feed.pojo.vo.FeedVO;
import com.uav.feed.service.FeedService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.FeedMediaType;
import com.uav.server.exception.BusinessException;
import com.uav.upload.entity.UploadedFile;
import com.uav.upload.repository.UploadRepository;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private static final int MAX_MEDIA_COUNT = 9;
    private static final int MAX_PAGE_SIZE = 50;

    private final FeedRepository feedRepository;
    private final FeedLikeRepository likeRepository;
    private final FeedCommentRepository commentRepository;
    private final UploadRepository uploadRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public FeedVO create(Long userId, CreateFeedDto dto) {
        Feed feed = Feed.builder()
                .userId(userId)
                .content(dto.getContent())
                .build();

        if (dto.getMediaIds() != null && !dto.getMediaIds().isEmpty()) {
            if (dto.getMediaIds().size() > MAX_MEDIA_COUNT) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "最多上传" + MAX_MEDIA_COUNT + "个媒体文件");
            }
            List<UploadedFile> files = uploadRepository.findByUserIdAndIdIn(userId, dto.getMediaIds());
            if (files.size() != dto.getMediaIds().size()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_NOT_FOUND,
                        "部分文件不存在或无权访问");
            }
            List<FeedMedia> media = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                UploadedFile f = files.get(i);
                FeedMediaType type = isVideo(f.getFileSuffix()) ? FeedMediaType.VIDEO : FeedMediaType.IMAGE;
                media.add(FeedMedia.builder()
                        .feed(feed)
                        .mediaUrl(f.getFileUrl())
                        .mediaType(type)
                        .sortOrder(i)
                        .build());
            }
            feed.setMedia(media);
        }

        feedRepository.save(feed);

        String userName = userRepository.findById(userId).map(User::getUserName).orElse(null);
        return toVO(feed, userName, userId);
    }

    @Override
    @Transactional
    public FeedVO update(Long feedId, Long userId, UpdateFeedDto dto) {
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "动态不存在"));
        if (!feed.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED,
                    "只能编辑自己的动态");
        }

        if (dto.getContent() != null && !dto.getContent().isBlank()) {
            feed.setContent(dto.getContent());
        }

        if (dto.getMediaIds() != null) {
            if (dto.getMediaIds().size() > MAX_MEDIA_COUNT) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "最多上传" + MAX_MEDIA_COUNT + "个媒体文件");
            }
            feed.getMedia().clear();
            if (!dto.getMediaIds().isEmpty()) {
                List<UploadedFile> files = uploadRepository.findByUserIdAndIdIn(userId, dto.getMediaIds());
                if (files.size() != dto.getMediaIds().size()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.FILE_NOT_FOUND,
                            "部分文件不存在或无权访问");
                }
                for (int i = 0; i < files.size(); i++) {
                    UploadedFile f = files.get(i);
                    FeedMediaType type = isVideo(f.getFileSuffix()) ? FeedMediaType.VIDEO : FeedMediaType.IMAGE;
                    feed.getMedia().add(FeedMedia.builder()
                            .feed(feed)
                            .mediaUrl(f.getFileUrl())
                            .mediaType(type)
                            .sortOrder(i)
                            .build());
                }
            }
        }

        feedRepository.save(feed);

        String userName = userRepository.findById(userId).map(User::getUserName).orElse(null);
        return toVO(feed, userName, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedVO getById(Long feedId, Long currentUserId) {
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "动态不存在"));
        String userName = userRepository.findById(feed.getUserId()).map(User::getUserName).orElse(null);
        return toVO(feed, userName, currentUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FeedVO> list(Long currentUserId, int page, int size, String sort, Long targetUserId) {
        size = Math.min(size, MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, size);
        Page<Feed> feeds;
        if (targetUserId != null) {
            feeds = "popular".equals(sort)
                    ? feedRepository.findByUserIdOrderByLikeCountDescCreateTimeDesc(targetUserId, pageable)
                    : feedRepository.findByUserIdOrderByCreateTimeDesc(targetUserId, pageable);
        } else {
            feeds = "popular".equals(sort)
                    ? feedRepository.findAllByOrderByLikeCountDescCreateTimeDesc(pageable)
                    : feedRepository.findAllByOrderByCreateTimeDesc(pageable);
        }

        List<Long> feedIds = feeds.stream().map(Feed::getId).toList();

        Set<Long> likedFeedIds;
        Map<Long, Long> commentCounts;

        if (!feedIds.isEmpty()) {
            likedFeedIds = likeRepository.findByFeedIdInAndUserId(feedIds, currentUserId)
                    .stream().map(FeedLike::getFeedId).collect(Collectors.toSet());
            commentCounts = commentRepository.countGroupByFeedIdIn(feedIds).stream()
                    .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        } else {
            likedFeedIds = Set.of();
            commentCounts = Map.of();
        }

        Map<Long, String> userNames = new HashMap<>();

        return feeds.map(feed -> {
            String userName = userNames.computeIfAbsent(feed.getUserId(),
                    uid -> userRepository.findById(uid).map(User::getUserName).orElse(null));
            return toVO(feed, userName, likedFeedIds.contains(feed.getId()), commentCounts);
        });
    }

    @Override
    @Transactional
    public void delete(Long feedId, Long userId) {
        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "动态不存在"));
        if (!feed.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED,
                    "只能删除自己的动态");
        }
        likeRepository.deleteByFeedId(feedId);
        commentRepository.deleteByFeedId(feedId);
        feedRepository.delete(feed);
    }

    @Override
    @Transactional
    public FeedVO toggleLike(Long feedId, Long userId) {
        if (!feedRepository.existsById(feedId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                    "动态不存在");
        }

        Optional<FeedLike> existing = likeRepository.findByFeedIdAndUserId(feedId, userId);
        if (existing.isPresent()) {
            likeRepository.delete(existing.get());
        } else {
            likeRepository.save(FeedLike.builder()
                    .feedId(feedId)
                    .userId(userId)
                    .build());
        }

        feedRepository.updateLikeCount(feedId, existing.isPresent() ? -1 : 1);

        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "动态不存在"));
        String userName = userRepository.findById(feed.getUserId()).map(User::getUserName).orElse(null);
        FeedVO vo = toVO(feed, userName, userId);
        vo.setLiked(existing.isEmpty());
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FeedCommentVO> listComments(Long feedId, int page, int size) {
        size = Math.min(size, MAX_PAGE_SIZE);
        Page<FeedComment> comments = commentRepository
                .findByFeedIdOrderByCreateTimeAsc(feedId, PageRequest.of(page, size));
        Map<Long, String> userNames = new HashMap<>();
        return comments.map(c -> {
            String name = userNames.computeIfAbsent(c.getUserId(),
                    uid -> userRepository.findById(uid).map(User::getUserName).orElse(null));
            return FeedCommentVO.builder()
                    .id(c.getId())
                    .userId(c.getUserId())
                    .userName(name)
                    .content(c.getContent())
                    .parentId(c.getParentId())
                    .createTime(c.getCreateTime())
                    .build();
        });
    }

    @Override
    @Transactional
    public FeedCommentVO addComment(Long feedId, Long userId, CommentDto dto) {
        feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "动态不存在"));

        if (dto.getParentId() != null) {
            FeedComment parent = commentRepository.findById(dto.getParentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ROUTE_NOT_FOUND,
                            "父评论不存在"));
            if (!parent.getFeedId().equals(feedId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "父评论不属于该动态");
            }
        }

        FeedComment comment = FeedComment.builder()
                .feedId(feedId)
                .userId(userId)
                .content(dto.getContent())
                .parentId(dto.getParentId())
                .build();
        commentRepository.save(comment);

        String userName = userRepository.findById(userId).map(User::getUserName).orElse(null);
        return FeedCommentVO.builder()
                .id(comment.getId())
                .userId(userId)
                .userName(userName)
                .content(comment.getContent())
                .parentId(comment.getParentId())
                .createTime(comment.getCreateTime())
                .build();
    }

    @Override
    @Transactional
    public void deleteComment(Long feedId, Long commentId, Long userId) {
        FeedComment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                        "评论不存在"));
        if (!comment.getFeedId().equals(feedId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                    "评论不属于该动态");
        }
        if (!comment.getUserId().equals(userId)) {
            Feed feed = feedRepository.findById(feedId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND,
                            "动态不存在"));
            if (!feed.getUserId().equals(userId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.FILE_ACCESS_DENIED,
                        "无权删除该评论");
            }
        }
        commentRepository.delete(comment);
    }

    private FeedVO toVO(Feed feed, String userName, Long currentUserId) {
        boolean liked = likeRepository.findByFeedIdAndUserId(feed.getId(), currentUserId).isPresent();
        return toVO(feed, userName, liked);
    }

    private FeedVO toVO(Feed feed, String userName, boolean liked) {
        Map<Long, Long> commentCounts = Map.of(feed.getId(), commentRepository.countByFeedId(feed.getId()));
        return toVO(feed, userName, liked, commentCounts);
    }

    private FeedVO toVO(Feed feed, String userName, boolean liked,
                         Map<Long, Long> commentCounts) {
        long commentCount = commentCounts.getOrDefault(feed.getId(), 0L);

        List<FeedVO.MediaEntry> media = feed.getMedia() != null
                ? feed.getMedia().stream()
                    .map(m -> FeedVO.MediaEntry.builder()
                            .id(m.getId())
                            .url(m.getMediaUrl())
                            .type(m.getMediaType().name())
                            .build())
                    .toList()
                : List.of();

        return FeedVO.builder()
                .id(feed.getId())
                .userId(feed.getUserId())
                .userName(userName)
                .content(feed.getContent())
                .media(media)
                .likeCount(feed.getLikeCount())
                .commentCount(commentCount)
                .liked(liked)
                .createTime(feed.getCreateTime())
                .updateTime(feed.getUpdateTime())
                .build();
    }

    private static boolean isVideo(String suffix) {
        if (suffix == null) return false;
        return switch (suffix.toLowerCase()) {
            case "mp4", "mov", "avi", "mkv", "flv" -> true;
            default -> false;
        };
    }
}
