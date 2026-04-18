package com.ecommerce.config;

import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.UserProfile;
import com.ecommerce.model.Product;
import com.ecommerce.orchestrator.SupervisorOrchestrator;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ecommerce.service.ProfileCacheService;
import com.ecommerce.service.ABTestService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;

import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final SupervisorOrchestrator orchestrator;
    private final ABTestService abTestService;
    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);
    private final UserProfileAgent userProfileAgent;
    private final InventoryAgent inventoryAgent;
    private final ProductRecAgent productRecAgent;
    private final ProfileCacheService profileCacheService;

    public RecommendationController(SupervisorOrchestrator orchestrator, ABTestService abTestService,
                                    UserProfileAgent userProfileAgent, InventoryAgent inventoryAgent,
                                    ProductRecAgent productRecAgent, ProfileCacheService profileCacheService) {
        this.orchestrator = orchestrator;
        this.abTestService = abTestService;
        this.userProfileAgent = userProfileAgent;
        this.inventoryAgent = inventoryAgent;
        this.productRecAgent = productRecAgent;
        this.profileCacheService = profileCacheService;
    }

    @PostMapping("/recommend")
    public RecommendationResponse recommend(@RequestBody RecommendationRequest request) {
        return orchestrator.recommend(request);
    }

    @GetMapping("/profile/{userId}")
    public UserProfile getUserProfile(@PathVariable String userId) {
        UserProfile cachedProfile = profileCacheService.getFromCache(userId);
        if (cachedProfile != null) {
            log.info("Hit cache for user profile: {}", userId);
            return cachedProfile;
        }
        log.info("Cache miss, calling UserProfileAgent for: {}", userId);
        try {
            var result = userProfileAgent.runAsync(Map.of("userId", userId)).join();
            if (result.getData() != null && result.getData().containsKey("profile")) {
                UserProfile profile = (UserProfile) result.getData().get("profile");
                profileCacheService.saveToCache(userId, profile);
                return profile;
            }
        } catch (Exception e) {
            log.error("Failed to get profile for user: {}", userId, e);
        }
        return new UserProfile();
    }

    @PostMapping("/behavior/record")
    public Map<String, String> recordBehavior(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String action = request.getOrDefault("action", "view");
        String target = request.getOrDefault("target", "unknown_item");

        profileCacheService.recordUserBehavior(userId, action, target);
        log.info("Recorded behavior: {} -> {} for user {}", action, target, userId);

        return Map.of("status", "success", "message", "Behavior recorded in Redis ZSet");
    }

    @GetMapping("/inventory/snapshot")
    @SuppressWarnings("unchecked")
    public List<Product> getInventorySnapshot() {
        try {
            // 简单模拟通过推荐Agent 获取一些基础商品，实际项目中注入一个 ProductService 从数据库查所有商品
            var recResult = productRecAgent.runAsync(Map.of("numItems", 100)).join();
            List<Product> allProducts = (List<Product>) recResult.getData().get("products");

            if (allProducts == null || allProducts.isEmpty()) {
                return List.of();
            }
            var invResult = inventoryAgent.runAsync(Map.of("products", allProducts)).join();
            return allProducts;

        } catch (Exception e) {
            log.error("Failed to get inventory snapshot", e);
        }
        return List.of();
    }

    @GetMapping("/abtest/config")
    public Map<String, Object> getABTestConfig() {
        return Map.of(
                "experiment_name", "Rec_Strategy_Test",
                "groups", List.of("control", "treatment_llm"),
                "traffic_split", "50% / 50%",
                "status", "running"
        );
    }

    @PostMapping("/abtest/assign")
    public Map<String, Object> assignUserGroup(@RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        // 简单模拟直接返回分配结果，实际项目中更新数据库或 Redis 中的用户分组记录
        return abTestService.assign(userId);
    }

    @GetMapping("/abtest/metrics")
    public Map<String, Object> getABTestMetrics() {
        // 模拟数据，实际项目中查询数据库中的用户行为日志
        return Map.of(
                "control", Map.of(
                        "users", 1250,
                        "ctr", 0.12, // 12%
                        "cvr", 0.03, // 3%
                        "latency_ms", 1800
                ),
                "treatment_llm", Map.of(
                        "users", 1248,
                        "ctr", 0.18,
                        "cvr", 0.05,
                        "latency_ms", 2100
                )
        );
    }


    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy", "language", "java");
    }

    @GetMapping("/experiments")
    public Map<String, Object> getExperiments() {
        return Map.of(
                "rec_strategy", Map.of(
                        "name", "推荐策略实验",
                        "groups", Map.of("control", "rule_based", "treatment_llm", "llm_rerank")
                )
        );
    }
}
