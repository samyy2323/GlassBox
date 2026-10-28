package dev.glassbox.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RateLimitConfig {
    @Bean
    ApiRateLimitFilter apiRateLimitFilter(@Value("${glassbox.rate.auth-per-minute}") int auth, @Value("${glassbox.rate.public-per-minute}") int publicLinks,
                                          @Value("${glassbox.rate.anonymous-per-minute}") int anonymous, @Value("${glassbox.rate.principal-per-minute}") int principal,
                                          @Value("${glassbox.rate.workspace-per-minute}") int workspace, MeterRegistry metrics) {
        return new ApiRateLimitFilter(new RateLimiter(auth), new RateLimiter(publicLinks), new RateLimiter(anonymous), new RateLimiter(principal),
                new RateLimiter(workspace), metrics);
    }

    /** The filter runs inside the security chain, after authentication; it must not also be registered as a plain servlet filter. */
    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilterRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> r = new FilterRegistrationBean<>(filter);
        r.setEnabled(false);
        return r;
    }
}
