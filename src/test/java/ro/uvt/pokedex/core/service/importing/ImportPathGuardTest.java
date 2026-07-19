package ro.uvt.pokedex.core.service.importing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportPathGuardTest {

    @Test
    void acceptsFilesUnderAnAllowedRootAndCanonicalizes(@TempDir Path root) throws IOException {
        Path file = Files.writeString(root.resolve("doaj.csv"), "x");
        ImportPathGuard guard = new ImportPathGuard(root.toString());

        File resolved = guard.resolveWithinAllowedRoots(file.toString());

        assertEquals(file.toFile().getCanonicalFile(), resolved);
        // Non-canonical spellings of an in-root path resolve, too.
        assertEquals(file.toFile().getCanonicalFile(),
                guard.resolveWithinAllowedRoots(root + File.separator + "." + File.separator + "doaj.csv"));
    }

    @Test
    void rejectsDotDotEscapesAndOutsidePaths(@TempDir Path root) throws IOException {
        ImportPathGuard guard = new ImportPathGuard(root.resolve("data").toString());
        Files.createDirectories(root.resolve("data"));
        Path secret = Files.writeString(root.resolve("secret.txt"), "x");

        assertThrows(IllegalArgumentException.class,
                () -> guard.resolveWithinAllowedRoots(root.resolve("data") + File.separator + ".." + File.separator + "secret.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.resolveWithinAllowedRoots(secret.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> guard.resolveWithinAllowedRoots("/etc/passwd"));
    }

    @Test
    void rejectsSymlinksPointingOutsideTheRoot(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("data"));
        Path secret = Files.writeString(root.resolve("secret.txt"), "x");
        Path link = allowed.resolve("sneaky.csv");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to prove here
        }
        ImportPathGuard guard = new ImportPathGuard(allowed.toString());

        assertThrows(IllegalArgumentException.class, () -> guard.resolveWithinAllowedRoots(link.toString()));
    }

    @Test
    void supportsMultipleCommaSeparatedRoots(@TempDir Path a, @TempDir Path b) throws IOException {
        Path inB = Files.writeString(b.resolve("erih.jsonl"), "x");
        ImportPathGuard guard = new ImportPathGuard(a + "," + b);

        assertEquals(inB.toFile().getCanonicalFile(), guard.resolveWithinAllowedRoots(inB.toString()));
    }

    @Test
    void rejectsBlankPaths() {
        ImportPathGuard guard = new ImportPathGuard("data");
        assertThrows(IllegalArgumentException.class, () -> guard.resolveWithinAllowedRoots(" "));
        assertThrows(IllegalArgumentException.class, () -> guard.resolveWithinAllowedRoots(null));
    }
}
