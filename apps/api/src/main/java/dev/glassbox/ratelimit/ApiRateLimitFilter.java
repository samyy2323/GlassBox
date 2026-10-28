package dev.glassbox.ratelimit;

import dev.glassbox.auth.Hashing;
import dev.glassbox.auth.Principal;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request rate limits for the whole API (SPEC 12), one token bucket per caller:
 * <ul>
 *   <li>sign-in, sign-up and refresh and the public report links: per client address, because nobody is authenticated yet and these are what is guessed or flooded;</li>
 *   <li>an authenticated caller: per API key or per user, and for API keys also per workspace (the workspace of a key comes from the key, so it cannot be named
 *       by someone else; the workspace header of a signed-in user is not trusted here, which keeps one tenant from using up another's allowance);</li>
 *   <li>requests that carry no valid credential: per client address, so a flood of 401s is bounded too.</li>
 * </ul>
 * Chat has its own, tighter limit on top ({@code glassbox.llm.rate-per-minute}). The answer is 429 with {@code Retry-After}.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final RateLimiter auth, publicLinks, anonymous, principals, workspaces;
    private final MeterRegistry metrics;

    public ApiRateLimitFilter(RateLimiter auth, RateLimiter publicLinks, RateLimiter anonymous, RateLimiter principals, RateLimiter workspaces, MeterRegistry metrics) {
        this.auth = auth;
        this.publicLinks = publicLinks;
        this.anonymous = anonymous;
        this.principals = principals;
        this.workspaces = workspaces;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !path.startsWith("/api/") || req.getMethod().equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        String client = req.getRemoteAddr();
        Principal p = SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Principal pr ? pr : null;

        if (path.startsWith("/api/auth/")) {
            if (!auth.tryAcquire(client)) { refuse(res, "sign-in", auth); return; }
        } else if (path.startsWith("/api/public/")) {
            if (!publicLinks.tryAcquire(client)) { refuse(res, "share link", publicLinks); return; }
        } else if (p == null) {
            if (!anonymous.tryAcquire(client)) { refuse(res, "client address", anonymous); return; }
        } else {
            String key = req.getHeader("X-API-Key");
            String who = p.userId() != null ? "user:" + p.userId() : "key:" + Hashing.sha256Hex(key == null ? "" : key).substring(0, 16);
            if (!principals.tryAcquire(who)) { refuse(res, p.userId() != null ? "user" : "API key", principals); return; }
            if (p.userId() == null && !workspaces.tryAcquire("ws:" + p.workspaceId())) { refuse(res, "workspace", workspaces); return; }
        }
        chain.doFilter(req, res);
    }

    private void refuse(HttpServletResponse res, String scope, RateLimiter limiter) throws IOException {
        metrics.counter("rate_limited_total", "scope", scope.replace(' ', '_')).increment();
        int wait = limiter.secondsUntilToken();
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(wait));
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,\"detail\":\"Too many requests for this " + scope
                + ". Try again in " + wait + " s.\"}");
    }
}
