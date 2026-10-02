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
    static final String FIELD_ARTS_PARTICIPANTS = "N_participanti_universitate";
    /** H129 Anexa 5.2: the declared sport performance; the instance's name is the "date de identificare". */
    static final String SPORT_ACTIVITY = "Performanță sportivă (CNFIS 5.2)";
    static final String FIELD_SPORT_CHAMPIONSHIP = "Campionat";
    static final String FIELD_SPORT_LEVEL = "Nivel";
    static final String FIELD_SPORT_PLACE = "Loc";
    static final String FIELD_SPORT_RECORD = "Record";
    /** H142 Anexa 4.1: a citation or review of an artistic work; the instance's name is the work, its date the citation's. */
    static final String CITATION_ACTIVITY = "Citare sau cronică a unei creații artistice (CNFIS 4.1)";
    static final String FIELD_CITATION_WORK_YEAR = "An_creatie";
    static final String FIELD_CITATION_WORK_DETAILS = "Detalii_creatie";
    static final String FIELD_CITATION_PUBLICATION = "Publicatie";
    static final String FIELD_CITATION_ISSUE = "Numar_publicatie";

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
    private final ro.uvt.pokedex.core.repository.scopus.canonical.ScholardexBookFactRepository bookFactRepository;
    private final ro.uvt.pokedex.core.service.reporting.CiteScoreQuartiles citeScoreQuartiles;

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
        CitationsSheet citationsSheet = citations(userEmail, edition);
        HumanitiesSheet humanitiesSheet = humanities(userEmail, edition, data);
        CnfisSheetViewModel.Humanities humanities = new CnfisSheetViewModel.Humanities(
                ro.uvt.pokedex.core.service.reporting.CnfisDomains.fillsHumanities(header.getDomainCode()),
                humanitiesSheet.rows(), humanitiesSheet.leftOut(), citeScoreQuartiles.availableYears());
        CnfisSheetViewModel.Arts arts = new CnfisSheetViewModel.Arts(
                ro.uvt.pokedex.core.service.reporting.CnfisDomains.fillsArts(header.getDomainCode()) || !artsSheet.rows().isEmpty()
                        || !citationsSheet.rows().isEmpty(),
                artsSheet.rows(), artsSheet.leftOut(), citationsSheet.rows(), citationsSheet.leftOut());
        SportSheet sportSheet = sport(userEmail, edition);
        CnfisSheetViewModel.Sport sport = new CnfisSheetViewModel.Sport(
                ro.uvt.pokedex.core.service.reporting.CnfisDomains.fillsSport(header.getDomainCode()) || !sportSheet.rows().isEmpty(),
                sportSheet.rows(), sportSheet.leftOut());

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
                score, rows, leftOut, patents, arts, sport, humanities, staff.missingRecords, snapshots, counts(data.reports(), patents)));
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
        for (CnfisSheetViewModel.CitationRow r : citations(userEmail, edition).rows()) {
            CnfisSheetSnapshot.CitationRow row = new CnfisSheetSnapshot.CitationRow();
            row.setActivityInstanceId(r.activityInstanceId());
            row.setWorkYear(r.workYear());
            row.setWork(r.work());
            row.setCitation(r.citation());
            row.setCitationYear(r.citationYear());
            snapshot.getArtsCitationRows().add(row);
        }
        for (CnfisSheetViewModel.SportRow r : sport(userEmail, edition).rows()) {
            CnfisSheetSnapshot.SportRow row = new CnfisSheetSnapshot.SportRow();
            row.setActivityInstanceId(r.activityInstanceId());
            row.setYear(r.year());
            row.setActivity(r.activity());
            row.setChampionship(r.championship());
            row.setLevel(r.level());
            row.setPlace(r.place());
            row.setRecord(r.record());
            row.setUniversityParticipants(r.universityParticipants());
            snapshot.getSportRows().add(row);
        }
        for (CnfisSheetViewModel.HumanitiesRow r : humanities(userEmail, edition, data).rows()) {
            snapshot.getHumanitiesRows().add(toSnapshotHumanities(r));
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

    /** Anexa 4.1 of the live data — the citations of the person's artistic works up to the edition's reference date. */
    public Optional<byte[]> exportCitationsLive(String userEmail, int reportingYear) throws IOException {
        Optional<CnfisEdition> edition = CnfisEdition.ofReportingYear(reportingYear);
        if (edition.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportService.generateAnexa41(toExportCitations(citations(userEmail, edition.get()).rows())));
    }

    /** Anexa 4.1 of a frozen copy; a copy frozen before Anexa 4.1 existed gives an empty form. */
    public Optional<byte[]> exportCitationsSnapshot(String userEmail, String snapshotId) throws IOException {
        Optional<CnfisSheetSnapshot> snapshotOpt = snapshotRepository.findById(snapshotId).filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        List<CnfisSheetSnapshot.CitationRow> rows = snapshotOpt.get().getArtsCitationRows() == null ? List.of()
                : snapshotOpt.get().getArtsCitationRows();
        return Optional.of(exportService.generateAnexa41(toExportCitations(rows)));
    }

    static List<CNFISReportExportService.CitationExportRow> toExportCitations(List<? extends Object> rows) {
        List<CNFISReportExportService.CitationExportRow> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof CnfisSheetViewModel.CitationRow r) {
                out.add(new CNFISReportExportService.CitationExportRow(r.workYear(), r.work(), r.citation()));
            } else if (o instanceof CnfisSheetSnapshot.CitationRow r) {
                out.add(new CNFISReportExportService.CitationExportRow(r.getWorkYear(), r.getWork(), r.getCitation()));
            }
        }
        return out;
    }

    /** Anexa 5.2 of the live sheet. */
    public Optional<byte[]> exportSportLive(String userEmail, int reportingYear) throws IOException {
        Optional<CnfisEdition> edition = CnfisEdition.ofReportingYear(reportingYear);
        if (edition.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportService.generateAnexa52(toExportSport(sport(userEmail, edition.get()).rows())));
    }

    /** Anexa 5.2 of a frozen copy. */
    public Optional<byte[]> exportSportSnapshot(String userEmail, String snapshotId) throws IOException {
        Optional<CnfisSheetSnapshot> snapshotOpt = snapshotRepository.findById(snapshotId).filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        List<CnfisSheetSnapshot.SportRow> rows = snapshotOpt.get().getSportRows() == null ? List.of() : snapshotOpt.get().getSportRows();
        return Optional.of(exportService.generateAnexa52(toExportSport(rows)));
    }

    static List<CNFISReportExportService.SportExportRow> toExportSport(List<? extends Object> rows) {
        List<CNFISReportExportService.SportExportRow> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof CnfisSheetViewModel.SportRow r) {
                out.add(new CNFISReportExportService.SportExportRow(r.year(), r.activity(), r.championship(), r.level(), r.place(), r.record(), r.universityParticipants()));
            } else if (o instanceof CnfisSheetSnapshot.SportRow r) {
                out.add(new CNFISReportExportService.SportExportRow(r.getYear(), r.getActivity(), r.getChampionship(), r.getLevel(), r.getPlace(), r.getRecord(), r.getUniversityParticipants()));
            }
        }
        return out;
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

    public Optional<byte[]> exportHumanitiesLive(String userEmail, int reportingYear) throws IOException {
        Optional<CnfisEdition> edition = CnfisEdition.ofReportingYear(reportingYear);
        if (edition.isEmpty()) {
            return Optional.empty();
        }
        Optional<UserReportFacade.CnfisSheetData> dataOpt = userReportFacade.buildCnfisSheet(userEmail, edition.get());
        if (dataOpt.isEmpty()) {
            return Optional.empty();
        }
        countUniversityAuthors(dataOpt.get(), edition.get().referenceDate());
        return Optional.of(exportService.generateAnexa53(toExportHumanities(humanities(userEmail, edition.get(), dataOpt.get()).rows())));
    }

    public Optional<byte[]> exportHumanitiesSnapshot(String userEmail, String snapshotId) throws IOException {
        Optional<CnfisSheetSnapshot> snapshotOpt = snapshotRepository.findById(snapshotId)
                .filter(s -> userEmail.equals(s.getUserEmail()));
        if (snapshotOpt.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(exportService.generateAnexa53(toExportHumanities(snapshotOpt.get().getHumanitiesRows())));
    }

    static List<CNFISReportExportService.HumanitiesExportRow> toExportHumanities(List<? extends Object> rows) {
        List<CNFISReportExportService.HumanitiesExportRow> out = new ArrayList<>();
        for (Object o : rows) {
            if (o instanceof CnfisSheetViewModel.HumanitiesRow r) {
                out.add(new CNFISReportExportService.HumanitiesExportRow(r.year(), r.containerTitle(), r.publisher(), r.isbn(),
                        r.issnOnline(), r.issnPrint(), r.doi(), r.itemTitle(), r.category(), r.pages(), r.authorCount(), r.universityAuthorCount()));
            } else if (o instanceof CnfisSheetSnapshot.HumanitiesRow r) {
                out.add(new CNFISReportExportService.HumanitiesExportRow(r.getYear(), r.getContainerTitle(), r.getPublisher(), r.getIsbn(),
                        r.getIssnOnline(), r.getIssnPrint(), r.getDoi(), r.getItemTitle(), r.getCategory(), r.getPages(), r.getAuthorCount(), r.getUniversityAuthorCount()));
            }
        }
        return out;
    }

    private static CnfisSheetSnapshot.HumanitiesRow toSnapshotHumanities(CnfisSheetViewModel.HumanitiesRow r) {
        CnfisSheetSnapshot.HumanitiesRow row = new CnfisSheetSnapshot.HumanitiesRow();
        row.setSourceId(r.sourceId());
        row.setYear(r.year());
        row.setContainerTitle(r.containerTitle());
        row.setPublisher(r.publisher());
        row.setIsbn(r.isbn());
        row.setIssnOnline(r.issnOnline());
        row.setIssnPrint(r.issnPrint());
        row.setDoi(r.doi());
        row.setItemTitle(r.itemTitle());
        row.setCategory(r.category());
        row.setListYear(r.listYear());
        row.setClassifiedBy(r.classifiedBy());
        row.setPages(r.pages());
        row.setAuthorCount(r.authorCount());
        row.setUniversityAuthorCount(r.universityAuthorCount());
        return row;
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

    /**
     * The CNATDCU score the head of the sheet asks for: the chosen report's latest total, or what was typed.
     * The total is the one the evaluation page shows for the researcher's position — the position of the
     * department's staff list (staff import, roster), not a position picked on the page: each contributing
     * criterion at its position-effective value (threshold-cap additions, {@code Poz} formulas) where one
     * exists, canonical otherwise. A researcher without a position gets the canonical sum.
     */
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
        String position = userRepository.findById(userEmail)
                .map(User::getResearcherProfile)
                .map(p -> p.getPosition() == null ? null : p.getPosition().name())
                .orElse(null);
        Map<Integer, Double> canonical = run.get().criteriaScores() == null ? Map.of() : run.get().criteriaScores();
        Map<Integer, Map<String, Double>> byPosition = ReportingComputationSupport.computePositionEffectiveScores(
                report.get().getCriteria(), report.get().getIndicators(), run.get().indicatorScoresByIndicatorId(),
                canonical, run.get().indicatorScoresByPositionByIndicatorId());
        List<AbstractReport.Criterion> criteria = report.get().getCriteria();
        boolean any = false;
        double total = 0.0;
        for (int i = 0; i < criteria.size(); i++) {
            if (!criteria.get(i).isContributesToTotal()) {
                continue;
            }
            any = true;
            Map<String, Double> effective = byPosition.get(i);
            total += position != null && effective != null && effective.containsKey(position)
                    ? effective.get(position)
                    : canonical.getOrDefault(i, 0.0);
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

    record HumanitiesSheet(List<CnfisSheetViewModel.HumanitiesRow> rows, List<CnfisSheetViewModel.LeftOut> leftOut) {
    }

    /**
     * Anexa 5.3 (the humanities): from the publications of the window — an Article or Review in a journal
     * Scopus indexes, by the best CiteScore quartile of the edition's list year (nearest loaded list when that
     * year's is not, and the row says so); a book (carte de autor); a chapter in a collective volume — and from
     * the declared activities: an edited volume ("Carte coordonată …"), a translation ("Traducere …"), a book
     * or chapter declared by the candidate. The KVK library link is the person's to add in the file.
     */
    HumanitiesSheet humanities(String userEmail, CnfisEdition edition, UserReportFacade.CnfisSheetData data) {
        List<CnfisSheetViewModel.HumanitiesRow> rows = new ArrayList<>();
        List<CnfisSheetViewModel.LeftOut> leftOut = new ArrayList<>();
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());
        for (int i = 0; i < data.publications().size(); i++) {
            ScoringPublicationReadModel p = data.publications().get(i);
            CNFISReport2025 r = data.reports().get(i);
            Integer year = ro.uvt.pokedex.core.service.application.PersistenceYearSupport.extractYear(p.getCoverDate(), p.getId(), log).orElse(null);
            String yearText = year == null ? "" : String.valueOf(year);
            ScholardexForumView forum = data.forumMap().get(p.getForumId());
            String subtype = ro.uvt.pokedex.core.service.reporting.PublicationSubtypeSupport.resolveSubtype(p);
            String container = forum == null ? "" : nz(forum.getPublicationName());
            String venue = container;
            if ("ar".equals(subtype) || "re".equals(subtype)) {
                if (forum == null || forum.getScopusId() == null || forum.getScopusId().isBlank()) {
                    leftOut.add(new CnfisSheetViewModel.LeftOut(p.getId(), yearText, p.getTitle(), venue, p.getDoi(),
                            "Anexa 5.3 reports articles in journals Scopus indexes; this journal is not one"));
                    continue;
                }
                int wanted = year == null ? edition.lastListYear() : edition.listYearFor(year);
                Optional<ro.uvt.pokedex.core.service.reporting.CiteScoreQuartiles.Placement> placement =
                        citeScoreQuartiles.placement(forum.getScopusId(), wanted);
                if (placement.isEmpty()) {
                    leftOut.add(new CnfisSheetViewModel.LeftOut(p.getId(), yearText, p.getTitle(), venue, p.getDoi(),
                            "the journal has no CiteScore quartile in the lists loaded"));
                    continue;
                }
                String by = "CiteScore Q" + placement.get().quartile() + " · list " + placement.get().listYear()
                        + (placement.get().listYear() == wanted ? "" : " (the " + wanted + " list is not loaded)");
                rows.add(new CnfisSheetViewModel.HumanitiesRow(p.getId(), yearText, container, nz(forum.getPublisher()), "",
                        nz(forum.getEIssn()), nz(forum.getIssn()), nz(p.getDoi()), nz(p.getTitle()),
                        "SCOPUS_Q" + placement.get().quartile(), placement.get().listYear(), by, null,
                        r.getNumarAutori(), r.getNumarAutoriUniversitate()));
            } else if ("bk".equals(subtype) || "ch".equals(subtype)) {
                ro.uvt.pokedex.core.model.scopus.canonical.ScholardexBookFact book = p.getBookId() == null ? null
                        : bookFactRepository.findById(p.getBookId()).orElse(null);
                String publisher = book != null && book.getPublisher() != null ? book.getPublisher() : forum == null ? "" : nz(forum.getPublisher());
                String isbn = book != null ? nz(book.getPrintIsbn() != null ? book.getPrintIsbn() : book.getElectronicIsbn())
                        : forum == null ? "" : nz(forum.getIsbn());
                String bookTitle = book != null && book.getTitle() != null ? book.getTitle() : container;
                boolean chapter = "ch".equals(subtype);
                rows.add(new CnfisSheetViewModel.HumanitiesRow(p.getId(), yearText,
                        chapter ? bookTitle : nz(p.getTitle()), publisher, isbn, "", "", nz(p.getDoi()),
                        chapter ? nz(p.getTitle()) : "", chapter ? "CHAPTER" : "BOOK", null,
                        chapter ? "chapter in a collective volume (from the publication record)" : "book (from the publication record)",
                        null, r.getNumarAutori(), r.getNumarAutoriUniversitate()));
            }
        }
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(userEmail)) {
            String type = instance.getActivity() == null || instance.getActivity().getName() == null ? "" : instance.getActivity().getName();
            Map<String, String> f = instance.getFields() == null ? Map.of() : instance.getFields();
            String category;
            if (type.startsWith("Carte coordonat")) {
                category = "EDITED_VOLUME";
            } else if (type.startsWith("Traducere")) {
                category = "TRANSLATION";
            } else if (type.startsWith("Carte sau capitol")) {
                category = nz(f.get("Tip")).toLowerCase(java.util.Locale.ROOT).startsWith("capitol") ? "CHAPTER" : "BOOK";
            } else {
                continue;
            }
            Integer year = parseYear(instance.getDate());
            if (year == null || !edition.covers(year)) {
                continue;
            }
            String title = f.getOrDefault("Titlu", instance.getName());
            int authors = Math.max(1, parseInt(f.getOrDefault("N_autori", f.getOrDefault("N_coordonatori", "1"))));
            rows.add(new CnfisSheetViewModel.HumanitiesRow(instance.getId(), String.valueOf(year),
                    "CHAPTER".equals(category) ? nz(f.get("Volum")) : nz(title), nz(f.get("Editura")), "", "", "", "",
                    "CHAPTER".equals(category) ? nz(title) : "", category, null,
                    "declared: " + type, null, authors, 1));
        }
        rows.sort(Comparator.comparing(CnfisSheetViewModel.HumanitiesRow::year).thenComparing(CnfisSheetViewModel.HumanitiesRow::containerTitle));
        return new HumanitiesSheet(rows, leftOut);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
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
            // H142: the kind as declared, else derived from the result, the size of the ensemble or the role
            String kind = ro.uvt.pokedex.core.service.reporting.ArtisticPerformanceSupport.cnfisKind(f);
            if (kind == null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), event, null,
                        "the kind of the work (individual, group, collective, nomination, prize) is not declared, "
                                + "and neither the role nor the size of the ensemble tells it"));
                continue;
            }
            // H142 slice 3: the rank the experts gave the event (its name or a spelling); national-top is national for
            // CNFIS, a local host is no CNFIS level, and an event nobody has ranked yet waits
            var rank = event == null ? java.util.Optional.<ro.uvt.pokedex.core.model.ArtisticEvent.Rank>empty()
                    : ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport.rankOf(event);
            String level = rank.map(r -> switch (r) {
                case INTERNATIONAL_TOP -> "INTERNATIONAL_TOP";
                case INTERNATIONAL -> "INTERNATIONAL";
                case NATIONAL_TOP, NATIONAL -> "NATIONAL";
                case LOCAL -> null;
            }).orElse(null);
            if (level == null) {
                String why = event == null ? "no event declared"
                        : rank.isPresent() ? "the event is ranked local, which is no CNFIS level"
                        : ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport.statusOf(event)
                                .filter(st -> st == ro.uvt.pokedex.core.service.reporting.ArtisticEventRankSupport.EventStatus.REJECTED)
                                .isPresent() ? "the experts rejected the event's name; name the event again"
                        : "the event waits for an expert of its domain to rank it";
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), event, null, why));
                continue;
            }
            rows.add(new CnfisSheetViewModel.ArtsRow(instance.getId(), yearText, instance.getName(), event, level, kind,
                    parseInt(f.get(FIELD_ARTS_PARTICIPANTS))));
        }
        rows.sort(Comparator.comparing(CnfisSheetViewModel.ArtsRow::year).thenComparing(CnfisSheetViewModel.ArtsRow::work));
        return new ArtsSheet(rows, leftOut);
    }

    record CitationsSheet(List<CnfisSheetViewModel.CitationRow> rows, List<CnfisSheetViewModel.LeftOut> leftOut) {
    }

    /**
     * Anexa 4.1 from the declared activity "Citare sau cronică a unei creații artistice (CNFIS 4.1)": one row per
     * citation, for the whole career — every citation that appeared before the edition's reference date, whatever the
     * year of the work. Column C is the work (its name, then the event, place and date as declared), column D the
     * publication, its issue and the year of the citation. A citation without the year of the work or without the
     * publication is left out and says so.
     */
    CitationsSheet citations(String userEmail, CnfisEdition edition) {
        List<CnfisSheetViewModel.CitationRow> rows = new ArrayList<>();
        List<CnfisSheetViewModel.LeftOut> leftOut = new ArrayList<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(userEmail)) {
            if (instance.getActivity() == null || !CITATION_ACTIVITY.equals(instance.getActivity().getName())) {
                continue;
            }
            Map<String, String> f = instance.getFields() == null ? Map.of() : instance.getFields();
            Integer year = parseYear(instance.getDate());
            String publication = blankToNull(f.get(FIELD_CITATION_PUBLICATION));
            if (year != null && year >= edition.referenceDate().getYear()) {
                continue; // after the reference date: the next edition's
            }
            String yearText = year == null ? "" : String.valueOf(year);
            Integer workYear = parseYear(f.get(FIELD_CITATION_WORK_YEAR));
            String reason = year == null ? "the year of the citation is not declared"
                    : workYear == null ? "the year of the cited work is not declared"
                    : publication == null ? "the publication that cites the work is not declared"
                    : null;
            if (reason != null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), publication, null, reason));
                continue;
            }
            String details = blankToNull(f.get(FIELD_CITATION_WORK_DETAILS));
            String work = nz(instance.getName()) + (details == null ? "" : " — " + details);
            rows.add(new CnfisSheetViewModel.CitationRow(instance.getId(), String.valueOf(workYear), work,
                    citationText(publication, blankToNull(f.get(FIELD_CITATION_ISSUE)), year), yearText));
        }
        rows.sort(Comparator.comparing(CnfisSheetViewModel.CitationRow::workYear).thenComparing(CnfisSheetViewModel.CitationRow::work));
        return new CitationsSheet(rows, leftOut);
    }

    /** "Publication, nr. issue, year" — the issue and the year only where the publication's text does not hold them. */
    static String citationText(String publication, String issue, int year) {
        StringBuilder text = new StringBuilder(publication);
        if (issue != null && !publication.contains(issue)) {
            text.append(", nr. ").append(issue);
        }
        if (!publication.contains(String.valueOf(year))) {
            text.append(", ").append(year);
        }
        return text.toString();
    }

    record SportSheet(List<CnfisSheetViewModel.SportRow> rows, List<CnfisSheetViewModel.LeftOut> leftOut) {
    }

    /**
     * Anexa 5.2 from the declared activity "Performanță sportivă (CNFIS 5.2)": the level of the championship and
     * the place obtained are the column of the form (a "1" in the cell), a record set is its own column, the
     * participants from the university their own. A performance without a level or a place, or with a place the
     * form has no cell for at that level, is left out and says so.
     */
    SportSheet sport(String userEmail, CnfisEdition edition) {
        List<CnfisSheetViewModel.SportRow> rows = new ArrayList<>();
        List<CnfisSheetViewModel.LeftOut> leftOut = new ArrayList<>();
        for (ActivityInstance instance : activityInstanceRepository.findAllByResearcherId(userEmail)) {
            if (instance.getActivity() == null || !SPORT_ACTIVITY.equals(instance.getActivity().getName())) {
                continue;
            }
            Integer year = parseYear(instance.getDate());
            if (year == null || !edition.covers(year)) {
                continue;
            }
            String yearText = String.valueOf(year);
            Map<String, String> f = instance.getFields() == null ? Map.of() : instance.getFields();
            String championship = f.get(FIELD_SPORT_CHAMPIONSHIP);
            String level = sportLevel(f.get(FIELD_SPORT_LEVEL));
            String place = sportPlace(f.get(FIELD_SPORT_PLACE));
            String record = sportRecord(f.get(FIELD_SPORT_RECORD));
            if (level == null || place == null) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), championship, null,
                        "the level of the championship or the place obtained is not declared"));
                continue;
            }
            if (CNFISReportExportService.sportColumn(level, place) < 0) {
                leftOut.add(new CnfisSheetViewModel.LeftOut(instance.getId(), yearText, instance.getName(), championship, null,
                        "the form has no cell for this place at this level (university and national: places 1–3; European: 1–3 and 4–6; "
                                + "international representation: 1–3; world: 1–6 and 7–8)"));
                continue;
            }
            rows.add(new CnfisSheetViewModel.SportRow(instance.getId(), yearText, instance.getName(), championship, level, place,
                    record, parseInt(f.get(FIELD_ARTS_PARTICIPANTS))));
        }
        rows.sort(Comparator.comparing(CnfisSheetViewModel.SportRow::year).thenComparing(CnfisSheetViewModel.SportRow::activity));
        return new SportSheet(rows, leftOut);
    }

    /** The declared level, as the activity's allowed values name it, to the column group of the form. */
    static String sportLevel(String declared) {
        if (declared == null) {
            return null;
        }
        String d = declared.trim().toLowerCase(java.util.Locale.ROOT);
        if (d.startsWith("universitar")) return "UNIVERSITY";
        if (d.startsWith("național") || d.startsWith("national")) return "NATIONAL";
        if (d.startsWith("european")) return "EUROPEAN";
        if (d.startsWith("competiție internațională") || d.startsWith("competitie internationala")) return "INTERNATIONAL_ROMANIA";
        if (d.startsWith("mondial")) return "WORLD";
        return null;
    }

    /** The declared place to the form's cell key. */
    static String sportPlace(String declared) {
        if (declared == null) {
            return null;
        }
        String d = declared.trim().toLowerCase(java.util.Locale.ROOT).replace("locurile", "").replace("locul", "").trim();
        return switch (d) {
            case "1" -> "PLACE_1";
            case "2" -> "PLACE_2";
            case "3" -> "PLACE_3";
            case "4" -> "PLACE_4";
            case "5" -> "PLACE_5";
            case "6" -> "PLACE_6";
            case "4-6", "4–6" -> "PLACES_4_6";
            case "7-8", "7–8" -> "PLACES_7_8";
            default -> null;
        };
    }

    /** The declared record to the form's column; null when none. */
    static String sportRecord(String declared) {
        if (declared == null) {
            return null;
        }
        String d = declared.trim().toLowerCase(java.util.Locale.ROOT);
        if (d.startsWith("național") || d.startsWith("national")) return "NATIONAL";
        if (d.startsWith("european")) return "EUROPEAN";
        if (d.startsWith("mondial")) return "WORLD";
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
