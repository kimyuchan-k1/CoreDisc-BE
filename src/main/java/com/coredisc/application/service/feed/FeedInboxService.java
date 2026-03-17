package com.coredisc.application.service.feed;

import com.coredisc.config.RedisCircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis Sorted Set 기반 피드 인박스 서비스.
 *
 * key: feed:inbox:{memberId}:{feedType}  (feedType = ALL | CORE)
 * score = postId (auto-increment PK → 시간순 보장)
 * member = postId (문자열)
 *
 * 7일 TTL + ZREMRANGEBYRANK로 500개 cap 관리.
 * Circuit Breaker로 Redis 장애 시 빠르게 fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedInboxService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisCircuitBreaker circuitBreaker;

    private static final String INBOX_KEY_PREFIX = "feed:inbox:";
    private static final int INBOX_MAX_SIZE = 500;
    private static final long INBOX_TTL_DAYS = 7;

    /**
     * 인박스에서 최신 postId 목록 조회 (첫 페이지)
     */
    public List<Long> getInbox(Long memberId, String feedType, int size) {
        if (!circuitBreaker.isAvailable()) return Collections.emptyList();
        try {
            String key = buildKey(memberId, feedType);
            Set<Object> result = redisTemplate.opsForZSet().reverseRange(key, 0, size - 1);
            refreshTtl(key);
            circuitBreaker.recordSuccess();
            if (result == null || result.isEmpty()) {
                return Collections.emptyList();
            }
            return result.stream()
                    .map(this::toLong)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox get failed: memberId={}, feedType={}, error={}", memberId, feedType, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 커서 기반 페이지네이션 (cursor보다 작은 postId 조회)
     */
    public List<Long> getInboxByCursor(Long memberId, String feedType, Long cursor, int size) {
        if (!circuitBreaker.isAvailable()) return Collections.emptyList();
        try {
            String key = buildKey(memberId, feedType);
            Set<Object> result = redisTemplate.opsForZSet()
                    .reverseRangeByScore(key, Double.NEGATIVE_INFINITY, cursor - 1, 0, size);
            refreshTtl(key);
            circuitBreaker.recordSuccess();
            if (result == null || result.isEmpty()) {
                return Collections.emptyList();
            }
            return result.stream()
                    .map(this::toLong)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox cursor get failed: memberId={}, cursor={}, error={}", memberId, cursor, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 인박스에 postId 추가 (단건)
     */
    public void addToInbox(Long memberId, String feedType, Long postId) {
        if (!circuitBreaker.isAvailable()) return;
        try {
            String key = buildKey(memberId, feedType);
            redisTemplate.opsForZSet().add(key, postId.toString(), postId.doubleValue());
            refreshTtl(key);
            trimInbox(key);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox add failed: memberId={}, postId={}, error={}", memberId, postId, e.getMessage());
        }
    }

    /**
     * 여러 팔로워 인박스에 postId 추가 (Pipeline)
     */
    public void addToInboxes(List<Long> memberIds, String feedType, Long postId) {
        if (memberIds.isEmpty()) return;
        if (!circuitBreaker.isAvailable()) return;
        try {
            RedisCallback<Object> callback = (connection) -> {
                byte[] scoreBytes = String.valueOf(postId).getBytes();
                for (Long memberId : memberIds) {
                    String key = buildKey(memberId, feedType);
                    connection.zSetCommands().zAdd(
                            key.getBytes(),
                            postId.doubleValue(),
                            scoreBytes
                    );
                }
                return null;
            };
            redisTemplate.executePipelined(callback);
            for (Long memberId : memberIds) {
                String key = buildKey(memberId, feedType);
                refreshTtl(key);
                trimInbox(key);
            }
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox batch add failed: postId={}, followerCount={}, error={}",
                    postId, memberIds.size(), e.getMessage());
            throw e;
        }
    }

    /**
     * 인박스에서 postId 제거 (단건)
     */
    public void removeFromInbox(Long memberId, String feedType, Long postId) {
        if (!circuitBreaker.isAvailable()) return;
        try {
            String key = buildKey(memberId, feedType);
            redisTemplate.opsForZSet().remove(key, postId.toString());
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox remove failed: memberId={}, postId={}, error={}", memberId, postId, e.getMessage());
        }
    }

    /**
     * 여러 팔로워 인박스에서 postId 제거 (Pipeline)
     */
    public void removeFromInboxes(List<Long> memberIds, String feedType, Long postId) {
        if (memberIds.isEmpty()) return;
        if (!circuitBreaker.isAvailable()) return;
        try {
            RedisCallback<Object> callback = (connection) -> {
                byte[] memberBytes = postId.toString().getBytes();
                for (Long memberId : memberIds) {
                    String key = buildKey(memberId, feedType);
                    connection.zSetCommands().zRem(key.getBytes(), memberBytes);
                }
                return null;
            };
            redisTemplate.executePipelined(callback);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox batch remove failed: postId={}, error={}", postId, e.getMessage());
            throw e;
        }
    }

    /**
     * 인박스에서 특정 작성자의 게시글들 제거 (Unfollow/Block 시)
     */
    public void removePostsFromInbox(Long memberId, String feedType, List<Long> postIds) {
        if (postIds.isEmpty()) return;
        if (!circuitBreaker.isAvailable()) return;
        try {
            String key = buildKey(memberId, feedType);
            Object[] members = postIds.stream()
                    .map(id -> (Object) id.toString())
                    .toArray();
            redisTemplate.opsForZSet().remove(key, members);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox remove posts failed: memberId={}, count={}, error={}",
                    memberId, postIds.size(), e.getMessage());
        }
    }

    /**
     * 인박스에 여러 postId 추가 (Follow backfill용)
     */
    public void addPostsToInbox(Long memberId, String feedType, List<Long> postIds) {
        if (postIds.isEmpty()) return;
        if (!circuitBreaker.isAvailable()) return;
        try {
            String key = buildKey(memberId, feedType);
            Set<ZSetOperations.TypedTuple<Object>> tuples = new HashSet<>();
            for (Long id : postIds) {
                tuples.add(new DefaultTypedTuple<>(id.toString(), id.doubleValue()));
            }
            redisTemplate.opsForZSet().add(key, tuples);
            refreshTtl(key);
            trimInbox(key);
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Feed inbox bulk add failed: memberId={}, count={}, error={}",
                    memberId, postIds.size(), e.getMessage());
        }
    }

    /**
     * 인박스 존재 여부 확인
     */
    public boolean hasInbox(Long memberId, String feedType) {
        if (!circuitBreaker.isAvailable()) return false;
        try {
            String key = buildKey(memberId, feedType);
            Long size = redisTemplate.opsForZSet().zCard(key);
            circuitBreaker.recordSuccess();
            return size != null && size > 0;
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return false;
        }
    }

    /**
     * 인박스 크기를 INBOX_MAX_SIZE로 제한
     */
    private void trimInbox(String key) {
        try {
            Long size = redisTemplate.opsForZSet().zCard(key);
            if (size != null && size > INBOX_MAX_SIZE) {
                // 오래된 항목 제거: rank 0부터 (size - INBOX_MAX_SIZE - 1)까지
                redisTemplate.opsForZSet().removeRange(key, 0, size - INBOX_MAX_SIZE - 1);
            }
        } catch (Exception e) {
            log.warn("Feed inbox trim failed: key={}, error={}", key, e.getMessage());
        }
    }

    private void refreshTtl(String key) {
        try {
            redisTemplate.expire(key, INBOX_TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            // TTL 갱신 실패는 무시 — 다음 접근 시 재시도
        }
    }

    private String buildKey(Long memberId, String feedType) {
        return INBOX_KEY_PREFIX + memberId + ":" + feedType;
    }

    private Long toLong(Object obj) {
        if (obj instanceof Number) {
            return ((Number) obj).longValue();
        }
        return Long.parseLong(obj.toString());
    }
}
