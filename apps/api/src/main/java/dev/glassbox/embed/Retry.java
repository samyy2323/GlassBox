package dev.glassbox.embed;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Exponential backoff for calls to model providers. Retries transient failures only: network errors,
 * HTTP 429 (honouring Retry-After) and 5xx. Other 4xx responses are bugs or bad input and fail at once.
 */
public final class Retry {
    private static final Logger log = LoggerFactory.getLogger(Retry.class);
    private static final Duration MAX_DELAY = Duration.ofSeconds(30);

    private Retry() {}

    public static <T> T call(String what, int maxAttempts, Duration base, Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RestClientResponseException e) {
                if (!retryable(e.getStatusCode().value()) || attempt >= maxAttempts) throw e;
                pause(what, attempt, delayFor(e, attempt, base), e.getStatusCode().value() + "");
            } catch (ResourceAccessException e) {
                if (attempt >= maxAttempts) throw e;
                pause(what, attempt, backoff(attempt, base), e.getMessage());
            }
        }
    }

    static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    private static Duration delayFor(RestClientResponseException e, int attempt, Duration base) {
        String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                Duration d = Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
                return d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d;
            } catch (NumberFormatException ignored) {
                // HTTP-date form: fall back to exponential backoff
            }
        }
        return backoff(attempt, base);
    }

    private static Duration backoff(int attempt, Duration base) {
        Duration d = base.multipliedBy(1L << Math.min(attempt - 1, 10));
        return d.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : d;
    }

    private static void pause(String what, int attempt, Duration delay, String reason) {
        log.warn("{} failed (attempt {}, {}); retrying in {} ms", what, attempt, reason, delay.toMillis());
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while retrying " + what, ie);
        }
    }
}
