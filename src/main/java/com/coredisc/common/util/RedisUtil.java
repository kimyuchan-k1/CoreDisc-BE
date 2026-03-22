package com.coredisc.common.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
@Service
public class RedisUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis에 key와 value 저장 (Redis 장애 시 무시 — 로그인은 성공, refresh만 불가)
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.warn("[RedisUtil] set 실패 (key={}): {}", key, e.getMessage());
        }
    }

    // 주어진 key로 Redis에서 저장된 문자열 값을 조회하여 반환 (Redis 장애 시 null 반환)
    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("[RedisUtil] get 실패 (key={}): {}", key, e.getMessage());
            return null;
        }
    }

    // Redis에 해당 key가 존재하는지 여부 확인 (Redis 장애 시 false 반환)
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("[RedisUtil] exists 실패 (key={}): {}", key, e.getMessage());
            return false;
        }
    }

    // 만료시간을 설정 (Redis 장애 시 무시)
    public void expire(String key, long timeout, TimeUnit unit) {
        try {
            redisTemplate.expire(key, timeout, unit);
        } catch (Exception e) {
            log.warn("[RedisUtil] expire 실패 (key={}): {}", key, e.getMessage());
        }
    }

    // Redis에서 key에 저장된 데이터 삭제 (Redis 장애 시 false 반환)
    public boolean delete(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.delete(key));
        } catch (Exception e) {
            log.warn("[RedisUtil] delete 실패 (key={}): {}", key, e.getMessage());
            return false;
        }
    }
}
