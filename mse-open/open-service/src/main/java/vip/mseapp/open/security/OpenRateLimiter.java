package vip.mseapp.open.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import vip.mseapp.open.config.OpenProperties;
import vip.mseapp.open.entity.OpenApp;

import java.time.Duration;
import java.time.Instant;

/**
 * 应用级限流（固定窗口计数器，Redis 实现，多实例共享计数）。
 *
 * <p>窗口 1 秒，计数键 open:app:rate:{appId}:{epochSecond}；每调用都重设 2s 过期，
 * 即使「INCR 后设 TTL」之间进程崩溃也不会残留永久键。固定窗口存在 2 倍瞬时突刺的可能，
 * 一期按内部接入标准够用；二期外部接入可平滑升级滑动窗口/令牌桶。
 */
@Service
public class OpenRateLimiter {

    private final StringRedisTemplate redis;
    private final OpenProperties properties;

    public OpenRateLimiter(StringRedisTemplate redis, OpenProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** @return true = 放行；false = 超限（应答 429） */
    public boolean tryAcquire(OpenApp app) {
        long limit = app.getRateLimitQps() == null
                ? properties.getDefaultRateLimit()
                : app.getRateLimitQps();
        if (limit <= 0) {
            return true;
        }
        long second = Instant.now().getEpochSecond();
        String key = "open:app:rate:" + app.getId() + ":" + second;
        Long count = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofSeconds(2));
        return count == null || count <= limit;
    }
}
