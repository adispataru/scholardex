package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.service.reporting.ArtisticPerformanceSupport;
import ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H142 slice 7 — per record of a researcher, what keeps it from counting that the researcher can supply (Adrian,
 * 2026-10-03: the people whose records do not count fill in what is missing, or ask for it, rather than the platform
 * guessing): the role of a performance in an ensemble of five or more, or of unknown size; the journal's ISSN; the
 * classification request for a journal no list covers. Only missing data: a type a standard does not score (citations
 * in Music) is no gap, and a request a head is deciding shows with the request instead.
 */
@Service
@RequiredArgsConstructor
public class ActivityGapsFacade {

    public enum Gap { ROLE, ISSN, JOURNAL_UNLISTED }

    /** One thing a record lacks; {@code detail} names the ISSN for {@link Gap#JOURNAL_UNLISTED}. */
    public record RecordGap(Gap gap, String detail) {
    }

    private final ActivityInstanceRepository activityInstanceRepository;

    /** Record id → what it lacks, for the records that lack something. */
    public Map<String, List<RecordGap>> gapsFor(String researcherEmail) {
        Map<String, List<RecordGap>> out = new LinkedHashMap<>();
        if (researcherEmail == null || researcherEmail.isBlank()) {
            return out;
        }
        for (ActivityInstance record : activityInstanceRepository.findAllByResearcherId(researcherEmail)) {
            List<RecordGap> gaps = gapsOf(record);
            if (!gaps.isEmpty() && record.getId() != null) {
                out.put(record.getId(), gaps);
            }
        }
        return out;
    }

    static List<RecordGap> gapsOf(ActivityInstance record) {
        Activity type = record.getActivity();
        if (type == null) {
            return List.of();
        }
        Map<String, String> fields = record.getFields() == null ? Map.of() : record.getFields();
        Map<Activity.ReferenceField, String> references = record.getReferenceFields() == null ? Map.of() : record.getReferenceFields();
        List<Activity.ReferenceField> declared = type.getReferenceFields() == null ? List.of() : type.getReferenceFields();
        List<RecordGap> gaps = new ArrayList<>();
        // a performance counts with a role, unless the ensemble's size answers for it; a prize needs none
        if (declared.contains(Activity.ReferenceField.EVENT_NAME) && declares(type, ArtisticPerformanceSupport.FIELD_ROLE)
                && blank(fields.get(ArtisticPerformanceSupport.FIELD_ROLE))
                && ArtisticPerformanceSupport.PARTICIPATION.equals(ArtisticPerformanceSupport.result(fields))
                && !ArtisticPerformanceSupport.roleCounts(fields)) {
            gaps.add(new RecordGap(Gap.ROLE, null));
        }
        // a journal the lists classify by its ISSN, or a head by a request
        if (declared.contains(Activity.ReferenceField.FORUM_ISSN) && declares(type, PublisherClaim.REQUEST_FIELD)
                && !requested(record)) {
            String issn = references.get(Activity.ReferenceField.FORUM_ISSN);
            if (blank(issn)) {
                gaps.add(new RecordGap(Gap.ISSN, null));
            } else if (RegistryScoringSupport.journal(issn, record.getYear()).map(ActivityGapsFacade::unlisted).orElse(true)) {
                gaps.add(new RecordGap(Gap.JOURNAL_UNLISTED, issn.trim()));
            }
        }
        return gaps;
    }

    /** A request a head is deciding, or one approved for the record as it is now. */
    private static boolean requested(ActivityInstance record) {
        PublisherClaim claim = PublisherClaim.inForce(record);
        return claim != null && (claim.getStatus() == PublisherClaim.Status.PENDING
                || claim.getStatus() == PublisherClaim.Status.APPROVED);
    }

    /** In no database the platform knows: neither Web of Science nor Scopus, nor any list it holds. */
    private static boolean unlisted(RegistryScoringSupport.JournalFacts journal) {
        return !journal.webOfScience() && !journal.webOfScienceCore() && !journal.scopus() && journal.databases().isEmpty();
    }

    private static boolean declares(Activity type, String fieldName) {
        return type.getFields() != null && type.getFields().stream().anyMatch(f -> fieldName.equals(f.getName()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
