package com.hms.imaging.service;

import com.hms.common.error.BadRequestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where the pixels go, when anywhere.
 *
 * <p>Unconfigured by default, and that is the shipped state rather than an oversight: this platform
 * is a RIS and not an archive, so with no {@code hms.imaging.storage-dir} a study registers in full
 * — every UID, the series, the instance geometry — and carries no {@code storage_uri}. The screens
 * say "registered, no image stored" rather than implying a file exists. Point the property at a
 * directory, or at a mount backed by an object store, and the bytes are written and the URI
 * recorded.
 *
 * <p>The same posture as {@code LoggingChannel} in notification-service and the default ABDM
 * gateway: a working default, one adapter, and no pretence that the unwired half is wired.
 *
 * <p><strong>The layout is UID-addressed, not name-addressed.</strong> A file lands at
 * {@code <dir>/<studyUid>/<seriesUid>/<sopUid>.dcm}, so re-sending the same instance overwrites
 * itself rather than accumulating copies, and nothing in the path comes from a filename the caller
 * chose. That second half matters more than the first: a multipart filename is caller-controlled
 * text, and a path built from one is the classic traversal. UIDs are validated before they are used
 * as path segments, because "validated by the format" is not the same as validated.
 */
@Component
public class ImageStore {

    private static final Logger log = LoggerFactory.getLogger(ImageStore.class);

    /**
     * What a DICOM UID is: digits in dot-separated components, up to 64 characters.
     *
     * <p>Checked rather than trusted. A UID reaches this class straight out of a file somebody
     * uploaded and is about to become a directory name, so anything that is not a UID is refused
     * before it can be a path segment.
     *
     * <p>Written as components rather than as a character class, and that is not pedantry: the
     * first version of this was {@code [0-9.]{1,64}}, which <strong>matches {@code ..}</strong> —
     * dots are in the class and nothing required a digit anywhere. {@code ImageStoreTest} caught
     * it, and the traversal it allowed slipped past the normalisation check too, because {@code ..}
     * from inside a subdirectory still lands under the archive root. So the two checks in this
     * class were never as independent as the comment below claimed; this one is the one that has to
     * be right.
     */
    private static final String UID_PATTERN = "\\d+(\\.\\d+)*";

    /** A UID is at most 64 characters, per the standard. Checked separately so the regex stays readable. */
    private static final int MAX_UID_LENGTH = 64;

    private final Path root;

    public ImageStore(@Value("${hms.imaging.storage-dir:}") String storageDir) {
        this.root = storageDir == null || storageDir.isBlank() ? null : Path.of(storageDir.trim());
        if (root == null) {
            log.info("No imaging archive configured (hms.imaging.storage-dir is unset): studies "
                    + "will be registered without stored images");
        } else {
            log.info("Imaging archive at {}", root);
        }
    }

    public boolean isConfigured() {
        return root != null;
    }

    /**
     * Stores one instance and returns where it went, or empty when no archive is configured.
     *
     * @throws BadRequestException if a UID is not a UID — refused rather than sanitised, because a
     *                             file whose identifiers cannot be trusted is a file whose contents
     *                             cannot be either.
     */
    public Optional<String> store(String studyUid, String seriesUid, String sopUid, byte[] bytes) {
        requireUid(studyUid, "study instance UID");
        requireUid(seriesUid, "series instance UID");
        requireUid(sopUid, "SOP instance UID");
        if (root == null) {
            return Optional.empty();
        }
        Path directory = root.resolve(studyUid).resolve(seriesUid);
        Path file = directory.resolve(sopUid + ".dcm");
        // A second check on the resolved path, kept even though the pattern above now makes a
        // traversal unrepresentable. Cheap, and the consequence of the pattern being wrong is
        // writing anywhere this service can reach -- which is exactly what happened to the first
        // version of that pattern, and is why this line is not the one being relied on.
        if (!file.normalize().startsWith(root.normalize())) {
            throw new BadRequestException("That study's identifiers do not name a storable path");
        }
        try {
            Files.createDirectories(directory);
            Files.write(file, bytes);
        } catch (IOException ex) {
            // Not swallowed and not turned into a refusal of the whole upload: the study is worth
            // registering even if the archive is full or unwritable, and a radiographer who is told
            // "nothing was saved" when the record was in fact created would send it again.
            throw new UncheckedIOException("Could not write the instance to the archive", ex);
        }
        return Optional.of(file.toUri().toString());
    }

    private static void requireUid(String value, String what) {
        if (value == null || value.length() > MAX_UID_LENGTH || !value.matches(UID_PATTERN)) {
            throw new BadRequestException(
                    "The %s in this file is missing or not a DICOM UID".formatted(what));
        }
    }
}
