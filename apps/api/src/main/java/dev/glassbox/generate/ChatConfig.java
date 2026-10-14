package dev.glassbox.generate;

import dev.glassbox.ratelimit.RateLimiter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    RateLimiter chatRateLimiter(@Value("${glassbox.llm.rate-per-minute}") int perMinute) {
        return new RateLimiter(perMinute);
    }

    /** Streaming work is blocking I/O; virtual threads keep it cheap. Concurrency is bounded by {@link LlmGate}, not by this pool. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService chatExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
