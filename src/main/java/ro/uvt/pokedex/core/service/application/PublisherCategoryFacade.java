package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.Activity;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.activities.PublisherClaim;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.service.reporting.PublisherCategorySupport;
import ro.uvt.pokedex.core.service.reporting.PublisherRules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * H143 — what the platform makes of the publisher of each declared book: under every standard whose indicators score
 * the book's activity type, the category the lists give (or an approved request), where it comes from, and the state
 * of the researcher's request. The researcher sees it on the record; a head sees it next to the request they decide.
 */
@Service
@RequiredArgsConstructor
public class PublisherCategoryFacade {

    /** One standard's verdict on a record. */
    public record StandardView(String rules, String label, String category, String basis, String detail,
                               String listedCategory) {
    }

    /** The request and its decision, as shown. */
    public record ClaimView(String status, String requested, String evidence, Instant requestedAt, String decidedBy,
                            Instant decidedAt, String note) {
    }

    /** One declared book: the publisher typed, each standard's verdict, the request. */
    public record RecordView(String activityId, String publisher, List<StandardView> standards, ClaimView claim) {
    }

    private final IndicatorRepository indicatorRepository;
    private final ActivityInstanceRepository activityInstanceRepository;

    /** The researcher's declared books whose type a standard with publisher rules scores, by record id. */
    public Map<String, RecordView> forResearcher(String researcherEmail) {
        Map<String, Set<PublisherRules>> rulesByType = rulesByActivityType();
        Map<String, RecordView> out = new LinkedHashMap<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(researcherEmail)) {
            RecordView view = view(instance, rulesByType);
            if (view != null) {
                out.put(instance.getId(), view);
            }
        }
        return out;
    }

    /** One record's view (null when no standard with publisher rules scores its type). */
    public RecordView view(ActivityInstance instance, Map<String, Set<PublisherRules>> rulesByType) {
        Activity type = instance.getActivity();
        if (type == null || type.getId() == null || !hasField(type, PublisherRules.FIELD_PUBLISHER)) {
            return null;
        }
        Set<PublisherRules> rules = rulesByType.get(type.getId());
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        Map<String, String> fields = instance.getFields() == null ? Map.of() : instance.getFields();
        String publisher = fields.get(PublisherRules.FIELD_PUBLISHER);
        List<StandardView> standards = new ArrayList<>();
        for (PublisherRules r : rules) {
            PublisherCategorySupport.Outcome outcome = PublisherCategorySupport.outcome(r, publisher,
                    instance.getPublisherClaim(), fields);
            standards.add(new StandardView(r.name(), r.label(), outcome.category(), outcome.basis(), outcome.detail(),
                    outcome.listed() == null ? null : outcome.listed().category()));
        }
        return new RecordView(instance.getId(), publisher, standards, claimView(instance.getPublisherClaim()));
    }

    /** The standards with publisher rules that score each activity type, by type id. */
    public Map<String, Set<PublisherRules>> rulesByActivityType() {
        Map<String, Set<PublisherRules>> out = new HashMap<>();
        for (Indicator indicator : indicatorRepository.findAll()) {
            Activity type = indicator.getActivity();
            if (type == null || type.getId() == null) {
                continue;
            }
            PublisherRules.of(indicator).ifPresent(r ->
                    out.computeIfAbsent(type.getId(), k -> EnumSet.noneOf(PublisherRules.class)).add(r));
        }
        return out;
    }

    static ClaimView claimView(PublisherClaim claim) {
        if (claim == null || claim.getStatus() == null) {
            return null;
        }
        return new ClaimView(claim.getStatus().name(), claim.getRequested(), claim.getEvidence(), claim.getRequestedAt(),
                claim.getDecidedBy(), claim.getDecidedAt(), claim.getDecisionNote());
    }

    private static boolean hasField(Activity type, String name) {
        return type.getFields() != null && type.getFields().stream().anyMatch(f -> name.equals(f.getName()));
    }
}
