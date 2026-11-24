package dev.glassbox.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the demo viewer read-only everywhere: any request that is not a read is refused, which also means the demo account can never ask a new question
 * (and so never costs model time), upload, delete, run an experiment or change a setting, whatever roles its memberships carry.
 */
public class DemoGuardFilter extends OncePerRequestFilter {
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private final DemoMode demo;

    public DemoGuardFilter(DemoMode demo) {
        this.demo = demo;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !demo.enabled() || READS.contains(req.getMethod()) || !req.getRequestURI().startsWith("/api/") || req.getRequestURI().startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Principal p && demo.isViewer(p.userId())) {
            res.setStatus(HttpStatus.FORBIDDEN.value());
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
            res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"detail\":\"This is a read-only demo. Create an account to upload your own documents and ask your own questions.\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
