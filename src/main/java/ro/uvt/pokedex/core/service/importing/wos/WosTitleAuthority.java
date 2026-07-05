package ro.uvt.pokedex.core.service.importing.wos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.wos.WosSourceType;
import ro.uvt.pokedex.core.service.importing.wos.model.WosParsedRecord;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory naming reference built from the ingested JCR matrix ({@link WosSourceType#JCR_REFERENCE} records:
 * Title20 abbreviation → full title). JCR rows carry no ISSN, so they never seed identities; instead the
 * identity-resolution service consults this authority to (a) recognize an incoming title as a WoS
 * abbreviation and substitute the full title, and (b) attach {@code abbreviatedTitle} to identities whose
 * full title is known to JCR. Loaded by the fact builder at the start of a build run; empty (all lookups
 * miss) when no JCR reference has been ingested, which degrades to the legacy naming behavior.
 */
@Component
public class WosTitleAuthority {
    private static final Logger log = LoggerFactory.getLogger(WosTitleAuthority.class);

    /** One JCR matrix row: the Title20 abbreviation and the journal's full title. */
    public record ReferenceEntry(String abbreviatedTitle, String fullTitle) {
    }

    private volatile Map<String, ReferenceEntry> byAbbreviationFingerprint = Map.of();
    private volatile Map<String, ReferenceEntry> byFullTitleFingerprint = Map.of();

    /** Rebuild the reference from JCR-parsed records (title = full title, abbreviatedTitle = Title20). */
    public void load(Collection<WosParsedRecord> jcrRecords) {
        Map<String, ReferenceEntry> byAbbreviation = new LinkedHashMap<>();
        Map<String, ReferenceEntry> byFullTitle = new LinkedHashMap<>();
        if (jcrRecords != null) {
            for (WosParsedRecord record : jcrRecords) {
                if (record == null || record.sourceType() != WosSourceType.JCR_REFERENCE) {
                    continue;
                }
                String abbreviationFingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(record.abbreviatedTitle());
                String fullTitleFingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(record.title());
                if (abbreviationFingerprint == null || fullTitleFingerprint == null) {
                    continue;
                }
                ReferenceEntry entry = new ReferenceEntry(record.abbreviatedTitle().trim(), record.title().trim());
                byAbbreviation.putIfAbsent(abbreviationFingerprint, entry);
                byFullTitle.putIfAbsent(fullTitleFingerprint, entry);
            }
        }
        this.byAbbreviationFingerprint = Map.copyOf(byAbbreviation);
        this.byFullTitleFingerprint = Map.copyOf(byFullTitle);
        log.info("WoS title authority loaded: abbreviations={} fullTitles={}", byAbbreviation.size(), byFullTitle.size());
    }

    public boolean isLoaded() {
        return !byAbbreviationFingerprint.isEmpty();
    }

    /** The JCR entry whose Title20 matches the given raw title, or null. */
    public ReferenceEntry byAbbreviation(String rawTitle) {
        String fingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        return fingerprint == null ? null : byAbbreviationFingerprint.get(fingerprint);
    }

    /** The JCR entry whose full title matches the given raw title, or null. */
    public ReferenceEntry byFullTitle(String rawTitle) {
        String fingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        return fingerprint == null ? null : byFullTitleFingerprint.get(fingerprint);
    }

    /**
     * True when the raw title is a JCR abbreviation of a strictly different full title (short journal names
     * whose Title20 equals the full title, e.g. "NATURE", are NOT abbreviations).
     */
    public boolean isAbbreviation(String rawTitle) {
        ReferenceEntry entry = byAbbreviation(rawTitle);
        if (entry == null) {
            return false;
        }
        String rawFingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(rawTitle);
        String fullFingerprint = WosCanonicalContractSupport.normalizeTitleFingerprint(entry.fullTitle());
        return rawFingerprint != null && !rawFingerprint.equals(fullFingerprint);
    }
}
