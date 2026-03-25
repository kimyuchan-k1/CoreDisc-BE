package com.coredisc.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                buildCache("followingIds", 5000, 5, TimeUnit.MINUTES),
                buildCache("circleIds", 5000, 5, TimeUnit.MINUTES),
                buildCache("userDetails", 10000, 10, TimeUnit.MINUTES)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, int maxSize, long duration, TimeUnit unit) {
        return new CaffeineCache(name, Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(duration, unit)
                .recordStats()
                .build());
    }
}
