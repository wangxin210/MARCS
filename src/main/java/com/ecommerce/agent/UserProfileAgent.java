package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.ProfileCacheService;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户画像Agent
 */
@Component
public class UserProfileAgent extends BaseAgent {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT =
            "你是一个电商用户画像分析专家。根据用户的行为数据,分析用户特征并生成画像。\n" +
                    "输出JSON格式:\n" +
                    "{\"segments\":[\"active\"],\n" +
                    "\"preferred_categories\":[\"手机\"],\n" +
                    "\"price_range\":[0,10000],\n" +
                    "\"rfm_score\":{\"recency\":0.8,\"frequency\":0.5,\"monetary\":0.6},\n" +
                    "\"real_time_tags\":{\"活跃时段\":\"晚间\", \"兴趣偏好\":\"数码产品\", \"消费能力\":\"中高\"}}\n" +
                    "只输出JSON。";
    private final ProfileCacheService cacheService;

    @Autowired
    public UserProfileAgent(ChatClient.Builder chatClientBuilder, ProfileCacheService cacheService) {
        super("user_profile", 5.0, 2);
        this.chatClient = chatClientBuilder.build(); // 注入在 config 包里配置好的大模型客户端
        this.cacheService = cacheService;
    }

    @Override
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        String userId = (String) params.get("userId");
        Map<String, Object> behavior = collectBehavior(userId, params);

        String response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user("用户ID: " + userId + "\n行为数据: " + objectMapper.writeValueAsString(behavior))
                .call()
                .content();

        UserProfile profile = parseProfile(userId, response);

        Map<String, Object> data = new HashMap<>();
        data.put("raw_analysis", response);
        data.put("profile", profile);

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(0.85)
                .build();
    }

    private Map<String, Object> collectBehavior(String userId, Map<String, Object> params) {
        Map<String, Object> behavior = new HashMap<>();
        behavior.put("user_id", userId);
        // 从 Redis ZSet 获取真实行为日志
        Set<String> logs = cacheService.getRecentBehaviors(userId);

        if (logs == null || logs.isEmpty()) {
            behavior.put("recent_views", List.of("热门商品"));
            behavior.put("purchase_count_30d", 0);
            behavior.put("note", "New user with no history");
        } else {
            List<String> formattedLogs = logs.stream()
                    .map(log -> log.replace(":", " -> "))
                    .collect(Collectors.toList());

            behavior.put("recent_behavior_logs", formattedLogs);
            behavior.put("total_interactions", logs.size());
        }
        return behavior;
    }

    @SuppressWarnings("unchecked")
    private UserProfile parseProfile(String userId, String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            Map<String, Object> data = objectMapper.readValue(cleaned, Map.class);
            List<String> segments = (List<String>) data.getOrDefault("segments", List.of("active"));
            List<String> categories = (List<String>) data.getOrDefault("preferred_categories", List.of());
            List<?> priceRaw = (List<?>) data.getOrDefault("price_range", List.of(0, 10000));
            Map<String, Double> rfm = (Map<String, Double>) data.getOrDefault("rfm_score", Map.of());
            Map<String, Object> tags = (Map<String, Object>) data.getOrDefault("real_time_tags", Map.of());

            return UserProfile.builder()
                    .userId(userId)
                    .segments(segments)
                    .preferredCategories(categories)
                    .priceRange(new double[]{
                            ((Number) priceRaw.get(0)).doubleValue(),
                            priceRaw.size() > 1 ? ((Number) priceRaw.get(1)).doubleValue() : 10000
                    })
                    .rfmScore(rfm)
                    .realTimeTags(tags)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse profile for {}: {}", userId, e.getMessage());
            return UserProfile.builder()
                    .userId(userId)
                    .segments(List.of("active"))
                    .build();
        }
    }
}
