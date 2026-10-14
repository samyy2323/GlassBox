package dev.glassbox.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeLlmProvider;
import dev.glassbox.ingest.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Test set generation, review, versioning and diff through the real API, queue and database; the model is scripted. */
class TestSetApiTest extends ApiTestSupport {

    static final String[] NOUNS = {"compressor", "alternator", "condenser", "regulator", "generator", "evaporator", "accumulator", "transformer",
            "thermostat", "calibration", "refrigerant", "lubricator", "separator", "exchanger", "insulation", "pressurizer", "dehumidifier",
            "extinguisher", "anemometer", "flowmeter"};

    /** A markdown document with {@code sections} sections, each its own passage with its own vocabulary so that questions about them differ. */
    static String markdown(String topic, int sections, int salt) {
        StringBuilder sb = new StringBuilder("# " + topic + "\n\n");
        for (int i = 0; i < sections; i++) {
            String n1 = NOUNS[(salt * 7 + i * 2) % NOUNS.length], n2 = NOUNS[(salt * 7 + i * 2 + 1) % NOUNS.length];
            sb.append("## Part ").append(i).append(" of ").append(topic).append("\n\n")
                    .append("The ").append(n1).append(" unit controls the ").append(n2).append(" circuit and retries every failed task for the plant. ")
                    .append("The system reports its health to operators every day. The limit for part ").append(i).append(" is ").append(40 + i)
                    .append(" units per hour, and operators must record any breach in the shared logbook before the end of the shift. ")
                    .append("Unique marker ").append(UUID.randomUUID()).append(" ends this part of the guide for readers and auditors.\n\n");
        }
        return sb.toString();
    }

    Account accountWithDocs(int docs, int sections) throws Exception {
        Account a = register();
        String[] topics = {"Pumps", "Boilers", "Turbines", "Filters", "Valves"};
        for (int i = 0; i < docs; i++) uploadOk(a, topics[i % topics.length] + i + ".md", markdown(topics[i % topics.length] + i, sections, i).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        return a;
    }

    // ------------------------------------------------------------------ helpers

    ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, Account a, String body) throws Exception {
        b = b.header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace());
        return mvc.perform(body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    JsonNode generate(Account a, String name, int count) throws Exception {
        return ok(send(post("/api/testsets/generate"), a, "{\"name\":\"" + name + "\",\"count\":" + count + ",\"seed\":11}")).get("testset");
    }

    JsonNode set(Account a, String id) throws Exception {
        return ok(send(get("/api/testsets/" + id), a, null));
    }

    List<JsonNode> questions(Account a, String id, String query) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        ok(send(get("/api/testsets/" + id + "/questions?limit=200" + query), a, null)).get("items").forEach(out::add);
        return out;
    }

    JsonNode generated(Account a, String name, int count) throws Exception {
        String id = generate(a, name, count).get("id").asText();
        drainQueue();
        return set(a, id);
    }

    String scripted(String question, String answer) {
        return "{\"question\": \"" + question + "\", \"expected_answer\": \"" + answer + "\"}";
    }

    // ------------------------------------------------------------------ generation

    @Test
    void generatesQuestionsWithExactEvidenceSpread_acrossDocuments() throws Exception {
        Account a = accountWithDocs(3, 6);
        JsonNode created = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"first\",\"count\":8,\"seed\":5}"));
        assertThat(created.get("testset").get("status").asText()).isEqualTo("GENERATING");
        assertThat(created.get("estimatedLlmCalls").asInt()).as("one generation plus two checks per question").isEqualTo(24);
        String id = created.get("testset").get("id").asText();
        drainQueue();

        JsonNode t = set(a, id);
        assertThat(t.get("status").asText()).isEqualTo("DRAFT");
        assertThat(t.get("generatedCount").asInt()).isEqualTo(8);
        assertThat(t.get("stats").get("total").asInt()).isEqualTo(8);
        assertThat(t.get("stats").get("pending").asInt()).isEqualTo(8);
        assertThat(t.get("stats").has("acceptanceRate")).as("nothing reviewed yet").isFalse();

        List<JsonNode> qs = questions(a, id, "");
        assertThat(qs).hasSize(8);
        Set<String> docs = new HashSet<>();
        Set<String> allowed = Set.of("FACTUAL", "NUMERIC", "MULTI_HOP", "COMPARISON");
        for (JsonNode q : qs) {
            assertThat(allowed).contains(q.get("qtype").asText());
            assertThat(q.get("origin").asText()).isEqualTo("SYNTHETIC");
            assertThat(q.get("reviewStatus").asText()).isEqualTo("PENDING");
            assertThat(q.get("evidence")).isNotEmpty();
            for (JsonNode ev : q.get("evidence")) {
                docs.add(ev.get("documentId").asText());
                // the span must be literally in the document, at exactly those offsets
                JsonNode content = ok(send(get("/api/documents/" + ev.get("documentId").asText() + "/content"), a, null));
                String text = content.get("text").asText();
                int start = ev.get("charStart").asInt(), end = ev.get("charEnd").asInt();
                assertThat(text.substring(start, end)).isEqualTo(ev.get("text").asText());
                assertThat(end - start).as("a tight span, not the whole chunk").isLessThan(700);
            }
            if (q.get("qtype").asText().equals("MULTI_HOP") || q.get("qtype").asText().equals("COMPARISON")) assertThat(q.get("evidence")).hasSize(2);
        }
        assertThat(docs.size()).as("questions come from several documents").isGreaterThanOrEqualTo(2);
        assertThat(t.get("stats").get("byType").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void generationCanBeRestrictedToChosenDocuments() throws Exception {
        Account a = register();
        String only = uploadOk(a, "only.md", markdown("Pumps", 5, 0).getBytes(StandardCharsets.UTF_8)).get("id").asText();
        uploadOk(a, "other.md", markdown("Boilers", 5, 3).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        String id = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"scoped\",\"count\":4,\"documentIds\":[\"" + only + "\"]}")).get("testset").get("id").asText();
        drainQueue();
        for (JsonNode q : questions(a, id, "")) for (JsonNode ev : q.get("evidence")) assertThat(ev.get("documentId").asText()).isEqualTo(only);
    }

    @Test
    void weakQuestionsAreFlaggedNotDropped() throws Exception {
        Account a = accountWithDocs(2, 5);
        // The same question every time (duplicates), mentioning "the document" (ambiguous), with an answer that is not in the text.
        FakeLlmProvider.GENERATOR_SCRIPT = r -> scripted("What does the document say about zebras and giraffes?", "Zebras and giraffes graze together");
        JsonNode t = generated(a, "weak", 3);
        List<JsonNode> qs = questions(a, t.get("id").asText(), "");
        assertThat(qs).hasSize(3);
        for (JsonNode q : qs) {
            Set<String> flags = new HashSet<>();
            q.get("flags").forEach(f -> flags.add(f.asText()));
            assertThat(flags).contains("AMBIGUOUS", "LOW_ANSWERABILITY");
        }
        Set<String> first = new HashSet<>();
        qs.get(0).get("flags").forEach(f -> first.add(f.asText()));
        assertThat(first).as("the first of identical questions is not a duplicate").doesNotContain("DUPLICATE");
        for (JsonNode q : qs.subList(1, 3)) assertThat(q.get("flags").toString()).contains("DUPLICATE");
        assertThat(t.get("stats").get("byFlag").get("DUPLICATE").asInt()).isEqualTo(2);
        assertThat(questions(a, t.get("id").asText(), "&flag=DUPLICATE")).hasSize(2);
        assertThat(questions(a, t.get("id").asText(), "&flag=NONE")).isEmpty();
    }

    @Test
    void aQuestionThatCopiesItsEvidenceIsFlaggedAsCopyRisk() throws Exception {
        Account a = accountWithDocs(1, 3);
        FakeLlmProvider.GENERATOR_SCRIPT = r -> scripted("The system reports its health to operators every day?", "Every day");
        JsonNode t = generated(a, "copy", 1);
        assertThat(questions(a, t.get("id").asText(), "").get(0).get("flags").toString()).contains("COPY_RISK");
    }

    @Test
    void goodQuestionsPassAllChecksAndCanBeBulkAccepted() throws Exception {
        Account a = accountWithDocs(2, 5);
        JsonNode t = generated(a, "clean", 6);
        String id = t.get("id").asText();
        List<JsonNode> unflagged = questions(a, id, "&flag=NONE");
        assertThat(unflagged).as("the fake model writes clean questions").hasSize(6);
        assertThat(ok(send(post("/api/testsets/" + id + "/bulk-accept"), a, null)).get("accepted").asInt()).isEqualTo(6);
        assertThat(set(a, id).get("stats").get("acceptanceRate").asDouble()).isEqualTo(1.0);
        assertThat(ok(send(post("/api/testsets/" + id + "/bulk-accept"), a, null)).get("accepted").asInt()).as("idempotent").isZero();
    }

    @Test
    void unusableReplyIsRetriedAndThenSkipped() throws Exception {
        Account a = accountWithDocs(2, 5);
        AtomicInteger calls = new AtomicInteger();
        // First sample: two garbage replies, then a good one. Second sample: garbage every time, so it is skipped.
        FakeLlmProvider.GENERATOR_SCRIPT = r -> {
            int n = calls.incrementAndGet();
            return n <= 2 ? "I cannot do that" : n >= 4 && n <= 6 ? "still not json" : null; // null: the fake's normal reply
        };
        JsonNode t = generated(a, "garbage", 2);
        assertThat(t.get("status").asText()).isEqualTo("DRAFT");
        assertThat(t.get("generatedCount").asInt()).isEqualTo(2);
        assertThat(FakeLlmProvider.GENERATOR_CALLS.get()).as("3 attempts for the first, 3 for the skipped one, 1 for the last").isEqualTo(7);
    }

    @Test
    void aModelThatNeverAnswersWithJsonFailsTheTestSetWithAReason() throws Exception {
        Account a = accountWithDocs(1, 4);
        FakeLlmProvider.GENERATOR_SCRIPT = r -> "no json for you";
        JsonNode t = generated(a, "hopeless", 2);
        assertThat(t.get("status").asText()).isEqualTo("FAILED");
        assertThat(t.get("error").asText()).contains("usable question");
        assertThat(questions(a, t.get("id").asText(), "")).isEmpty();
    }

    @Test
    void generationResumesAfterAModelOutageWithoutDuplicatesOrRestarts() throws Exception {
        Account a = accountWithDocs(2, 6);
        AtomicInteger calls = new AtomicInteger();
        FakeLlmProvider.GENERATOR_SCRIPT = r -> {
            if (calls.incrementAndGet() == 4) throw new IllegalStateException("model server went away (simulated)");
            return null;
        };
        String id = generate(a, "resume", 6).get("id").asText();
        drainQueue();
        JsonNode partial = set(a, id);
        assertThat(partial.get("status").asText()).as("the job failed and will retry").isEqualTo("GENERATING");
        assertThat(partial.get("generatedCount").asInt()).isEqualTo(3);
        assertThat(questions(a, id, "")).hasSize(3);

        tx.worker(j -> j.update("UPDATE jobs SET run_after = now() WHERE status = 'QUEUED'"));
        drainQueue();
        JsonNode done = set(a, id);
        assertThat(done.get("status").asText()).isEqualTo("DRAFT");
        assertThat(done.get("generatedCount").asInt()).isEqualTo(6);
        List<JsonNode> qs = questions(a, id, "");
        assertThat(qs).hasSize(6);
        assertThat(qs.stream().map(q -> q.get("stableId").asText()).distinct()).hasSize(6);
        assertThat(calls.get()).as("3 done, 1 failed, 3 more: finished questions were not regenerated").isEqualTo(7);
    }

    @Test
    void generationIsRefusedWithoutReadyDocumentsOrWithBadInput() throws Exception {
        Account empty = register();
        send(post("/api/testsets/generate"), empty, "{\"name\":\"x\",\"count\":5}").andExpect(status().isUnprocessableEntity());

        Account a = accountWithDocs(1, 3);
        send(post("/api/testsets/generate"), a, "{\"name\":\"\",\"count\":5}").andExpect(status().isBadRequest());
        send(post("/api/testsets/generate"), a, "{\"name\":\"x\",\"count\":0}").andExpect(status().isBadRequest());
        send(post("/api/testsets/generate"), a, "{\"name\":\"x\",\"count\":201}").andExpect(status().isBadRequest());
        send(post("/api/testsets/generate"), a, "{\"name\":\"x\",\"count\":5,\"mix\":{\"UNANSWERABLE\":1}}").andExpect(status().isBadRequest());
        send(post("/api/testsets/generate"), a, "{\"name\":\"x\",\"count\":5,\"mix\":{\"FACTUAL\":-1}}").andExpect(status().isBadRequest());
        generate(a, "dup", 2);
        send(post("/api/testsets/generate"), a, "{\"name\":\"dup\",\"count\":2}").andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ review, versions, diff

    @Test
    void reviewFinalizeVersionAndDiff() throws Exception {
        Account a = accountWithDocs(2, 5);
        JsonNode t = generated(a, "suite", 6);
        String v1 = t.get("id").asText();
        List<JsonNode> qs = questions(a, v1, "");

        // review: edit one (marks EDITED), reject one, accept the others
        String editedId = qs.get(0).get("id").asText(), rejectedId = qs.get(1).get("id").asText();
        JsonNode edited = ok(send(patch("/api/questions/" + editedId), a, "{\"question\":\"How many units per hour is the limit for the first part?\"}"));
        assertThat(edited.get("reviewStatus").asText()).isEqualTo("EDITED");
        assertThat(edited.get("question").asText()).startsWith("How many units");
        assertThat(ok(send(patch("/api/questions/" + rejectedId), a, "{\"reviewStatus\":\"REJECTED\"}")).get("reviewStatus").asText()).isEqualTo("REJECTED");
        send(post("/api/testsets/" + v1 + "/finalize"), a, null).andExpect(status().isConflict()); // still pending
        for (JsonNode q : qs.subList(2, 6)) ok(send(patch("/api/questions/" + q.get("id").asText()), a, "{\"reviewStatus\":\"ACCEPTED\"}"));

        JsonNode stats = set(a, v1).get("stats");
        assertThat(stats.get("accepted").asInt()).isEqualTo(4);
        assertThat(stats.get("edited").asInt()).isEqualTo(1);
        assertThat(stats.get("rejected").asInt()).isEqualTo(1);
        assertThat(stats.get("acceptanceRate").asDouble()).isEqualTo(5.0 / 6.0);

        send(patch("/api/questions/" + editedId), a, "{\"reviewStatus\":\"EDITED\"}").andExpect(status().isBadRequest());
        JsonNode fin = ok(send(post("/api/testsets/" + v1 + "/finalize"), a, null));
        assertThat(fin.get("status").asText()).isEqualTo("FINAL");
        assertThat(fin.has("finalizedAt")).isTrue();

        // a final version is immutable
        send(patch("/api/questions/" + editedId), a, "{\"reviewStatus\":\"REJECTED\"}").andExpect(status().isConflict());
        send(post("/api/testsets/" + v1 + "/bulk-accept"), a, null).andExpect(status().isConflict());
        send(post("/api/testsets/" + v1 + "/finalize"), a, null).andExpect(status().isConflict());
        send(post("/api/testsets/" + v1 + "/questions"), a, "{\"question\":\"Anything at all here?\",\"expectedAnswer\":\"x\",\"qtype\":\"UNANSWERABLE\"}").andExpect(status().isConflict());

        // new version: a draft copy without the rejected question, same stable ids, parent pointer
        JsonNode v2 = ok(send(post("/api/testsets/" + v1 + "/versions"), a, null));
        String v2id = v2.get("id").asText();
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asText()).isEqualTo("DRAFT");
        assertThat(v2.get("parentVersionId").asText()).isEqualTo(v1);
        assertThat(v2.get("name").asText()).isEqualTo("suite");
        List<JsonNode> copy = questions(a, v2id, "");
        assertThat(copy).hasSize(5);
        assertThat(copy.stream().map(q -> q.get("stableId").asText()).toList()).containsExactlyElementsOf(
                qs.stream().filter(q -> !q.get("id").asText().equals(rejectedId)).map(q -> q.get("stableId").asText()).toList());
        send(post("/api/testsets/" + v1 + "/versions"), a, null).andExpect(status().isConflict()); // a draft already exists
        send(post("/api/testsets/" + v2id + "/versions"), a, null).andExpect(status().isConflict()); // a draft is edited directly

        // edit v2: change one question, drop one, add a manual one
        String changed = copy.get(1).get("id").asText(), dropped = copy.get(2).get("id").asText();
        ok(send(patch("/api/questions/" + changed), a, "{\"expectedAnswer\":\"A different expected answer\"}"));
        ok(send(patch("/api/questions/" + dropped), a, "{\"reviewStatus\":\"REJECTED\"}"));
        JsonNode ev = copy.get(0).get("evidence").get(0);
        JsonNode manual = ok(send(post("/api/testsets/" + v2id + "/questions"), a, "{\"question\":\"What is the manual question?\",\"expectedAnswer\":\"This one\",\"qtype\":\"FACTUAL\","
                + "\"spans\":[{\"documentId\":\"" + ev.get("documentId").asText() + "\",\"charStart\":" + ev.get("charStart").asInt() + ",\"charEnd\":" + ev.get("charEnd").asInt() + "}]}"));
        assertThat(manual.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(manual.get("reviewStatus").asText()).isEqualTo("ACCEPTED");
        assertThat(manual.get("evidence").get(0).get("text").asText()).isEqualTo(ev.get("text").asText());

        JsonNode diff = ok(send(get("/api/testsets/" + v2id + "/diff?against=" + v1), a, null));
        assertThat(diff.get("added")).hasSize(1);
        assertThat(diff.get("added").get(0).get("question").asText()).isEqualTo("What is the manual question?");
        assertThat(diff.get("removed")).hasSize(1);
        assertThat(diff.get("removed").get(0).get("id").asText()).isEqualTo(dropped.equals(copy.get(2).get("id").asText()) ? qs.stream()
                .filter(q -> q.get("stableId").asText().equals(copy.get(2).get("stableId").asText())).findFirst().orElseThrow().get("id").asText() : "");
        assertThat(diff.get("edited")).hasSize(1);
        assertThat(diff.get("edited").get(0).get("before").get("expectedAnswer").asText()).isNotEqualTo("A different expected answer");
        assertThat(diff.get("edited").get(0).get("after").get("expectedAnswer").asText()).isEqualTo("A different expected answer");
        assertThat(diff.get("unchanged").asInt()).isEqualTo(3);

        // v1 is untouched by all of that
        assertThat(set(a, v1).get("status").asText()).isEqualTo("FINAL");
        assertThat(questions(a, v1, "")).hasSize(6);
        assertThat(ok(send(get("/api/testsets"), a, null))).hasSize(2);
        // finalize v2 and make v3
        ok(send(post("/api/testsets/" + v2id + "/finalize"), a, "{\"allowPending\":true}"));
        assertThat(ok(send(post("/api/testsets/" + v2id + "/versions"), a, null)).get("version").asInt()).isEqualTo(3);
    }

    @Test
    void finalizeNeedsAnAcceptedQuestion() throws Exception {
        Account a = accountWithDocs(1, 4);
        String id = generated(a, "nothing", 2).get("id").asText();
        send(post("/api/testsets/" + id + "/finalize"), a, "{\"allowPending\":true}").andExpect(status().isConflict());
    }

    @Test
    void manualQuestionsValidateTheirEvidence() throws Exception {
        Account a = accountWithDocs(1, 3);
        String id = generated(a, "manual", 1).get("id").asText();
        String doc = questions(a, id, "").get(0).get("evidence").get(0).get("documentId").asText();
        String q = "\"question\":\"What does the manual question ask?\",\"expectedAnswer\":\"Something\"";
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"FACTUAL\"}").andExpect(status().isBadRequest()); // no evidence
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"NOPE\",\"spans\":[]}").andExpect(status().isBadRequest());
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"FACTUAL\",\"spans\":[{\"documentId\":\"" + doc + "\",\"charStart\":5,\"charEnd\":99999999}]}").andExpect(status().isUnprocessableEntity());
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"FACTUAL\",\"spans\":[{\"documentId\":\"" + doc + "\",\"charStart\":9,\"charEnd\":9}]}").andExpect(status().isUnprocessableEntity());
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"FACTUAL\",\"spans\":[{\"documentId\":\"" + UUID.randomUUID() + "\",\"charStart\":0,\"charEnd\":5}]}").andExpect(status().isUnprocessableEntity());
        send(post("/api/testsets/" + id + "/questions"), a, "{\"question\":\"  \",\"expectedAnswer\":\"x\",\"qtype\":\"FACTUAL\",\"spans\":[]}").andExpect(status().isBadRequest());
        // an unanswerable question has no evidence by definition
        send(post("/api/testsets/" + id + "/questions"), a, "{" + q + ",\"qtype\":\"UNANSWERABLE\"}").andExpect(status().isCreated());
    }

    @Test
    void changingEvidenceInReviewMarksTheQuestionEdited() throws Exception {
        Account a = accountWithDocs(1, 3);
        String id = generated(a, "evidence", 2).get("id").asText();
        JsonNode q = questions(a, id, "").get(0);
        JsonNode content = ok(send(get("/api/documents/" + q.get("evidence").get(0).get("documentId").asText() + "/content"), a, null));
        int start = q.get("evidence").get(0).get("charStart").asInt();
        // the same evidence again is not an edit
        JsonNode same = ok(send(patch("/api/questions/" + q.get("id").asText()), a, "{\"spans\":[{\"documentId\":\"" + q.get("evidence").get(0).get("documentId").asText()
                + "\",\"charStart\":" + start + ",\"charEnd\":" + q.get("evidence").get(0).get("charEnd").asInt() + "}]}"));
        assertThat(same.get("reviewStatus").asText()).isEqualTo("PENDING");
        JsonNode moved = ok(send(patch("/api/questions/" + q.get("id").asText()), a, "{\"spans\":[{\"documentId\":\"" + q.get("evidence").get(0).get("documentId").asText()
                + "\",\"charStart\":" + start + ",\"charEnd\":" + (start + 20) + "}]}"));
        assertThat(moved.get("reviewStatus").asText()).isEqualTo("EDITED");
        assertThat(moved.get("evidence").get(0).get("text").asText()).isEqualTo(content.get("text").asText().substring(start, start + 20));
    }

    // ------------------------------------------------------------------ roles and tenancy

    @Test
    void viewersReadButCannotChangeAndOtherWorkspacesSeeNothing() throws Exception {
        Account owner = accountWithDocs(1, 4);
        String id = generated(owner, "private", 2).get("id").asText();
        String qid = questions(owner, id, "").get(0).get("id").asText();

        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        ok(send(get("/api/testsets/" + id), asViewer, null));
        assertThat(questions(asViewer, id, "")).hasSize(2);
        ok(send(get("/api/testsets"), asViewer, null));
        send(post("/api/testsets/generate"), asViewer, "{\"name\":\"nope\",\"count\":2}").andExpect(status().isForbidden());
        send(patch("/api/questions/" + qid), asViewer, "{\"reviewStatus\":\"ACCEPTED\"}").andExpect(status().isForbidden());
        send(post("/api/testsets/" + id + "/bulk-accept"), asViewer, null).andExpect(status().isForbidden());
        send(post("/api/testsets/" + id + "/finalize"), asViewer, null).andExpect(status().isForbidden());

        Account stranger = register();
        send(get("/api/testsets/" + id), stranger, null).andExpect(status().isNotFound());
        send(get("/api/testsets/" + id + "/questions"), stranger, null).andExpect(status().isNotFound());
        send(patch("/api/questions/" + qid), stranger, "{\"reviewStatus\":\"ACCEPTED\"}").andExpect(status().isNotFound());
        send(post("/api/testsets/" + id + "/finalize"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/testsets/" + id + "/versions"), stranger, null).andExpect(status().isNotFound());
        assertThat(ok(send(get("/api/testsets"), stranger, null))).isEmpty();
        // the stranger's own workspace id with the owner's test set id: still nothing
        send(get("/api/testsets/" + id + "/diff?against=" + id), stranger, null).andExpect(status().isNotFound());
        // and another workspace header is refused for a non-member
        mvc.perform(get("/api/testsets").header("Authorization", "Bearer " + stranger.token()).header("X-Workspace-Id", owner.workspace())).andExpect(status().isNotFound());
        send(get("/api/testsets"), owner, null).andExpect(status().isOk());
        mvc.perform(get("/api/testsets")).andExpect(status().isUnauthorized());
    }
}
