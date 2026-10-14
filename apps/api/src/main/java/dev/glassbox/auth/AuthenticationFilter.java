package dev.glassbox.auth;

import dev.glassbox.tenancy.Tx;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT for users, or "X-API-Key: gb_{workspaceId}_{secret}" for the CI bot.
 * The workspace id inside the key lets us set the RLS context before the key row can be read.
 */
public class AuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final Tx tx;

    public AuthenticationFilter(JwtService jwt, Tx tx) {
        this.jwt = jwt;
        this.tx = tx;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Principal p = null;
        String auth = req.getHeader("Authorization");
        String apiKey = req.getHeader("X-API-Key");
        if (auth != null && auth.startsWith("Bearer ")) {
            p = jwt.verify(auth.substring(7)).map(Principal::user).orElse(null);
        } else if (apiKey != null) {
            p = fromApiKey(apiKey);
        }
        if (p != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(p, null, List.of()));
        }
        chain.doFilter(req, res);
    }

    private Principal fromApiKey(String key) {
        String[] parts = key.split("_", 3);
        if (parts.length != 3 || !parts[0].equals("gb")) return null;
        UUID ws;
        try {
            ws = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String hash = Hashing.sha256Hex(key);
        return tx.workspace(ws, null, j -> {
            var rows = j.queryForList("SELECT id, scopes FROM api_keys WHERE key_hash = ?", hash);
            if (rows.isEmpty()) return null;
            Object id = rows.get(0).get("id");
            j.update("UPDATE api_keys SET last_used_at = now() WHERE id = ? "
                    + "AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')", id);
            try {
                String[] scopes = (String[]) ((java.sql.Array) rows.get(0).get("scopes")).getArray();
                return Principal.apiKey(ws, Set.of(scopes));
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
