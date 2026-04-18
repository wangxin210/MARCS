package com.ecommerce.service;

import com.ecommerce.model.UserProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.Set;

@Service
public class ProfileCacheService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String KEY_PREFIX = "agent:profile:";
    private static final long EXPIRE_HOURS = 24;

    public ProfileCacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public UserProfile getFromCache(String userId) {
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (json != null) {
                return objectMapper.readValue(json, UserProfile.class);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    public void saveToCache(String userId, UserProfile profile) {
        try {
            String json = objectMapper.writeValueAsString(profile);
            redisTemplate.opsForValue().set(KEY_PREFIX + userId, json, EXPIRE_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
    }

    public void recordUserBehavior(String userId, String action, String targetId) {
        try {
            String key = KEY_PREFIX + "behavior:" + userId;
            double score = System.currentTimeMillis();
            String member = action + ":" + targetId;
            redisTemplate.opsForZSet().add(key, member, score);
            Long removed = redisTemplate.opsForZSet().removeRange(key, 0, -101);
            redisTemplate.expire(key, 30, TimeUnit.DAYS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Set<String> getRecentBehaviors(String userId) {
        String key = KEY_PREFIX + "behavior:" + userId;
        return redisTemplate.opsForZSet().reverseRange(key, 0, 49);
    }

    public Set<String> getBehaviorsInLastHour(String userId) {
        String key = KEY_PREFIX + "behavior:" + userId;
        double now = System.currentTimeMillis();
        double oneHourAgo = now - 3600 * 1000;
        return redisTemplate.opsForZSet().rangeByScore(key, oneHourAgo, now);
    }
}

