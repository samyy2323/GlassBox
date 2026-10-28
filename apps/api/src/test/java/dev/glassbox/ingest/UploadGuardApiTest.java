package dev.glassbox.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.glassbox.TestFiles;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** SPEC 12 upload checks through the real endpoint: a decompression bomb is refused with 422 before it is stored or queued. */
class UploadGuardApiTest extends ApiTestSupport {
    private static byte[] bomb() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.setLevel(9);
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes());
            z.closeEntry();
            z.putNextEntry(new ZipEntry("word/document.xml"));
            z.write(new byte[40 * 1024 * 1024]);
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void aBombIsRefusedAndLeavesNothingBehindWhileARealDocumentStillGoesThrough() throws Exception {
        Account a = register();
        byte[] bomb = bomb();
        assertThat(bomb.length).isLessThan(100_000);
        upload(a, "report.docx", bomb).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("refused")));
        assertThat(countRows(a, "documents")).isZero();
        assertThat(countJobs("WHERE true")).isZero();

        // the same bytes renamed are refused too: the check looks at the content, not at the name
        upload(a, "notes.md", bomb).andExpect(status().isUnprocessableEntity());
        uploadOk(a, "real.docx", TestFiles.docx());
        assertThat(countRows(a, "documents")).isEqualTo(1);
    }
}
