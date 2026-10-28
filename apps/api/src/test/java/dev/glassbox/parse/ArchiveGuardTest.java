package dev.glassbox.parse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.MoreTestFiles;
import dev.glassbox.TestFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ArchiveGuardTest {
    private static byte[] zip(int level, Entry... entries) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream z = new ZipOutputStream(out)) {
                z.setLevel(level);
                for (Entry e : entries) {
                    z.putNextEntry(new ZipEntry(e.name));
                    z.write(e.data);
                    z.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private record Entry(String name, byte[] data) {}

    @Test
    void ordinaryOfficeFilesAndNonZipFilesPass() {
        assertThat(ArchiveGuard.problem(TestFiles.docx())).isEmpty();
        assertThat(ArchiveGuard.problem(MoreTestFiles.xlsx())).isEmpty();
        assertThat(ArchiveGuard.problem(MoreTestFiles.pptx())).isEmpty();
        assertThat(ArchiveGuard.problem("# plain markdown".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ArchiveGuard.problem(TestFiles.pdf("page one"))).isEmpty();
        assertThat(ArchiveGuard.problem(new byte[] {'P', 'K', 3, 4, 1, 2})).as("a truncated zip is the parser's to report").isEmpty();
    }

    @Test
    void aHugeExpansionRatioIsRefusedAfterReadingOnlyAsMuchAsNeeded() {
        byte[] zeros = new byte[40 * 1024 * 1024];
        byte[] bomb = zip(9, new Entry("word/document.xml", zeros));
        assertThat(bomb.length).as("40 MB of zeros is a tiny file").isLessThan(100_000);
        assertThat(ArchiveGuard.problem(bomb)).hasValueSatisfying(why -> assertThat(why).contains("times or more"));
    }

    @Test
    void anAbsoluteSizeLimitAppliesEvenToAFileThatDoesNotCompressSoWell() {
        byte[] noisy = new byte[3 * 1024 * 1024];
        new java.util.Random(7).nextBytes(noisy);
        byte[] zip = zip(0, new Entry("a.bin", noisy), new Entry("b.bin", noisy));
        assertThat(ArchiveGuard.problem(zip, 100, 4L * 1024 * 1024, 1000)).hasValueSatisfying(why -> assertThat(why).contains("expands to more than 4 MB"));
        assertThat(ArchiveGuard.problem(zip, 100, 64L * 1024 * 1024, 1000)).isEmpty();
    }

    @Test
    void tooManyPartsAreRefused() {
        Entry[] many = new Entry[60];
        for (int i = 0; i < many.length; i++) many[i] = new Entry("part" + i + ".xml", new byte[] {'x'});
        assertThat(ArchiveGuard.problem(zip(6, many), 50, ArchiveGuard.MAX_EXPANDED_BYTES, ArchiveGuard.MAX_RATIO)).hasValueSatisfying(why -> assertThat(why).contains("more than 50 parts"));
        assertThat(ArchiveGuard.problem(zip(6, many), 60, ArchiveGuard.MAX_EXPANDED_BYTES, ArchiveGuard.MAX_RATIO)).isEmpty();
    }

    @Test
    void anArchiveHiddenInsideAnArchiveIsRefused() {
        byte[] inner = zip(6, new Entry("x.txt", "hello".getBytes(StandardCharsets.UTF_8)));
        for (String name : new String[] {"word/embeddings/inner.zip", "lib/helper.jar", "data.tar", "backup.7z", "x.GZ"}) {
            assertThat(ArchiveGuard.problem(zip(6, new Entry(name, inner)))).as(name).hasValueSatisfying(why -> assertThat(why).contains("another archive"));
        }
        assertThat(ArchiveGuard.problem(MoreTestFiles.zip(Map.of("a.txt", "x")))).isEmpty();
    }
}
