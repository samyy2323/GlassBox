package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** SPEC 12: sensitive actions are written to the audit log, once, with the actor and never a secret; only owners read it. */
class AuditApiTest extends EvalApiSupport {
    private List<String> actions(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : page.get("items")) out.add(e.get("action").asText());
        return out;
    }

    @Test
    void everySensitiveActionWritesOneRowWithTheActorAndNoSecret() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "audited", 6);
        String exp = createExperiment(a, set, "audited run", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]").get("id").asText();
        drainQueue();

        JsonNode key = ok(send(post("/api/workspaces/" + a.workspace() + "/api-keys"), a, "{\"label\":\"ci\",\"scopes\":[\"read\"]}"));
        String secret = key.get("key").asText();
        ok(send(put("/api/budget"), a, "{\"monthlyUsd\":50,\"perRunUsd\":null,\"confirmAboveUsd\":null}"));
        ok(send(put("/api/budget/prices"), a, "{\"model\":\"audit-model\",\"promptUsdPerMillion\":1,\"completionUsdPerMillion\":2}"));
        ok(send(delete("/api/budget/prices?model=audit-model"), a, null));
        String report = ok(send(post("/api/reports"), a, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + exp + "\",\"redact\":false}")).get("id").asText();
        JsonNode link = ok(send(post("/api/reports/" + report + "/links"), a, "{\"days\":7}"));
        String token = link.get("token").asText();
        send(delete("/api/reports/" + report + "/links/" + link.get("id").asText()), a, null).andExpect(status().is2xxSuccessful());
        send(delete("/api/reports/" + report), a, null).andExpect(status().is2xxSuccessful());
        send(delete("/api/workspaces/" + a.workspace() + "/api-keys/" + key.get("id").asText()), a, null).andExpect(status().is2xxSuccessful());
        String docId = ok(send(get("/api/documents?limit=1"), a, null)).get("items").get(0).get("id").asText();
        send(delete("/api/documents/" + docId), a, null).andExpect(status().is2xxSuccessful());

        String raw = send(get("/api/audit?limit=200"), a, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode page = json.readTree(raw);
        List<String> seen = actions(page);
        assertThat(seen).contains("document.upload", "experiment.create", "apikey.create", "budget.update", "pricing.update", "pricing.delete", "report.create",
                "report.link.create", "report.link.revoke", "report.delete", "apikey.delete", "document.delete");
        for (String once : List.of("apikey.create", "budget.update", "pricing.update", "pricing.delete", "report.create", "report.link.create", "report.link.revoke",
                "report.delete", "apikey.delete", "document.delete", "experiment.create")) {
            assertThat(seen.stream().filter(once::equals).count()).as(once).isEqualTo(1);
        }
        assertThat(seen.indexOf("document.delete")).as("newest first").isLessThan(seen.indexOf("document.upload"));
        for (JsonNode e : page.get("items")) assertThat(e.get("actor").asText()).startsWith("user:");
        assertThat(raw).as("a key and a link token are secrets").doesNotContain(secret).doesNotContain(token);
        assertThat(secret).isNotBlank();
    }

    @Test
    void theLogCanBeFilteredAndPagedAndIsPrivateToItsWorkspaceAndOwners() throws Exception {
        Account a = accountWithDocs(1, 3);
        for (int i = 0; i < 3; i++) ok(send(post("/api/workspaces/" + a.workspace() + "/api-keys"), a, "{\"label\":\"k" + i + "\",\"scopes\":[\"read\"]}"));

        JsonNode keys = ok(send(get("/api/audit?action=apikey."), a, null));
        assertThat(actions(keys)).hasSize(3).containsOnly("apikey.create");

        JsonNode first = ok(send(get("/api/audit?limit=2"), a, null));
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("next").isNull()).isFalse();
        JsonNode second = ok(send(get("/api/audit?limit=2&before=" + first.get("next").asText().replace("+", "%2B")), a, null));
        assertThat(second.get("items").get(0).get("id").asText()).isNotEqualTo(first.get("items").get(0).get("id").asText()).isNotEqualTo(first.get("items").get(1).get("id").asText());

        send(get("/api/audit?limit=0"), a, null).andExpect(status().isBadRequest());
        // an underscore or percent sign in the filter is text, not a wildcard
        assertThat(ok(send(get("/api/audit?action=%25"), a, null)).get("items")).isEmpty();

        Account other = register();
        assertThat(ok(send(get("/api/audit"), other, null)).get("items").toString()).doesNotContain(a.workspace().toString());

        // a read-only key may not read the log; an admin key may
        String read = ok(send(post("/api/workspaces/" + a.workspace() + "/api-keys"), a, "{\"label\":\"ro\",\"scopes\":[\"read\"]}")).get("key").asText();
        String admin = ok(send(post("/api/workspaces/" + a.workspace() + "/api-keys"), a, "{\"label\":\"adm\",\"scopes\":[\"admin\"]}")).get("key").asText();
        mvc.perform(get("/api/audit").header("X-API-Key", read)).andExpect(status().isForbidden());
        JsonNode viaKey = json.readTree(mvc.perform(get("/api/audit").header("X-API-Key", admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(viaKey.get("items").size()).isGreaterThan(0);
        mvc.perform(get("/api/audit").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
    }
}
