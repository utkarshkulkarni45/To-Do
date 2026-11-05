package com.todo.app.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * A bounded in-process cache backs the task stats endpoint. It is per instance, which is correct only
 * while the application runs on one; with more instances the cache would show one instance's view, so
 * Redis would replace it as the store behind the same Spring cache abstraction.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TASK_STATS_CACHE = "taskStats";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(120, TimeUnit.SECONDS));
        return manager;
    }
}
