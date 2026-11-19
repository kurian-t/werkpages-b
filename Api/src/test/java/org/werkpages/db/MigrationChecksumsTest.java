package org.werkpages.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A RELEASED MIGRATION NEVER CHANGES AGAIN, AND THIS IS WHAT NOTICES.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>On 2026-11-17 a narrowing CHECK was folded into V109 after a development database had already
 * applied it. Flyway records a migration's checksum when it runs it and compares on every later
 * start, so the next boot did not produce a wrong schema - it produced no backend at all:
 *
 * <pre>
 *   Migration checksum mismatch for migration version 109
 *   -&gt; Applied to database : 1991302054
 *   -&gt; Resolved locally    : -163065755
 * </pre>
 *
 * <p>The application could not start for the rest of the afternoon. Every sign-in returned HTTP
 * 500, because the thing behind the proxy was simply not running.
 *
 * <h2>Why nothing caught it</h2>
 *
 * <p>There were already migration tests - {@code Phase2FreshInstallIntegrationTest},
 * {@code MigrationFailFastIntegrationTest} - and nearly six thousand backend tests besides. Every
 * one of them builds a database FROM SCRATCH. A fresh database has no history, so there is nothing
 * for an edited file to contradict, and the entire suite stayed green while the application was
 * unstartable.
 *
 * <p>That is the shape worth remembering: the defect was not in any schema or any query. It was a
 * disagreement between a file and a database that already existed, and only a record of what was
 * released can see it.
 *
 * <h2>What this test does</h2>
 *
 * <p>{@code db/migration-checksums.txt} is that record, committed beside the migrations. This
 * compares it with the files on disk, using Flyway's own checksum, and fails when a migration that
 * has been released has moved - naming the file and saying what to do instead.
 *
 * <p>It is a unit test on purpose. No container, no database, a few milliseconds: it runs in every
 * build and in CI, which is the only way a guard like this survives.
 */
@DisplayName("Released migrations are immutable")
class MigrationChecksumsTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migrations");
    private static final Path MANIFEST   = Paths.get("src/main/resources/db/migration-checksums.txt");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");

    /**
     * Flyway's own checksum, so the numbers here are the numbers in its error messages.
     *
     * <p>{@code ChecksumCalculator} runs CRC32 over each line with the line terminator stripped,
     * which is what makes the value insensitive to CRLF versus LF while remaining sensitive to
     * every character that matters. Reimplemented rather than called so this test needs no Flyway
     * classpath and cannot be broken by a Flyway upgrade changing an internal signature.
     */
    private static int flywayChecksum(Path file) {
        CRC32 crc = new CRC32();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> crc.update(line.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + file, e);
        }
        return (int) crc.getValue();
    }

    private static Map<String, Integer> onDisk() {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            Map<String, Integer> out = new LinkedHashMap<>();
            files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches())
                 .sorted(Comparator.comparingInt(MigrationChecksumsTest::versionOf))
                 .forEach(p -> out.put(p.getFileName().toString(), flywayChecksum(p)));
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + MIGRATIONS, e);
        }
    }

    private static int versionOf(Path p) {
        Matcher m = VERSIONED.matcher(p.getFileName().toString());
        if (!m.matches()) throw new IllegalArgumentException("not a versioned migration: " + p);
        return Integer.parseInt(m.group(1));
    }

    private static Map<String, Integer> manifest() {
        try {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                String[] parts = trimmed.split("\\s+");
                if (parts.length != 2) fail("malformed line in " + MANIFEST + ": " + line);
                out.put(parts[0], Integer.parseInt(parts[1]));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + MANIFEST, e);
        }
    }

    @Test
    @DisplayName("no migration that has been released has changed since it was released")
    void noReleasedMigrationHasChanged() {
        Map<String, Integer> disk = onDisk();
        Map<String, Integer> recorded = manifest();

        List<String> changed = new ArrayList<>();
        for (var entry : recorded.entrySet()) {
            Integer now = disk.get(entry.getKey());
            if (now == null) {
                changed.add(String.format(
                    "  %s has been DELETED or RENAMED, and it is recorded as released.%n"
                    + "      Flyway reports a deleted applied migration as a validation failure, so"
                    + " every database%n"
                    + "      that ran it would refuse to start.",
                    entry.getKey()));
            } else if (!now.equals(entry.getValue())) {
                changed.add(String.format(
                    "  %s HAS BEEN EDITED.%n"
                    + "      recorded when released : %d%n"
                    + "      on disk now            : %d%n"
                    + "      Every database that already applied it will refuse to start with%n"
                    + "      \"Migration checksum mismatch for migration version %d\". Put the change in"
                    + " a NEW%n"
                    + "      migration instead, and leave this file alone.",
                    entry.getKey(), entry.getValue(), now, versionNumber(entry.getKey())));
            }
        }

        if (!changed.isEmpty()) {
            fail(String.format(
                "%d released migration(s) no longer match what was released:%n%n%s%n%n"
                + "This is the defect that stopped the backend booting on 2026-11-17. A migration"
                + " is a%n"
                + "record of something that already happened to a database; it is not a file to"
                + " revise.%n"
                + "There is no rollback in production once a version is in flyway_schema_history.",
                changed.size(), String.join(System.lineSeparator(), changed)));
        }
    }

    @Test
    @DisplayName("every migration on disk is recorded, so a new one cannot slip past unnoticed")
    void everyMigrationIsRecorded() {
        Map<String, Integer> disk = onDisk();
        Map<String, Integer> recorded = manifest();

        List<String> missing = new ArrayList<>();
        disk.forEach((name, sum) -> {
            if (!recorded.containsKey(name)) missing.add(String.format("%s  %d", name, sum));
        });

        if (!missing.isEmpty()) {
            fail(String.format(
                "%d migration(s) are not recorded in %s.%n%n"
                + "If you have just ADDED these, append exactly these lines to that file:%n%n%s%n%n"
                + "Appending is a deliberate act, and that is the point: it is the moment to be"
                + " sure the%n"
                + "file is final, because after it runs anywhere it can never change again.",
                missing.size(), MANIFEST, String.join(System.lineSeparator(), missing)));
        }
    }

    @Test
    @DisplayName("the version sequence is contiguous, so no number is skipped or reused")
    void versionsAreContiguousAndUnique() {
        List<Integer> versions;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            versions = files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches())
                            .map(MigrationChecksumsTest::versionOf)
                            .sorted()
                            .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not list " + MIGRATIONS, e);
        }

        assertTrue(versions.size() > 100, "the migration folder looks truncated: " + versions.size());

        for (int i = 0; i < versions.size(); i++) {
            int expected = i + 1;
            if (versions.get(i) != expected) {
                /*
                  A gap means a migration was deleted; a duplicate means two developers claimed the
                  same number, and whichever reaches a database first makes the other unapplicable
                  forever. Both are cheap to find here and expensive to find in a deployment.
                */
                fail(String.format(
                    "the migration sequence breaks at V%d: expected V%d.%n"
                    + "A gap means one was removed. A repeat means two changes claimed the same"
                    + " version, and%n"
                    + "only the first to reach a database can ever apply.",
                    versions.get(i), expected));
            }
        }
    }

    private static int versionNumber(String fileName) {
        Matcher m = VERSIONED.matcher(fileName);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }
}
