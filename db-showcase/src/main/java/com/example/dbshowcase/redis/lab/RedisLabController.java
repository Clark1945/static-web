package com.example.dbshowcase.redis.lab;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Redis 實戰場景實驗室的 API。 */
@RestController
@RequestMapping("/api/redis/lab")
public class RedisLabController {

    private final RedisLabService lab;

    public RedisLabController(RedisLabService lab) {
        this.lab = lab;
    }

    @PostMapping("/cache/query")
    public RedisLabService.CacheResult cacheQuery(@RequestParam int categoryId,
                                                  @RequestParam(defaultValue = "false") boolean cacheNull) {
        return lab.categoryReport(categoryId, cacheNull);
    }

    @PostMapping("/cache/invalidate")
    public Map<String, Long> invalidate(@RequestParam int categoryId) {
        return Map.of("deleted", lab.invalidate(categoryId));
    }

    @PostMapping("/rate-limit")
    public List<RedisLabService.RateResult> rateLimit(@RequestParam(defaultValue = "1") int requests) {
        return lab.rateLimit(clamp(requests, 1, 30), 5, 10);
    }

    @PostMapping("/rate-limit/reset")
    public void resetRateLimit() {
        lab.resetRateLimit();
    }

    @PostMapping("/lock/contend")
    public List<RedisLabService.LockAttempt> contend(@RequestParam(defaultValue = "5") int workers) {
        return lab.contend(clamp(workers, 2, 20));
    }

    @PostMapping("/lock/release")
    public List<RedisLabService.LockEvent> release(@RequestParam boolean safe) throws InterruptedException {
        return lab.releaseDemo(safe);
    }

    @PostMapping("/stock")
    public RedisLabService.StockResult stock(@RequestParam String mode,
                                             @RequestParam(defaultValue = "10") int stock,
                                             @RequestParam(defaultValue = "50") int buyers) {
        if (!List.of("naive", "decr", "lua").contains(mode)) {
            throw new IllegalArgumentException("mode 只能是 naive、decr、lua。");
        }
        return lab.oversell(mode, clamp(stock, 1, 100), clamp(buyers, 2, 60));
    }

    @GetMapping("/leaderboard")
    public RedisLabService.Board leaderboard(@RequestParam(required = false) String customerId) {
        return lab.leaderboard(customerId);
    }

    @PostMapping("/leaderboard/simulate")
    public Map<String, Double> simulate(@RequestParam(defaultValue = "100") int orders) {
        return Map.of("ms", lab.simulateOrders(clamp(orders, 1, 10_000)));
    }

    @PostMapping("/pipeline")
    public RedisLabService.PipelineResult pipeline(@RequestParam(defaultValue = "1000") int n) {
        return lab.pipeline(clamp(n, 10, 10_000));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
