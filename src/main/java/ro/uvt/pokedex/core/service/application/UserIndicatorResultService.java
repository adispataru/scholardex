package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.UserIndicatorResult;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.UserIndicatorResultRepository;
import ro.uvt.pokedex.core.service.UserService;
import ro.uvt.pokedex.core.service.application.model.IndicatorApplyResultDto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserIndicatorResultService {

    private final UserIndicatorResultRepository userIndicatorResultRepository;
    private final IndicatorRepository indicatorRepository;
    private final UserService userService;
    private final UserReportFacade userReportFacade;
    private final IndicatorPayloadSerializer payloadSerializer;

    public IndicatorApplyResultDto getOrCreateLatest(String userEmail, String indicatorId) {
        Optional<UserIndicatorResult> existing = userIndicatorResultRepository
                .findByUserEmailAndIndicatorIdAndMode(userEmail, indicatorId, UserIndicatorResult.Mode.LATEST);
        if (existing.isPresent()) {
            return toDto(existing.get(), IndicatorApplyResultDto.Source.PERSISTED);
        }
        return computeAndSaveLatest(userEmail, indicatorId, 0, IndicatorApplyResultDto.Source.COMPUTED);
    }

    public IndicatorApplyResultDto refreshLatest(String userEmail, String indicatorId) {
        Optional<UserIndicatorResult> existing = userIndicatorResultRepository
                .findByUserEmailAndIndicatorIdAndMode(userEmail, indicatorId, UserIndicatorResult.Mode.LATEST);
        int nextVersion = existing.map(value -> value.getRefreshVersion() + 1).orElse(1);
        return computeAndSaveLatest(userEmail, indicatorId, nextVersion, IndicatorApplyResultDto.Source.COMPUTED);
    }

    public UserIndicatorResult createSnapshotFromLatest(String userEmail, String indicatorId, String sourceReportId) {
        IndicatorApplyResultDto latest = getOrCreateLatest(userEmail, indicatorId);
        UserIndicatorResult snapshot = new UserIndicatorResult();
        Instant now = Instant.now();
        snapshot.setUserEmail(userEmail);
        snapshot.setResearcherId(userService.getUserByEmail(userEmail).map(ro.uvt.pokedex.core.model.user.User::getResearcherId).orElse(null));
        snapshot.setIndicatorId(indicatorId);
        snapshot.setMode(UserIndicatorResult.Mode.SNAPSHOT);
        snapshot.setSourceType(UserIndicatorResult.SourceType.REPORT_RUN);
        snapshot.setSourceReportId(sourceReportId);
        snapshot.setFingerprint(buildFingerprint(indicatorId));
        snapshot.setViewName(latest.viewName());
        snapshot.setRawGraph(payloadSerializer.serialize(latest.rawGraph()));
        snapshot.setTotalScore(latest.summary().totalScore());
        snapshot.setTotalCount(latest.summary().totalCount());
        snapshot.setQuarterLabels(latest.summary().quarterLabels());
        snapshot.setQuarterValues(latest.summary().quarterValues());
        snapshot.setCreatedAt(now);
        snapshot.setUpdatedAt(now);
        snapshot.setRefreshVersion(latest.refreshVersion());
        return userIndicatorResultRepository.save(snapshot);
    }

    public Optional<IndicatorApplyResultDto> getById(String id) {
        return userIndicatorResultRepository.findById(id).map(value -> toDto(value, IndicatorApplyResultDto.Source.PERSISTED));
    }

    private IndicatorApplyResultDto computeAndSaveLatest(String userEmail,
                                                         String indicatorId,
                                                         int refreshVersion,
                                                         IndicatorApplyResultDto.Source source) {
        ro.uvt.pokedex.core.service.application.model.UserIndicatorApplyViewModel computed =
                userReportFacade.buildIndicatorApplyView(userEmail, indicatorId);

        Map<String, Object> attrs = computed.attributes();
        if (!attrs.containsKey("indicator")) {
            return new IndicatorApplyResultDto(
                    null,
                    indicatorId,
                    computed.viewName(),
                    attrs,
                    new IndicatorApplyResultDto.Summary(0.0, 0, List.of(), List.of()),
                    source,
                    null,
                    null,
                    refreshVersion
            );
        }

        UserIndicatorResult entity = userIndicatorResultRepository
                .findByUserEmailAndIndicatorIdAndMode(userEmail, indicatorId, UserIndicatorResult.Mode.LATEST)
                .orElse(new UserIndicatorResult());

        Instant now = Instant.now();
        entity.setUserEmail(userEmail);
        entity.setResearcherId(userService.getUserByEmail(userEmail).map(ro.uvt.pokedex.core.model.user.User::getResearcherId).orElse(null));
        entity.setIndicatorId(indicatorId);
        entity.setMode(UserIndicatorResult.Mode.LATEST);
        entity.setSourceType(UserIndicatorResult.SourceType.APPLY_PAGE);
        entity.setSourceReportId(null);
        entity.setFingerprint(buildFingerprint(indicatorId));
        entity.setViewName(computed.viewName());
        entity.setRawGraph(payloadSerializer.serialize(attrs));

        IndicatorApplyResultDto.Summary summary = extractSummary(attrs);
        entity.setTotalScore(summary.totalScore());
        entity.setTotalCount(summary.totalCount());
        entity.setQuarterLabels(summary.quarterLabels());
        entity.setQuarterValues(summary.quarterValues());

        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        entity.setRefreshVersion(refreshVersion);

        UserIndicatorResult saved = userIndicatorResultRepository.save(entity);
        return toDto(saved, source);
    }

    private IndicatorApplyResultDto toDto(UserIndicatorResult entity, IndicatorApplyResultDto.Source source) {
        return new IndicatorApplyResultDto(
                entity.getId(),
                entity.getIndicatorId(),
                entity.getViewName(),
                payloadSerializer.deserialize(entity.getRawGraph()),
                new IndicatorApplyResultDto.Summary(
                        entity.getTotalScore(),
                        entity.getTotalCount(),
                        entity.getQuarterLabels() == null ? List.of() : entity.getQuarterLabels(),
                        entity.getQuarterValues() == null ? List.of() : entity.getQuarterValues()
                ),
                source,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getRefreshVersion()
        );
    }

    private String buildFingerprint(String indicatorId) {
        Optional<Indicator> indicator = indicatorRepository.findById(indicatorId);
        if (indicator.isEmpty()) {
            return "MISSING_INDICATOR";
        }
        Indicator ind = indicator.get();
        return String.join(
                "|",
                ind.getId() == null ? "" : ind.getId(),
                ind.getOutputType() == null ? "" : ind.getOutputType().name(),
                ind.getScoringStrategy() == null ? "" : ind.getScoringStrategy().name(),
                ind.getFormula() == null ? "" : ind.getFormula(),
                ind.getYearRange() == null ? "" : ind.getYearRange(),
                ind.getScoreYearRange() == null ? "" : ind.getScoreYearRange(),
                ind.getSelector() == null ? "" : ind.getSelector().name()
        );
    }

    private IndicatorApplyResultDto.Summary extractSummary(Map<String, Object> attrs) {
        double totalScore = parseDouble(attrs.get("total"));

        Integer totalCount = null;
        Object totalCountObj = attrs.get("totalCit");
        if (totalCountObj != null) {
            try {
                totalCount = Integer.parseInt(String.valueOf(totalCountObj));
            } catch (NumberFormatException ignored) {
                // totalCount remains null from initialization
            }
        }

        List<String> quarterLabels = new ArrayList<>();
        Object labelsObj = attrs.get("allQuarters");
        if (labelsObj instanceof Iterable<?> iterable) {
            iterable.forEach(e -> quarterLabels.add(String.valueOf(e)));
        }

        List<Integer> quarterValues = new ArrayList<>();
        Object valuesObj = attrs.get("allValues");
        if (valuesObj instanceof Iterable<?> iterable) {
            iterable.forEach(e -> {
                try {
                    quarterValues.add(Integer.parseInt(String.valueOf(e)));
                } catch (NumberFormatException ignored) {
                    quarterValues.add(0);
                }
            });
        }

        return new IndicatorApplyResultDto.Summary(totalScore, totalCount, quarterLabels, quarterValues);
    }

    private double parseDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }

        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return 0.0;
        }

        String normalized = raw.replace("\u00A0", "").replace(" ", "");
        if (normalized.contains(",") && normalized.contains(".")) {
            if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
                normalized = normalized.replace(".", "").replace(",", ".");
            } else {
                normalized = normalized.replace(",", "");
            }
        } else if (normalized.contains(",")) {
            normalized = normalized.replace(",", ".");
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }
}
