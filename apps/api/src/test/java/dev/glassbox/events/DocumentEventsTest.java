package dev.glassbox.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.ingest.ApiTestSupport;
import dev.glassbox.ingest.DocumentEventsHub;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Worker NOTIFY -> Postgres LISTEN -> hub -> per-workspace subscribers. */
class DocumentEventsTest extends ApiTestSupport {
    @Autowired DocumentEventsHub hub;

    private void awaitSize(List<String> events, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (events.size() < n && System.currentTimeMillis() < deadline) Thread.sleep(50);
    }

    private List<String> statuses(List<String> events) throws Exception {
        return events.stream().map(e -> {
            try {
                return json.readTree(e).get("status").asText();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }).toList();
    }

    @Test
    void subscribersSeeUploadProcessingAndReadyInOrder() throws Exception {
        Account a = register();
        List<String> events = new CopyOnWriteArrayList<>();
        Runnable unsubscribe = hub.subscribe(a.workspace(), p -> { if (p != null) events.add(p); });
        Thread.sleep(500); // the listener connection is established at startup; give the subscription a moment

        JsonNode d = uploadOk(a, "n.md", ("# Notes\n\nhello events " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        awaitSize(events, 3);

        assertThat(statuses(events)).containsExactly("UPLOADED", "PROCESSING", "READY");
        assertThat(json.readTree(events.get(2)).get("documentId").asText()).isEqualTo(d.get("id").asText());
        assertThat(events.get(0)).doesNotContain("workspaceId"); // routing data is not forwarded

        unsubscribe.run();
        uploadOk(a, "m.md", ("# More\n\nsecond " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        Thread.sleep(500);
        assertThat(events).as("no events after unsubscribing").hasSize(3);
    }

    @Test
    void aWorkspaceNeverReceivesAnotherWorkspacesEvents() throws Exception {
        Account mine = register();
        Account theirs = register();
        List<String> mineEvents = new CopyOnWriteArrayList<>();
        List<String> theirEvents = new CopyOnWriteArrayList<>();
        hub.subscribe(mine.workspace(), p -> { if (p != null) mineEvents.add(p); });
        hub.subscribe(theirs.workspace(), p -> { if (p != null) theirEvents.add(p); });
        Thread.sleep(500);

        uploadOk(theirs, "t.md", ("# T\n\nonly theirs " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        awaitSize(theirEvents, 3);

        assertThat(theirEvents).hasSize(3);
        assertThat(mineEvents).isEmpty();
    }

    @Test
    void theHttpEndpointOpensAStreamForMembersAndRefusesOutsiders() throws Exception {
        Account a = register();
        Account outsider = register();
        MvcResult r = mvc.perform(get("/api/events").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())
                .accept(MediaType.TEXT_EVENT_STREAM)).andExpect(request().asyncStarted()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("event:ready");

        mvc.perform(get("/api/events").header("Authorization", "Bearer " + outsider.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/events")).andExpect(status().isUnauthorized());
    }
}
