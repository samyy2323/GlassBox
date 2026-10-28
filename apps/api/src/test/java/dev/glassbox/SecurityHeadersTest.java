package dev.glassbox;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.glassbox.ingest.ApiTestSupport;
import org.junit.jupiter.api.Test;

/** SPEC 12: locked-down CORS and security headers on every API answer, including errors. */
class SecurityHeadersTest extends ApiTestSupport {
    @Test
    void everyAnswerCarriesTheHeadersThatKeepABrowserFromDoingAnythingClever() throws Exception {
        Account a = register();
        for (var request : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[] {
                get("/api/workspaces").header("Authorization", "Bearer " + a.token()),   // 200
                get("/api/documents/not-a-uuid").header("Authorization", "Bearer " + a.token()), // 400
                get("/api/workspaces"),                                                   // 401
                get("/api/public/reports/unknown-token"),                                 // 404 on the public route
        }) {
            mvc.perform(request)
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                    .andExpect(header().string("Permissions-Policy", org.hamcrest.Matchers.containsString("camera=()")))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        }
        // HSTS is only meaningful on HTTPS
        mvc.perform(get("/api/workspaces").secure(true).header("Authorization", "Bearer " + a.token()))
                .andExpect(header().string("Strict-Transport-Security", org.hamcrest.Matchers.containsString("max-age=31536000")));
    }

    @Test
    void onlyTheConfiguredOriginMayCallTheApiFromABrowser() throws Exception {
        mvc.perform(options("/api/documents").header("Origin", "https://evil.example").header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/workspaces").header("Origin", "https://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/api/documents").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        // a header the API does not use is not allowed in a cross-origin call
        mvc.perform(options("/api/documents").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-something-else"))
                .andExpect(status().isForbidden());
    }
}
