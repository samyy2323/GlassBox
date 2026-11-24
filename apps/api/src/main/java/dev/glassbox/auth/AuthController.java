package dev.glassbox.auth;

import dev.glassbox.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Profile("api")
public class AuthController {
    static final String COOKIE = "gb_refresh";
    /** Custom header required on cookie-authenticated calls: browsers cannot send it cross-site without a CORS preflight. */
    static final String CSRF_HEADER = "X-Glassbox-CSRF";

    public record Credentials(@NotBlank @Email String email, @NotBlank @Size(min = 8, max = 128) String password) {}
    public record LoginBody(@NotBlank String email, @NotBlank String password) {}
    public record AuthResponse(AuthService.User user, String accessToken, long expiresIn) {}

    private final AuthService auth;
    private final DemoMode demo;
    private final boolean secureCookie;

    public AuthController(AuthService auth, DemoMode demo, @Value("${glassbox.refresh.secure-cookie}") boolean secureCookie) {
        this.auth = auth;
        this.demo = demo;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody Credentials body) {
        return respond(auth.register(body.email(), body.password()));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginBody body) {
        return respond(auth.login(body.email(), body.password()));
    }

    /** Tells the sign-in page whether to offer the demo button, and the shell which account is the read-only one. */
    @GetMapping("/demo")
    public java.util.Map<String, Object> demoInfo() {
        return java.util.Map.of("enabled", demo.enabled(), "email", demo.enabled() ? demo.viewerEmail() : "");
    }

    /** The public demo: signs in the read-only demo viewer. Not found unless the server was started with the demo switched on. */
    @PostMapping("/demo")
    public ResponseEntity<AuthResponse> demo() {
        if (!demo.enabled()) throw ApiException.notFound("Not found");
        return respond(auth.demo(demo.viewerEmail()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = COOKIE, required = false) String cookie,
                                                @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        requireCsrf(csrf);
        if (cookie == null) throw ApiException.unauthorized("Missing refresh token");
        return respond(auth.refresh(cookie));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String cookie,
                                       @RequestHeader(name = CSRF_HEADER, required = false) String csrf) {
        requireCsrf(csrf);
        auth.logout(cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    private static void requireCsrf(String header) {
        if (header == null || header.isBlank()) throw ApiException.forbidden("Missing " + CSRF_HEADER + " header");
    }

    private ResponseEntity<AuthResponse> respond(AuthService.Session s) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), auth.refreshTtl().toSeconds()).toString())
                .body(new AuthResponse(s.user(), s.accessToken(), s.expiresInSeconds()));
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(COOKIE, value).httpOnly(true).secure(secureCookie)
                .sameSite("Strict").path("/api/auth").maxAge(maxAgeSeconds).build();
    }
}
