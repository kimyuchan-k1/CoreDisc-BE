package com.coredisc.application.service.feed;

import com.coredisc.config.RedisCircuitBreaker;
import com.coredisc.presentation.dto.post.PostResponseDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 피드 캐시 서비스
 *
 * - Cache Aside 패턴: 캐시 미스 시 DB 조회 후 캐시 저장
 * - Thundering Herd 방어: SETNX 기반 분산 락으로 동시 DB 조회 방지
 * - Graceful Fallback: Redis 장애 시 DB 직접 조회
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final RedisCircuitBreaker circuitBreaker;

    private static final String FEED_KEY_PREFIX = "feed:";
    private static final String LOCK_KEY_PREFIX = "feed:lock:";
    private static final long FEED_TTL_SECONDS = 30;
    private static final long LOCK_TTL_SECONDS = 5;
    private static final int LOCK_WAIT_MAX_RETRIES = 10;
    private static final long LOCK_WAIT_INTERVAL_MS = 50;

    /**
     * 피드 캐시 조회. 캐시 히트 시 즉시 반환, 미스 시 null 반환.
     */
    public PostResponseDTO.PostFeedResponseDTO get(Long memberId, String feedType) {
        if (!circuitBreaker.isAvailable()) return null;
        try {
            String key = buildKey(memberId, feedType);
            Object raw = redisTemplate.opsForValue().get(key);
            circuitBreaker.recordSuccess();
            if (raw == null) return null;
            return objectMapper.readValue(raw.toString(), PostResponseDTO.PostFeedResponseDTO.class);
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Redis feed cache get failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 피드 캐시 저장 (TTL 30초)
     */
    public void put(Long memberId, String feedType, PostResponseDTO.PostFeedResponseDTO feed) {
        if (!circuitBreaker.isAvailable()) return;
        try {
            String key = buildKey(memberId, feedType);
            String json = objectMapper.writeValueAsString(feed);
            redisTemplate.opsForValue().set(key, json, FEED_TTL_SECONDS, TimeUnit.SECONDS);
            circuitBreaker.recordSuccess();
        } catch (JsonProcessingException e) {
            log.warn("Redis feed cache put serialization failed: {}", e.getMessage());
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Redis feed cache put failed: {}", e.getMessage());
        }
    }

    /**
     * Thundering Herd 방어: 분산 락 획득 시도
     * 캐시 만료 시 여러 스레드가 동시에 DB를 조회하는 것을 방지.
     * 락을 획득한 스레드만 DB 조회 → 나머지는 캐시 재조회 후 반환.
     *
     * @return true면 이 스레드가 DB 조회 담당, false면 다른 스레드가 이미 로딩 중
     */
    public boolean tryLock(Long memberId, String feedType) {
        try {
            String lockKey = LOCK_KEY_PREFIX + memberId + ":" + feedType;
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(lockKey, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 장애 시 락 없이 진행 (DB 조회 허용)
            return true;
        }
    }

    /**
     * 분산 락 해제
     */
    public void unlock(Long memberId, String feedType) {
        try {
            String lockKey = LOCK_KEY_PREFIX + memberId + ":" + feedType;
            redisTemplate.delete(lockKey);
        } catch (Exception e) {
            // 무시 — TTL로 자동 해제됨
        }
    }

    /**
     * 락 대기: 다른 스레드가 캐시를 채울 때까지 대기 후 재조회.
     * 최대 500ms(50ms × 10회) 대기 후 캐시 미스면 직접 DB 조회.
     */
    public PostResponseDTO.PostFeedResponseDTO waitAndGet(Long memberId, String feedType) {
        for (int i = 0; i < LOCK_WAIT_MAX_RETRIES; i++) {
            try {
                Thread.sleep(LOCK_WAIT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }

            PostResponseDTO.PostFeedResponseDTO cached = get(memberId, feedType);
            if (cached != null) return cached;
        }
        // 대기 시간 초과 → null 반환 (호출자가 직접 DB 조회)
        return null;
    }

    /**
     * 피드 캐시 무효화 (글 작성/삭제 시)
     */
    public void evict(Long memberId) {
        if (!circuitBreaker.isAvailable()) return;
        try {
            redisTemplate.delete(buildKey(memberId, "ALL"));
            redisTemplate.delete(buildKey(memberId, "CORE"));
            circuitBreaker.recordSuccess();
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            log.warn("Redis feed cache evict failed: {}", e.getMessage());
        }
    }

    private String buildKey(Long memberId, String feedType) {
        return FEED_KEY_PREFIX + memberId + ":" + feedType;
    }
}
