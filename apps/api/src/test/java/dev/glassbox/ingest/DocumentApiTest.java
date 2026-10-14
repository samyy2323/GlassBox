package dev.glassbox.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.MoreTestFiles;
import dev.glassbox.TestDb;
import dev.glassbox.TestFiles;
import dev.glassbox.jobs.JobWorker;
import dev.glassbox.tenancy.Tx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

class DocumentApiTest extends ApiTestSupport {

    // ------------------------------------------------------------ happy paths

    @Test
    void uploadedPdfIsIngestedIntoOffsetCorrectChunks() throws Exception {
        Account a = register();
        JsonNode d = uploadOk(a, "policy.pdf", TestFiles.pdf(pdfText(), "Second page about valves."));
        assertThat(d.get("status").asText()).isEqualTo("UPLOADED");
        assertThat(d.get("title").asText()).isEqualTo("policy");

        drainQueue();
        assertThat(doc(a, d.get("id").asText()).get("status").asText()).isEqualTo("READY");

        JsonNode content = json.readTree(mvc.perform(get("/api/documents/" + d.get("id").asText() + "/content")
                        .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String text = content.get("text").asText();
        assertThat(text).contains("pumps must be inspected").contains("Second page about valves");
        assertThat(content.get("pageStarts")).hasSize(2);

        List<Map<String, Object>> chunks = tx.workspace(a.workspace(), null, j -> j.queryForList(
                "SELECT text, char_start, char_end, page FROM chunks WHERE document_id = ? ORDER BY ordinal", UUID.fromString(d.get("id").asText())));
        assertThat(chunks).isNotEmpty();
        for (var c : chunks) {
            String original = text.substring((Integer) c.get("char_start"), (Integer) c.get("char_end"));
            assertThat((String) c.get("text")).endsWith(original);
            assertThat(c.get("page")).isNotNull();
        }
        // A chunk's page is the page it starts on (small chunks may span a page break).
        int secondPageStart = content.get("pageStarts").get(1).asInt();
        for (var c : chunks) {
            int expected = (Integer) c.get("char_start") >= secondPageStart ? 2 : 1;
            assertThat((Integer) c.get("page")).isEqualTo(expected);
        }
    }

    @Test
    void docxHtmlAndMarkdownAreIngested() throws Exception {
        Account a = register();
        String marker = UUID.randomUUID().toString();
        JsonNode docx = uploadOk(a, "rules.docx", TestFiles.docx());
        JsonNode html = uploadOk(a, "billing.html", (TestFiles.HTML + "<p>" + marker + "</p>").getBytes(StandardCharsets.UTF_8));
        JsonNode md = uploadOk(a, "guide.md", (TestFiles.MARKDOWN + "\n" + marker + "\n").getBytes(StandardCharsets.UTF_8));
        drainQueue();
        for (JsonNode d : List.of(docx, html, md)) {
            assertThat(doc(a, d.get("id").asText()).get("status").asText()).as(d.get("title").asText()).isEqualTo("READY");
        }
        assertThat(countRows(a, "sections")).isGreaterThan(3);
    }

    @Test
    void exactDuplicateIsSkipped() throws Exception {
        Account a = register();
        byte[] pdf = TestFiles.pdf(pdfText());
        JsonNode first = uploadOk(a, "one.pdf", pdf);
        JsonNode again = json.readTree(upload(a, "copy-of-one.pdf", pdf).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.get("duplicate").asBoolean()).isTrue();
        assertThat(again.get("document").get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(countJobs("")).isEqualTo(1);
    }

    @Test
    void replacingADocumentCreatesVersion2AndSupersedesOldChunks() throws Exception {
        Account a = register();
        JsonNode d = uploadOk(a, "manual.pdf", TestFiles.pdf(pdfText()));
        drainQueue();
        String id = d.get("id").asText();

        mvc.perform(multipart(HttpMethod.PUT, "/api/documents/" + id)
                        .file(new MockMultipartFile("file", "manual.pdf", null, TestFiles.pdf(pdfText() + " Revised."))
                        ).header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        drainQueue();

        assertThat(doc(a, id).get("status").asText()).isEqualTo("READY");
        var superseded = tx.workspace(a.workspace(), null, j -> j.queryForList(
                "SELECT v.version, c.superseded, count(*) AS n FROM chunks c JOIN document_versions v ON v.id = c.document_version_id "
                        + "WHERE c.document_id = ? GROUP BY v.version, c.superseded ORDER BY v.version", UUID.fromString(id)));
        assertThat(superseded).extracting(r -> r.get("version") + ":" + r.get("superseded")).containsExactly("1:true", "2:false");
    }

    @Test
    void deleteRemovesRowsChunksAndFiles() throws Exception {
        Account a = register();
        JsonNode d = uploadOk(a, "temp.pdf", TestFiles.pdf(pdfText()));
        drainQueue();
        String id = d.get("id").asText();
        Path dir = TestDb.STORAGE.resolve(a.workspace().toString()).resolve(id);
        assertThat(dir).exists();

        mvc.perform(delete("/api/documents/" + id).header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isNoContent());
        assertThat(countRows(a, "chunks")).isZero();
        assertThat(countRows(a, "sections")).isZero();
        assertThat(Files.exists(dir)).isFalse();
        mvc.perform(get("/api/documents/" + id + "/content").header("Authorization", "Bearer " + a.token())
                .header("X-Workspace-Id", a.workspace())).andExpect(status().isNotFound());
    }

    @Test
    void listIsCursorPaginated() throws Exception {
        Account a = register();
        for (int i = 0; i < 3; i++) uploadOk(a, "n" + i + ".md", ("# T" + i + "\n\n" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
        JsonNode p1 = json.readTree(mvc.perform(get("/api/documents?limit=2").header("Authorization", "Bearer " + a.token())
                .header("X-Workspace-Id", a.workspace())).andReturn().getResponse().getContentAsString());
        assertThat(p1.get("items")).hasSize(2);
        String cursor = p1.get("nextCursor").asText();
        JsonNode p2 = json.readTree(mvc.perform(get("/api/documents").param("limit", "2").param("cursor", cursor)
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())).andReturn().getResponse().getContentAsString());
        assertThat(p2.get("items")).hasSize(1);
        assertThat(p2.has("nextCursor")).isFalse();
    }

    // ------------------------------------------------------------ failures

    @Test
    void scannedAndPasswordProtectedPdfsFailAsUnsupported() throws Exception {
        Account a = register();
        JsonNode scanned = uploadOk(a, "scan.pdf", TestFiles.pdf("", ""));
        JsonNode locked = uploadOk(a, "locked.pdf", TestFiles.encryptedPdf("pw", pdfText()));
        drainQueue();
        JsonNode s = doc(a, scanned.get("id").asText());
        assertThat(s.get("status").asText()).isEqualTo("FAILED_UNSUPPORTED");
        assertThat(s.get("error").asText()).contains("OCR");
        JsonNode l = doc(a, locked.get("id").asText());
        assertThat(l.get("status").asText()).isEqualTo("FAILED_UNSUPPORTED");
        assertThat(l.get("error").asText()).contains("Password");
        // Unsupported is final: the job is DONE, not retried.
        assertThat(countJobs(" WHERE status <> 'DONE'")).isZero();
    }

    @Test
    void corruptFileFailsAsUnsupportedNotRetriedForever() throws Exception {
        Account a = register();
        byte[] notReallyAPdf = ("%PDF-1.4\n" + UUID.randomUUID() + " garbage garbage").getBytes(StandardCharsets.UTF_8);
        JsonNode d = uploadOk(a, "broken.pdf", notReallyAPdf);
        drainQueue();
        assertThat(doc(a, d.get("id").asText()).get("status").asText()).isEqualTo("FAILED_UNSUPPORTED");
    }

    @Test
    void rejectsMediaTheSystemCannotReadWithAReasonEmptyFilesAndMissingWorkspace() throws Exception {
        Account a = register();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};
        upload(a, "image.png", png).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("OCR")));
        upload(a, "talk.mp3", new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0x21, 0x54, 'I', '2', 0, 0, 0, 0, 0, 0, 0})
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("transcription")));
        upload(a, "bundle.zip", MoreTestFiles.zip(java.util.Map.of("a.txt", "x"))).andExpect(status().isUnsupportedMediaType());
        byte[] noise = new byte[3000];
        new java.util.Random(3).nextBytes(noise);
        noise[5] = 0;
        upload(a, "mystery.bin", noise).andExpect(status().isUnsupportedMediaType());
        upload(a, "empty.pdf", new byte[0]).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "a.pdf", null, TestFiles.pdf(pdfText())))
                .header("Authorization", "Bearer " + a.token())).andExpect(status().isBadRequest());
    }

    /** Every supported family goes through the real upload, queue, parser, chunker and indexer, and its text is searchable. */
    @Test
    void everySupportedFormatIsIngestedAndItsTextIsSearchable() throws Exception {
        Account a = register();
        String salt = UUID.randomUUID().toString().substring(0, 8);
        record Case(String name, byte[] data, String expectedType, String expectedText) {}
        List<Case> cases = List.of(
                new Case("notes.txt", ("Plain note about turbines " + salt).getBytes(StandardCharsets.UTF_8), "TXT", "Plain note about turbines"),
                new Case("Main.java", ("class Valve { /* pressure relief " + salt + " */ }").getBytes(StandardCharsets.UTF_8), "TXT", "pressure relief"),
                new Case("plans.csv", ("Plan,Price\nBasic" + salt + ",10\nTeam,40\n").getBytes(StandardCharsets.UTF_8), "CSV", "Plan: Team; Price: 40"),
                new Case("config.json", ("{\"service\":\"billing" + salt + "\",\"limits\":{\"rate\":100}}").getBytes(StandardCharsets.UTF_8), "JSON", "limits.rate: 100"),
                new Case("feed.xml", ("<root><owner>Dana" + salt + "</owner></root>").getBytes(StandardCharsets.UTF_8), "XML", "owner: Dana"),
                new Case("memo.rtf", MoreTestFiles.RTF.replace("autumn", "autumn" + salt).getBytes(StandardCharsets.UTF_8), "RTF", "Boilers are checked every autumn"),
                new Case("prices.xlsx", MoreTestFiles.xlsx(), "XLSX", "Plan: Team; Price: 40; Seats: 5"),
                new Case("legacy.xls", MoreTestFiles.xls(), "XLS", "Plan: Team; Price: 40; Seats: 5"),
                new Case("deck.pptx", MoreTestFiles.pptx(), "PPTX", "Incidents fell by 40 percent"),
                new Case("old.ppt", MoreTestFiles.ppt(), "PPT", "forklift training"),
                new Case("handbook.odt", MoreTestFiles.odt(), "ODF", "Valves are serviced every six months"),
                new Case("novel.epub", MoreTestFiles.epub(), "EPUB", "It ends at the lighthouse"),
                new Case("rules.docx", TestFiles.docx(), "DOCX", "Goggles are required near grinders"),
                new Case("billing.html", (TestFiles.HTML + "<p>" + salt + "</p>").getBytes(StandardCharsets.UTF_8), "HTML", "First notice after 7 days"),
                new Case("guide.md", (TestFiles.MARKDOWN + "\n" + salt + "\n").getBytes(StandardCharsets.UTF_8), "MD", "second step"),
                new Case("policy.pdf", TestFiles.pdf("Policy text for " + salt + " about scaffolding"), "PDF", "about scaffolding"));

        for (Case c : cases) {
            JsonNode d = uploadOk(a, c.name(), c.data());
            assertThat(d.get("sourceType").asText()).as(c.name()).isEqualTo(c.expectedType());
        }
        drainQueue();

        List<String> chunkTexts = tx.workspace(a.workspace(), null, j -> j.queryForList("SELECT text FROM chunks", String.class));
        String all = String.join("\n", chunkTexts);
        JsonNode page = json.readTree(mvc.perform(get("/api/documents?limit=50").header("Authorization", "Bearer " + a.token())
                .header("X-Workspace-Id", a.workspace())).andReturn().getResponse().getContentAsString());
        for (JsonNode d : page.get("items")) {
            assertThat(d.get("status").asText()).as("%s should be READY (error: %s)", d.get("title").asText(), d.path("error").asText()).isEqualTo("READY");
        }
        for (Case c : cases) assertThat(all).as("text of %s is searchable", c.name()).contains(c.expectedText());
        assertThat(all).as("hidden spreadsheet sheets are not indexed").doesNotContain("hidden sheet text");
    }

    // ------------------------------------------------------------ access control

    @Test
    void viewersCanReadButNotUploadAndOutsidersSeeNothing() throws Exception {
        Account owner = register();
        Account viewer = register();
        Account outsider = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        JsonNode d = uploadOk(owner, "secret.pdf", TestFiles.pdf(pdfText()));
        drainQueue();

        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        upload(asViewer, "x.pdf", TestFiles.pdf(pdfText())).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + viewer.token()).header("X-Workspace-Id", owner.workspace()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(delete("/api/documents/" + d.get("id").asText()).header("Authorization", "Bearer " + viewer.token())
                .header("X-Workspace-Id", owner.workspace())).andExpect(status().isForbidden());

        Account asOutsider = new Account(outsider.token(), owner.workspace(), outsider.email());
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + outsider.token()).header("X-Workspace-Id", owner.workspace()))
                .andExpect(status().isNotFound());
        upload(asOutsider, "y.pdf", TestFiles.pdf(pdfText())).andExpect(status().isNotFound());
        // Their own workspace is empty and cannot reach the owner's document by id either.
        mvc.perform(get("/api/documents/" + d.get("id").asText() + "/content").header("Authorization", "Bearer " + outsider.token())
                .header("X-Workspace-Id", outsider.workspace())).andExpect(status().isNotFound());
    }

    @Test
    void apiKeysNeedTheRunScopeToUpload() throws Exception {
        Account a = register();
        String readKey = createKey(a, "read");
        String runKey = createKey(a, "run");
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "k.pdf", null, TestFiles.pdf(pdfText())))
                .header("X-API-Key", readKey)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "k.pdf", null, TestFiles.pdf(pdfText())))
                .header("X-API-Key", runKey)).andExpect(status().isCreated());
        mvc.perform(get("/api/documents").header("X-API-Key", readKey)).andExpect(status().isOk());
    }

    private String createKey(Account a, String scope) throws Exception {
        return json.readTree(mvc.perform(post("/api/workspaces/" + a.workspace() + "/api-keys")
                        .header("Authorization", "Bearer " + a.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"k\",\"scopes\":[\"" + scope + "\"]}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("key").asText();
    }
}
