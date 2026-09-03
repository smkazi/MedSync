package com.hms.imaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.common.error.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The archive adapter: both of its states, and the path handling.
 *
 * <p>Unit-level and on purpose. The unconfigured case is covered by every integration test in the
 * module — it is the default — so what needs its own tests is the half that writes files, and the
 * refusals that stop a caller-supplied UID becoming a path.
 */
class ImageStoreTest {

    private static final String STUDY = "1.2.826.0.1.3680043.8.498.111";
    private static final String SERIES = "1.2.826.0.1.3680043.8.498.222";
    private static final String SOP = "1.2.826.0.1.3680043.8.498.333";

    @Test
    @DisplayName("with no directory configured it stores nothing and says so")
    void unconfiguredStoresNothing() {
        ImageStore store = new ImageStore("");

        assertThat(store.isConfigured()).isFalse();
        // Empty rather than an exception: a study registers in full without an archive, which is
        // the shipped default for a RIS. Refusing here would make the default deployment unable to
        // receive images at all.
        assertThat(store.store(STUDY, SERIES, SOP, new byte[] {1, 2, 3})).isEmpty();
    }

    @Test
    @DisplayName("with a directory configured it writes the file and returns where it went")
    void configuredWritesTheFile(@TempDir Path archive) throws IOException {
        ImageStore store = new ImageStore(archive.toString());
        byte[] bytes = {1, 2, 3, 4};

        Optional<String> uri = store.store(STUDY, SERIES, SOP, bytes);

        assertThat(store.isConfigured()).isTrue();
        assertThat(uri).isPresent();
        // UID-addressed, so the layout is derivable from the identifiers rather than from a
        // filename the caller chose.
        Path expected = archive.resolve(STUDY).resolve(SERIES).resolve(SOP + ".dcm");
        assertThat(expected).exists();
        assertThat(Files.readAllBytes(expected)).isEqualTo(bytes);
        assertThat(uri.get()).isEqualTo(expected.toUri().toString());
    }

    @Test
    @DisplayName("re-storing the same instance overwrites it rather than accumulating copies")
    void reStoringOverwrites(@TempDir Path archive) throws IOException {
        ImageStore store = new ImageStore(archive.toString());

        store.store(STUDY, SERIES, SOP, new byte[] {1});
        store.store(STUDY, SERIES, SOP, new byte[] {2, 2});

        Path file = archive.resolve(STUDY).resolve(SERIES).resolve(SOP + ".dcm");
        assertThat(Files.readAllBytes(file)).isEqualTo(new byte[] {2, 2});
        try (var children = Files.list(archive.resolve(STUDY).resolve(SERIES))) {
            assertThat(children.count()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a UID that is not a UID is refused, configured or not")
    void aUidThatIsNotAUidIsRefused(@TempDir Path archive) {
        // Refused in both states, because the check is about the identifiers rather than about the
        // filesystem: a file whose identifiers cannot be trusted is a file whose contents cannot
        // be either, and registering it would be worse than refusing it.
        for (ImageStore store : new ImageStore[] {new ImageStore(archive.toString()),
                new ImageStore("")}) {
            // ".." and "." are the two that matter, and the two the first version of the pattern
            // let through: it was a dots-and-digits character class, so a UID made only of dots
            // satisfied it -- and the traversal survived the path-normalisation check too, because
            // ".." from inside a subdirectory still lands under the archive root.
            for (String hostile : new String[] {"../../etc/passwd", "..", ".", "...", ".1.2",
                    "1.2.", "1..2", "a/b", "a\\b", "", "1.2.3;rm -rf /", "1.2.3 ",
                    "1.2.3\u0000"}) {
                assertThatThrownBy(() -> store.store(hostile, SERIES, SOP, new byte[] {1}))
                        .as("study UID '%s'", hostile)
                        .isInstanceOf(BadRequestException.class);
                assertThatThrownBy(() -> store.store(STUDY, hostile, SOP, new byte[] {1}))
                        .as("series UID '%s'", hostile)
                        .isInstanceOf(BadRequestException.class);
                assertThatThrownBy(() -> store.store(STUDY, SERIES, hostile, new byte[] {1}))
                        .as("SOP UID '%s'", hostile)
                        .isInstanceOf(BadRequestException.class);
            }
        }
    }

    @Test
    @DisplayName("nothing is written outside the archive")
    void nothingEscapesTheArchive(@TempDir Path parent) throws IOException {
        Path archive = parent.resolve("archive");
        Files.createDirectories(archive);
        ImageStore store = new ImageStore(archive.toString());

        // A traversal cannot get past the UID pattern, so this asserts the outcome rather than the
        // mechanism: whatever a caller sends, every file written lands under the configured root.
        store.store(STUDY, SERIES, SOP, new byte[] {1});

        try (var walk = Files.walk(parent)) {
            assertThat(walk.filter(Files::isRegularFile))
                    .allSatisfy(file -> assertThat(file).startsWith(archive));
        }
    }
}
