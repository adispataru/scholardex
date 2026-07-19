package ro.uvt.pokedex.core.service.importing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Confines the admin path-based reference-data importers (CiteScore/DOAJ/ERIH) to an allow-listed
 * set of directories. The path arrives as an admin-typed request parameter; without this guard a
 * (compromised) admin session could point the parsers at arbitrary server files (CodeQL
 * java/path-injection #32–#34). Roots are configurable for ops layouts — the defaults cover the
 * repo's {@code data/} directory in dev and the {@code /app/data} PVC mount on the cluster.
 */
@Component
public class ImportPathGuard {

    private final List<String> allowedRoots;

    public ImportPathGuard(
            @Value("${core.importing.allowed-import-roots:data,/app/data}") String allowedRootsCsv) {
        List<String> roots = new ArrayList<>();
        for (String root : allowedRootsCsv.split(",")) {
            if (!root.isBlank()) {
                roots.add(root.trim());
            }
        }
        this.allowedRoots = List.copyOf(roots);
    }

    /**
     * Resolve {@code rawPath} to its canonical file, verifying it sits under one of the allowed
     * roots (canonical prefix check — symlinks and {@code ..} segments cannot escape).
     *
     * @throws IllegalArgumentException when the path escapes every allowed root or cannot be resolved
     */
    public File resolveWithinAllowedRoots(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Import path is required.");
        }
        try {
            File canonical = new File(rawPath).getCanonicalFile();
            for (String root : allowedRoots) {
                File canonicalRoot = new File(root).getCanonicalFile();
                // Component-wise prefix check on the CANONICAL paths (symlinks and `..` already
                // resolved) — Path.startsWith never matches partial file names and handles the
                // filesystem root correctly, unlike string prefixing with a separator.
                if (canonical.toPath().startsWith(canonicalRoot.toPath())) {
                    return canonical;
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Import path could not be resolved: " + e.getMessage());
        }
        throw new IllegalArgumentException(
                "Import path is outside the allowed import roots " + allowedRoots
                        + " — configure core.importing.allowed-import-roots to widen them.");
    }
}
