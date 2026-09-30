package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.activities.ActivityInstance;
import ro.uvt.pokedex.core.model.reporting.AbstractReport;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.ReportAuthority;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetHeader;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.user.StaffRecord;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.ActivityInstanceRepository;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetHeaderRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetSnapshotRepository;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;
import ro.uvt.pokedex.core.service.application.model.CnfisSheetViewModel;
import ro.uvt.pokedex.core.service.application.model.IndividualReportRunDto;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CnfisDomainCatalog;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * H129 — the CNFIS page: the editions of the reporting, and for one edition the person's Anexa 5 as the
 * platform fills it (rows, left-out publications, patents), the head of the sheet the person fills in
 * (domain, CNATDCU score, Hirsch values), the frozen copies, and the download of either.
 */
@Service
@RequiredArgsConstructor
public class CnfisReportingFacade {

    /** The declared activity type whose instances are the patents of Anexa 5. */
    static final String PATENT_ACTIVITY = "Brevet";
    static final String FIELD_AUTHORS = "N_autori";
    static final String FIELD_TYPE = "Tip";
    static final String FIELD_CODE = "Cod brevet";
    static final String FIELD_OFFICE = "Oficiu";
    static final String FIELD_UNIVERSITY_AUTHORS = "N_autori_universitate";
    /** The declared activity type whose instances are the rows of Anexa 5.1. */
    static final String ARTS_ACTIVITY = "Participare eveniment artistic";
    static final String FIELD_ARTS_KIND = "Tip";
    static final String FIELD_ARTS_PARTICIPANTS = "N_participanti_universitate";

    private final UserReportFacade userReportFacade;
    private final UserIndividualReportRunService userIndividualReportRunService;
    private final UserRepository userRepository;
    private final ResearcherAuthorLookupService researcherAuthorLookupService;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final CnfisSheetHeaderRepository headerRepository;
    private final CnfisSheetSnapshotRepository snapshotRepository;
    private final CnfisDomainCatalog domainCatalog;
    private final CNFISReportExportService exportService;
    private final ro.uvt.pokedex.core.repository.ArtisticEventRepository artisticEventRepository;

    public List<CnfisEditionViewModel> editions() {
        return CnfisEdition.known().stream()
                .sorted(Comparator.comparingInt(CnfisEdition::reportingYear).reversed())
                .map(CnfisReportingFacade::toViewModel)
                .toList();
    }

    /** The edition of a reporting year; the newest known one when none is asked for. */
    public Optional<CnfisEditionViewModel> edition(Integer reportingYear) {
        return editionOf(reportingYear).map(CnfisReportingFacade::toViewModel);
    }

    private static Optional<CnfisEdition> editionOf(Integer reportingYear) {
        if (reportingYear == null) {
            return CnfisEdition.known().stream().max(Comparator.comparingInt(CnfisEdition::reportingYear));
        }
        return CnfisEdition.ofReportingYear(reportingYear);
    }

    // ── the page ────────────────────────────────────────────────────────────

    public Optional<CnfisSheetViewModel> buildSheet(String userEmail, int reportingYear) {
        Optional<CnfisEdition> editionOpt = CnfisEdition.ofReportingYear(reportingYear);
        if (editionOpt.isEmpty()) {
            return Optional.empty();
        }
        CnfisEdition edition = editionOpt.get();
        Optional<UserReportFacade.CnfisSheetData> dataOpt = userReportFacade.buildCnfisSheet(userEmail, edition);
        if (dataOpt.isEmpty()) {
            return Optional.empty();
        }
        UserReportFacade.CnfisSheetData data = dataOpt.get();
        CnfisSheetHeader header = headerRepository.findByUserEmailAndReportingYear(userEmail, edition.reportingYear())
                .orElseGet(() -> {
                    CnfisSheetHeader h = new CnfisSheetHeader();
                    h.setUserEmail(userEmail);
                    h.setReportingYear(edition.reportingYear());
                    return h;
                });

        StaffCount staff = countUniversityAuthors(data, edition.referenceDate());
        List<CnfisSheetViewModel.Row> rows = new ArrayList<>();
        List<CnfisSheetViewModel.LeftOut> leftOut = new ArrayList<>();
        for (int i = 0; i < data.publications().size(); i++) {
            ScoringPublicationReadModel p = data.publications().get(i);
            CNFISReport2025 r = data.reports().get(i);
            String year = ro.uvt.pokedex.core.service.application.PersistenceYearSupport
                    .extractYear(p.getCoverDate(), p.getId(), org.slf4j.LoggerFactory.getLogger(getClass()))
                    .map(String::valueOf).orElse("");
            String venue = Optional.ofNullable(data.forumMap().get(p.getForumId()))
                    .map(ScholardexForumView::getPublicationName).orElse("");
            String reason = CNFISReportExportService.leftOutReason(p, r);
            if (reason != null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(p.getId(), year, p.getTitle(), venue, p.getDoi(), reason));
                continue;
            }
            rows.add(new CnfisSheetViewModel.Row(p.getId(), year, p.getTitle(), venue, p.getDoi(), wosCode(p),
                    CnfisSheetViewModel.category(r), r.getClassifiedBy(), r.getListYear(),
                    r.getNumarAutori(), r.getNumarAutoriUniversitate()));
        }
        List<CnfisSheetViewModel.Patent> patents = patents(userEmail, edition);
        ArtsSheet artsSheet = arts(userEmail, edition);
        CnfisSheetViewModel.Arts arts = new CnfisSheetViewModel.Arts(
                ro.uvt.pokedex.core.service.reporting.CnfisDomains.fillsArts(header.getDomainCode()) || !artsSheet.rows().isEmpty(),
                artsSheet.rows(), artsSheet.leftOut());

        List<CnfisSheetViewModel.ReportChoice> reports = userReportFacade.buildIndividualReportsListView(userEmail)
                .individualReports().stream()
                .filter(r -> r.effectiveAuthority() == ReportAuthority.CNATDCU)
                .map(r -> new CnfisSheetViewModel.ReportChoice(r.getId(), r.getTitle()))
                .toList();
        Double score = cnatdcuScore(userEmail, header);

        List<CnfisSheetViewModel.Snapshot> snapshots = snapshotRepository
                .findByUserEmailAndReportingYearOrderByCreatedAtDesc(userEmail, edition.reportingYear()).stream()
                .map(s -> new CnfisSheetViewModel.Snapshot(s.getId(), s.getCreatedAt().toString(),
                        s.getRows().size(), s.getPatents().size(), s.getLockedByUnitSheetId() != null, s.isProvisional()))
                .toList();

        return Optional.of(new CnfisSheetViewModel(toViewModel(edition), header, domainCatalog.domains(), reports,
                score, rows, leftOut, patents, arts, staff.missingRecords, snapshots, counts(data.reports(), patents)));
    }

    // ── the head of the sheet ────────────────────────────────────────────────

    public record HeaderForm(String domainCode, String scoreReportId, Double scoreTyped, String unmetCriterion,
                             Integer hirschGoogleScholar, Integer hirschWebOfScience, Integer hirschScopus) {
    }

    /** Empty when no edition reports that year. */
    public Optional<CnfisSheetHeader> saveHeader(String userEmail, int reportingYear, HeaderForm form) {
        return CnfisEdition.ofReportingYear(reportingYear).map(edition -> saveHeader(userEmail, edition, form));
    }

    CnfisSheetHeader saveHeader(String userEmail, CnfisEdition edition, HeaderForm form) {
        CnfisSheetHeader header = headerRepository.findByUserEmailAndReportingYear(userEmail, edition.reportingYear())
                .orElseGet(CnfisSheetHeader::new);
        header.setUserEmail(userEmail);
        header.setReportingYear(edition.reportingYear());
        Optional<CnfisDomainCatalog.CnfisDomain> domain = domainCatalog.byCode(form.domainCode());
        header.setDomainCode(domain.map(CnfisDomainCatalog.CnfisDomain::code).orElse(null));
        header.setDomainName(domain.map(CnfisDomainCatalog.CnfisDomain::name).orElse(null));
        header.setScoreReportId(blankToNull(form.scoreReportId()));
        header.setScoreTyped(header.getScoreReportId() == null ? form.scoreTyped() : null);
        header.setUnmetCriterion(blankToNull(form.unmetCriterion()));
        header.setHirschGoogleScholar(form.hirschGoogleScholar());
        header.setHirschWebOfScience(form.hirschWebOfScience());
        header.setHirschScopus(form.hirschScopus());
        header.setUpdatedAt(Instant.now());
        return headerRepository.save(header);
    }

    // ── frozen copies ───────────────────────────────────────────────────────

    public Optional<CnfisSheetSnapshot> freeze(String userEmail, int reportingYear) {
        return CnfisEdition.ofReportingYear(reportingYear).flatMap(edition -> freeze(userEmail, edition));
    }

    Optional<CnfisSheetSnapshot> freeze(String userEmail, CnfisEdition edition) {
        return snapshot(userEmail, edition, false, userEmail);
    }

    /**
     * Slice 3: the copy a HEAD generates for a member who froze none — the same sheet, marked provisional and
     * signed by the head; it stands in for the member's own until the member freezes one.
     */
    public Optional<CnfisSheetSnapshot> freezeProvisional(String userEmail, CnfisEdition edition, String headEmail) {
        return snapshot(userEmail, edition, true, headEmail);
    }

    private Optional<CnfisSheetSnapshot> snapshot(String userEmail, CnfisEdition edition, boolean provisional, String createdBy) {
        Optional<UserReportFacade.CnfisSheetData> dataOpt = userReportFacade.buildCnfisSheet(userEmail, edition);
        if (dataOpt.isEmpty()) {
            return Optional.empty();
        }
        UserReportFacade.CnfisSheetData data = dataOpt.get();
        countUniversityAuthors(data, edition.referenceDate());
        CnfisSheetSnapshot snapshot = new CnfisSheetSnapshot();
        snapshot.setUserEmail(userEmail);
        snapshot.setDisplayName(userRepository.findById(userEmail)
                .map(u -> u.getResearcherProfile() == null ? userEmail : u.getResearcherProfile().getName())
                .orElse(userEmail));
        snapshot.setReportingYear(edition.reportingYear());
        snapshot.setCreatedAt(Instant.now());
        snapshot.setProvisional(provisional);
        snapshot.setCreatedBy(createdBy);
        CnfisSheetHeader header = headerRepository.findByUserEmailAndReportingYear(userEmail, edition.reportingYear()).orElse(null);
        snapshot.setHeader(header);
        snapshot.setCnatdcuScore(cnatdcuScore(userEmail, header));
        for (int i = 0; i < data.publications().size(); i++) {
            ScoringPublicationReadModel p = data.publications().get(i);
            CNFISReport2025 r = data.reports().get(i);
            String year = ro.uvt.pokedex.core.service.application.PersistenceYearSupport
                    .extractYearString(p.getCoverDate(), p.getId(), org.slf4j.LoggerFactory.getLogger(getClass()));
            String reason = CNFISReportExportService.leftOutReason(p, r);
            if (reason != null) {
                CnfisSheetSnapshot.LeftOut lo = new CnfisSheetSnapshot.LeftOut();
                lo.setPublicationId(p.getId());
                lo.setTitle(p.getTitle());
                lo.setYear(year);
                lo.setVenue(Optional.ofNullable(data.forumMap().get(p.getForumId()))
                        .map(ScholardexForumView::getPublicationName).orElse(""));
                lo.setDoi(p.getDoi());
                lo.setReason(reason);
                snapshot.getLeftOut().add(lo);
                continue;
            }
            CnfisSheetSnapshot.Row row = new CnfisSheetSnapshot.Row();
            row.setPublicationId(p.getId());
            row.setTitle(p.getTitle());
            row.setDoi(p.getDoi());
            row.setWosCode(wosCode(p));
            row.setYear(year);
            row.setForumId(p.getForumId());
            row.setAuthorCount(p.getAuthorCount());
            row.setClassification(r);
            snapshot.getRows().add(row);
        }
        for (CnfisSheetViewModel.Patent p : patents(userEmail, edition)) {
            CnfisSheetSnapshot.Patent patent = new CnfisSheetSnapshot.Patent();
            patent.setActivityInstanceId(p.activityInstanceId());
            patent.setYear(p.year());
            patent.setTitle(p.title());
            patent.setCode(p.code());
            patent.setOffice(p.office());
            patent.setType(p.type());
            patent.setAuthorCount(p.authorCount());
            patent.setUniversityAuthorCount(p.universityAuthorCount());
            snapshot.getPatents().add(patent);
        }
        for (CnfisSheetViewModel.ArtsRow r : arts(userEmail, edition).rows()) {
            CnfisSheetSnapshot.ArtsRow row = new CnfisSheetSnapshot.ArtsRow();
            row.setActivityInstanceId(r.activityInstanceId());
            row.setYear(r.year());
            row.setWork(r.work());
            row.setEvent(r.event());
            row.setLevel(r.level());
            row.setKind(r.kind());
            row.setUniversityParticipants(r.universityParticipants());
            snapshot.getArtsRows().add(row);
        }
        return Optional.of(snapshotRepository.save(snapshot));
    }

    /** The person's own copy, deleted unless an institutional table was built from it. */
    public enum ReleaseResult { RELEASED, NOT_FOUND, LOCKED }

    public ReleaseResult release(String userEmail, String snapshotId) {
        Optional<CnfisSheetSnapshot> snapshot = snapshotRepository.findById(snapshotId)
                .filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshot.isEmpty()) {
            return ReleaseResult.NOT_FOUND;
        }
        if (snapshot.get().getLockedByUnitSheetId() != null) {
            return ReleaseResult.LOCKED;
        }
        snapshotRepository.deleteById(snapshotId);
        return ReleaseResult.RELEASED;
    }

    // ── downloads ───────────────────────────────────────────────────────────

    public Optional<byte[]> exportLive(String userEmail, int reportingYear) throws IOException {
        Optional<CnfisEdition> edition = CnfisEdition.ofReportingYear(reportingYear);
        if (edition.isEmpty()) {
            return Optional.empty();
        }
        return exportLive(userEmail, edition.get());
    }

    Optional<byte[]> exportLive(String userEmail, CnfisEdition edition) throws IOException {
        Optional<UserReportFacade.CnfisSheetData> dataOpt = userReportFacade.buildCnfisSheet(userEmail, edition);
        if (dataOpt.isEmpty()) {
            return Optional.empty();
        }
        UserReportFacade.CnfisSheetData data = dataOpt.get();
        countUniversityAuthors(data, edition.referenceDate());
        return Optional.of(exportService.generateAnexa5(data.publications(), data.reports(), data.forumMap(),
                patents(userEmail, edition).stream().map(CnfisReportingFacade::toExportPatent).toList()));
    }

    /** Anexa 5.1 of the live data; empty when the edition is unknown or the person has no profile. */
    public Optional<byte[]> exportArtsLive(String userEmail, int reportingYear) throws IOException {
        Optional<CnfisEdition> edition = CnfisEdition.ofReportingYear(reportingYear);
        if (edition.isEmpty() || userReportFacade.buildCnfisSheet(userEmail, edition.get()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportService.generateAnexa51(toExportArts(arts(userEmail, edition.get()).rows())));
    }

    public Optional<byte[]> exportArtsSnapshot(String userEmail, String snapshotId) throws IOException {
        Optional<CnfisSheetSnapshot> snapshotOpt = snapshotRepository.findById(snapshotId)
                .filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportService.generateAnexa51(toExportArts(snapshotOpt.get().getArtsRows())));
    }

    static List<CNFISReportExportService.ArtsExportRow> toExportArts(List<? extends Object> rows) {
        List<CNFISReportExportService.ArtsExportRow> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof CnfisSheetViewModel.ArtsRow r) {
                out.add(new CNFISReportExportService.ArtsExportRow(r.year(), r.work(), r.event(), r.level(), r.kind(), r.universityParticipants()));
            } else if (o instanceof CnfisSheetSnapshot.ArtsRow r) {
                out.add(new CNFISReportExportService.ArtsExportRow(r.getYear(), r.getWork(), r.getEvent(), r.getLevel(), r.getKind(), r.getUniversityParticipants()));
            }
        }
        return out;
    }

    public Optional<byte[]> exportSnapshot(String userEmail, String snapshotId) throws IOException {
        Optional<CnfisSheetSnapshot> snapshotOpt = snapshotRepository.findById(snapshotId)
                .filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportSnapshot(snapshotOpt.get()));
    }

    /** The workbook of a frozen sheet, from what it holds — nothing is re-read. */
    public byte[] exportSnapshot(CnfisSheetSnapshot snapshot) throws IOException {
        List<ScoringPublicationReadModel> publications = new ArrayList<>();
        List<CNFISReport2025> reports = new ArrayList<>();
        Set<String> forumIds = new LinkedHashSet<>();
        for (CnfisSheetSnapshot.Row row : snapshot.getRows()) {
            publications.add(new ro.uvt.pokedex.core.model.reporting.ScoringPublication(
                    row.getPublicationId(), null, row.getForumId(),
                    row.getYear() == null ? null : row.getYear() + "-01-01", null, null, List.of(),
                    row.getAuthorCount(), row.getDoi(), row.getWosCode(), row.getTitle(), 0, Set.of()));
            reports.add(row.getClassification());
            if (row.getForumId() != null) {
                forumIds.add(row.getForumId());
            }
        }
        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        scholardexProjectionReadService.findForumsByIdIn(forumIds).forEach(f -> forumMap.put(f.getId(), f));
        List<CNFISReport2025> patents = snapshot.getPatents().stream().map(p -> {
            CNFISReport2025 r = new CNFISReport2025();
            r.setTitlu(p.getTitle());
            r.setBrevetCode(p.getCode());
            r.setOficiuBrevet(p.getOffice());
            r.setListYear(parseYear(p.getYear()));
            setPatentType(r, p.getType());
            r.setNumarAutori(p.getAuthorCount());
            r.setNumarAutoriUniversitate(p.getUniversityAuthorCount());
            return r;
        }).toList();
        return exportService.generateAnexa5(publications, reports, forumMap, patents);
    }

    // ── pieces ──────────────────────────────────────────────────────────────

    /** The CNATDCU score the head of the sheet asks for: the report's latest total, or what was typed. */
    private Double cnatdcuScore(String userEmail, CnfisSheetHeader header) {
        if (header == null) {
            return null;
        }
        if (header.getScoreReportId() == null) {
            return header.getScoreTyped();
        }
        Optional<IndividualReport> report = userReportFacade.findIndividualReportById(header.getScoreReportId());
        Optional<IndividualReportRunDto> run = userIndividualReportRunService.getOrCreateLatestRun(userEmail, header.getScoreReportId());
        if (report.isEmpty() || run.isEmpty() || report.get().getCriteria() == null) {
            return null;
        }
        // the same rule as the evaluation page: the sum of the criteria that contribute to the total
        List<AbstractReport.Criterion> criteria = report.get().getCriteria();
        boolean any = false;
        double total = 0.0;
        for (int i = 0; i < criteria.size(); i++) {
            if (criteria.get(i).isContributesToTotal()) {
                any = true;
                total += run.get().criteriaScores().getOrDefault(i, 0.0);
            }
        }
        return any ? total : null;
    }

    private record StaffCount(List<String> missingRecords) {
    }

    /**
     * "Număr autori din universitate" as CNFIS counts it: the co-authors who are staff of Anexa 1 at the
     * reference date. A co-author with an account but no staff record is counted and named, so the head
     * knows whose record to fill. Rewrites the count on each classification.
     */
    private StaffCount countUniversityAuthors(UserReportFacade.CnfisSheetData data, LocalDate referenceDate) {
        Map<String, User> ownerByAuthorId = new HashMap<>();
        for (User user : userRepository.findAll()) {
            if (user.getResearcherProfile() == null || user.isExternal()) {
                continue;
            }
            List<String> keys = researcherAuthorLookupService.resolveAuthorLookupKeys(user.getResearcherProfile());
            scholardexProjectionReadService.findAuthorsByIdIn(keys).forEach(a -> ownerByAuthorId.putIfAbsent(a.getId(), user));
        }
        Set<String> missing = new LinkedHashSet<>();
        for (int i = 0; i < data.publications().size(); i++) {
            int counted = 0;
            List<String> authorIds = data.publications().get(i).getAuthorIds() == null ? List.of() : data.publications().get(i).getAuthorIds();
            for (String authorId : authorIds) {
                User owner = ownerByAuthorId.get(authorId);
                if (owner == null) {
                    continue;
                }
                Boolean staff = owner.getStaffRecord() == null ? null : owner.getStaffRecord().isStaffOn(referenceDate);
                if (staff == null) {
                    missing.add(owner.getResearcherProfile().getName());
                    counted++;
                } else if (staff) {
                    counted++;
                }
            }
            data.reports().get(i).setNumarAutoriUniversitate(counted);
        }
        return new StaffCount(new ArrayList<>(missing));
    }

    record ArtsSheet(List<CnfisSheetViewModel.ArtsRow> rows, List<CnfisSheetViewModel.LeftOut> leftOut) {
    }

    /**
     * Anexa 5.1 from the declared activity "Participare eveniment artistic": the event is looked up in the
     * registry of artistic events, whose rank IS the level of the form (national, international, top
     * international); the declared kind (individual, group, collective, nomination, prize) is the column
     * group. A performance without a kind, or at an event the registry does not rank, is left out and says so.
     */
    ArtsSheet arts(String userEmail, CnfisEdition edition) {
        List<CnfisSheetViewModel.ArtsRow> rows = new ArrayList<>();
        List<CnfisSheetViewModel.LeftOut> leftOut = new ArrayList<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(userEmail)) {
            if (instance.getActivity() == null || !ARTS_ACTIVITY.equals(instance.getActivity().getName())) {
                continue;
            }
            Integer year = parseYear(instance.getDate());
            if (year == null || !edition.covers(year)) {
                continue;
            }
            String yearText = String.valueOf(year);
            String event = instance.getReferenceFields() == null ? null
                    : instance.getReferenceFields().get(ro.uvt.pokedex.core.model.activities.Activity.ReferenceField.EVENT_NAME);
            Map<String, String> f = instance.getFields() == null ? Map.of() : instance.getFields();
            String kind = artsKind(f.get(FIELD_ARTS_KIND));
            if (kind == null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), event, null,
                        "the kind of the work (individual, group, collective, nomination, prize) is not declared"));
                continue;
            }
            String level = event == null ? null : artisticEventRepository.findAllByNameIgnoreCase(event.trim()).stream()
                    .findFirst().map(e -> e.getRank() == null ? null : e.getRank().name()).orElse(null);
            if (level == null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), event, null,
                        event == null ? "no event declared" : "the event is not in the registry of ranked events (national / international / top)"));
                continue;
            }
            rows.add(new CnfisSheetViewModel.ArtsRow(instance.getId(), yearText, instance.getName(), event, level, kind,
                    parseInt(f.get(FIELD_ARTS_PARTICIPANTS))));
        }
        rows.sort(Comparator.comparing(CnfisSheetViewModel.ArtsRow::year).thenComparing(CnfisSheetViewModel.ArtsRow::work));
        return new ArtsSheet(rows, leftOut);
    }

    /** The declared kind, as the activity's allowed values name it, to the column group of the form. */
    static String artsKind(String declared) {
        if (declared == null) {
            return null;
        }
        String d = declared.trim().toLowerCase(java.util.Locale.ROOT);
        if (d.startsWith("proiect individual")) return "INDIVIDUAL";
        if (d.startsWith("proiect de grup")) return "GROUP";
        if (d.startsWith("proiect colectiv")) return "COLLECTIVE";
        if (d.startsWith("nominalizare")) return "NOMINATION";
        if (d.startsWith("premiu")) return "PRIZE";
        return null;
    }

    private List<CnfisSheetViewModel.Patent> patents(String userEmail, CnfisEdition edition) {
        List<CnfisSheetViewModel.Patent> out = new ArrayList<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(userEmail)) {
            if (instance.getActivity() == null || !PATENT_ACTIVITY.equals(instance.getActivity().getName())) {
                continue;
            }
            Integer year = parseYear(instance.getDate());
            if (year == null || !edition.covers(year)) {
                continue;
            }
            Map<String, String> f = instance.getFields() == null ? Map.of() : instance.getFields();
            out.add(new CnfisSheetViewModel.Patent(instance.getId(), String.valueOf(year), instance.getName(),
                    f.getOrDefault(FIELD_CODE, ""), f.getOrDefault(FIELD_OFFICE, ""), f.getOrDefault(FIELD_TYPE, ""),
                    parseInt(f.get(FIELD_AUTHORS)), parseInt(f.get(FIELD_UNIVERSITY_AUTHORS))));
        }
        out.sort(Comparator.comparing(CnfisSheetViewModel.Patent::year).thenComparing(CnfisSheetViewModel.Patent::title));
        return out;
    }

    private static CNFISReport2025 toExportPatent(CnfisSheetViewModel.Patent p) {
        CNFISReport2025 r = new CNFISReport2025();
        r.setTitlu(p.title());
        r.setBrevetCode(p.code());
        r.setOficiuBrevet(p.office());
        r.setListYear(parseYear(p.year()));
        setPatentType(r, p.type());
        r.setNumarAutori(p.authorCount());
        r.setNumarAutoriUniversitate(p.universityAuthorCount());
        return r;
    }

    /** The declared type of the "Brevet" activity IS the column of the form. */
    static void setPatentType(CNFISReport2025 r, String type) {
        String t = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        switch (t) {
            case "triadic" -> r.setTriadice(true);
            case "european" -> r.setEuropene(true);
            case "international" -> r.setInternationale(true);
            case "national" -> r.setNationale(true);
            default -> { }
        }
    }

    private static CnfisSheetViewModel.Counts counts(List<CNFISReport2025> reports, List<CnfisSheetViewModel.Patent> patents) {
        int q1 = 0, q2 = 0, q3 = 0, q4 = 0, ah = 0, esci = 0, erih = 0, isi = 0, ieee = 0;
        for (CNFISReport2025 r : reports) {
            if (r.isIsiQ1()) q1++;
            else if (r.isIsiQ2()) q2++;
            else if (r.isIsiQ3()) q3++;
            else if (r.isIsiQ4()) q4++;
            else if (r.isIsiArtsHumanities()) ah++;
            else if (r.isIsiEmergingSourcesCitationIndex()) esci++;
            else if (r.isErihPlus()) erih++;
            else if (r.isIsiProceedings()) isi++;
            else if (r.isIeeeProceedings()) ieee++;
        }
        return new CnfisSheetViewModel.Counts(q1, q2, q3, q4, ah, esci, erih, isi, ieee, patents.size());
    }

    private static CnfisEditionViewModel toViewModel(CnfisEdition e) {
        return new CnfisEditionViewModel(e.reportingYear(), e.windowStart(), e.windowEnd(), e.lastListYear(), e.provisional());
    }

    private static String wosCode(ScoringPublicationReadModel p) {
        String w = p.getWosId();
        return w == null || w.isBlank() || w.equals(ro.uvt.pokedex.core.model.reporting.CanonicalPublicationConstants.NON_WOS_ID) ? "" : w;
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(value);
        return m.find() ? Integer.parseInt(m.group()) : null;
    }

    private static int parseInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
