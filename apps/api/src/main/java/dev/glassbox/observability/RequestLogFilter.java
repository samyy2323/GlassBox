package dev.glassbox.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One log line per API request: method, path, status and duration (SPEC 14). It runs after the tracing filter, so the line carries the trace id, and before the security
 * chain, so a refused request (401, 429) is logged too. It never logs the query string, a header or a body. The token in a share link is part of its path, so that
 * path is logged with the token replaced. {@code workspace_id} is added only when the request succeeded, because only then has the workspace named in the header been
 * checked against the caller's memberships.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    private static final String PUBLIC_REPORTS = "/api/public/reports/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long millis = (System.nanoTime() - started) / 1_000_000;
            boolean verified = res.getStatus() < 300;
            String ws = verified ? workspaceOf(req) : null;
            if (ws != null) MDC.put("workspace_id", ws);
            try {
                log.info("{} {} {} {}ms", req.getMethod(), loggablePath(req.getRequestURI()), res.getStatus(), millis);
            } finally {
                if (ws != null) MDC.remove("workspace_id");
            }
        }
    }

    static String loggablePath(String path) {
        if (path.startsWith(PUBLIC_REPORTS)) {
            int next = path.indexOf('/', PUBLIC_REPORTS.length());
            return PUBLIC_REPORTS + "{token}" + (next < 0 ? "" : path.substring(next));
        }
        return path;
    }

    private static String workspaceOf(HttpServletRequest req) {
        String header = req.getHeader("X-Workspace-Id");
        if (header == null) return null;
        try {
            return UUID.fromString(header).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
