package com.coredisc.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.util.Collections;

/**
 * Caffeine 캐시 메트릭을 Prometheus에 노출.
 * cache.gets{result=hit|miss}, cache.puts, cache.evictions 등 자동 바인딩.
 */
@Configuration
public class CacheMetricsConfig {

    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;

    public CacheMetricsConfig(CacheManager cacheManager, MeterRegistry meterRegistry) {
        this.cacheManager = cacheManager;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bindCacheMetrics() {
        for (String cacheName : cacheManager.getCacheNames()) {
            org.springframework.cache.Cache springCache = cacheManager.getCache(cacheName);
            if (springCache instanceof CaffeineCache caffeineCache) {
                CaffeineCacheMetrics.monitor(
                        meterRegistry,
                        caffeineCache.getNativeCache(),
                        cacheName,
                        Collections.emptyList()
                );
            }
        }
    }
}
