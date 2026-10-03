package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.reporting.CNFISReport2025;
import ro.uvt.pokedex.core.model.reporting.ScoringPublication;
import ro.uvt.pokedex.core.model.reporting.ScoringPublicationReadModel;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisSheetSnapshot;
import ro.uvt.pokedex.core.model.reporting.cnfis.CnfisUnitSheet;
import ro.uvt.pokedex.core.model.scopus.canonical.ScholardexForumView;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.UserRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisSheetSnapshotRepository;
import ro.uvt.pokedex.core.repository.cnfis.CnfisUnitSheetRepository;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;
import ro.uvt.pokedex.core.service.application.model.CnfisEditionViewModel;
import ro.uvt.pokedex.core.service.application.model.CnfisUnitViewModel;
import ro.uvt.pokedex.core.service.reporting.CNFISReportExportService;
import ro.uvt.pokedex.core.service.reporting.CnfisEdition;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * H129 slice 3 — the CNFIS reporting of a unit, for its head: who has frozen a sheet and who has not, a
 * provisional sheet for whoever has not, and Anexa 6 built from the sheets — the sum of what the members
 * handed in, as the CNFIS guide defines it. A department's members are its current affiliations; a
 * faculty's are those of all its departments.
 */
@Service
@RequiredArgsConstructor
public class CnfisUnitFacade {

    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository orgDivisionRepository;
    private final DepartmentAffiliationService departmentAffiliationService;
    private final UserRepository userRepository;
    private final CnfisSheetSnapshotRepository snapshotRepository;
    private final CnfisUnitSheetRepository unitSheetRepository;
    private final CnfisReportingFacade cnfisReportingFacade;
    private final ScholardexProjectionReadService scholardexProjectionReadService;
    private final CNFISReportExportService exportService;

    private record Unit(CnfisUnitSheet.UnitKind kind, String id, String name, Map<String, String> departmentByMember) {
    }

    // ── the page ────────────────────────────────────────────────────────────

    public Optional<CnfisUnitViewModel> buildUnit(CnfisUnitSheet.UnitKind kind, String unitId, int reportingYear) {
        Optional<CnfisEdition> editionOpt = CnfisEdition.ofReportingYear(reportingYear);
        Optional<Unit> unitOpt = unit(kind, unitId);
        if (editionOpt.isEmpty() || unitOpt.isEmpty()) {
            return Optional.empty();
        }
        Unit unit = unitOpt.get();
        Map<String, CnfisSheetSnapshot> representing = representingSheets(unit, reportingYear);
        List<CnfisUnitViewModel.Member> members = new ArrayList<>();
        for (User user : membersOf(unit)) {
            CnfisSheetSnapshot s = representing.get(user.getEmail());
            members.add(new CnfisUnitViewModel.Member(user.getEmail(), displayName(user),
                    unit.departmentByMember().get(user.getEmail()),
                    s != null && s.getHeader() != null ? s.getHeader().getDomainName() : null,
                    s == null ? null : s.getId(), s == null ? null : s.getCreatedAt().toString(),
                    s != null && s.isProvisional(), s != null && s.getLockedByUnitSheetId() != null,
                    s == null ? 0 : s.getRows().size(), s == null ? 0 : s.getPatents().size(),
                    s == null || s.getArtsCitationRows() == null ? 0 : s.getArtsCitationRows().size()));
        }
        List<CnfisUnitViewModel.Table> tables = unitSheetRepository
                .findByUnitKindAndUnitIdAndReportingYearOrderByCreatedAtDesc(kind, unitId, reportingYear).stream()
                .map(t -> new CnfisUnitViewModel.Table(t.getId(), t.getCreatedAt().toString(), t.getCreatedBy(),
                        t.getMembers().size(), (int) t.getMembers().stream().filter(CnfisUnitSheet.Member::isProvisional).count(),
                        t.getMissingMembers(), t.getRowCount(), t.getPatentCount()))
                .toList();
        CnfisEdition e = editionOpt.get();
        return Optional.of(new CnfisUnitViewModel(kind.name(), unitId, unit.name(),
                new CnfisEditionViewModel(e.reportingYear(), e.windowStart(), e.windowEnd(), e.lastListYear(), e.provisional()),
                members, tables));
    }

    // ── provisional sheets ──────────────────────────────────────────────────

    /** A provisional sheet for one member of the unit ({@code memberEmail}), or for every member without a sheet. */
    public int generateProvisional(CnfisUnitSheet.UnitKind kind, String unitId, int reportingYear,
                                   String memberEmail, String headEmail) {
        Optional<CnfisEdition> editionOpt = CnfisEdition.ofReportingYear(reportingYear);
        Optional<Unit> unitOpt = unit(kind, unitId);
        if (editionOpt.isEmpty() || unitOpt.isEmpty()) {
            return 0;
        }
        Unit unit = unitOpt.get();
        Map<String, CnfisSheetSnapshot> representing = representingSheets(unit, reportingYear);
        int generated = 0;
        for (User user : membersOf(unit)) {
            boolean asked = memberEmail == null ? !representing.containsKey(user.getEmail()) : memberEmail.equals(user.getEmail());
            if (asked && cnfisReportingFacade.freezeProvisional(user.getEmail(), editionOpt.get(), headEmail).isPresent()) {
                generated++;
            }
        }
        return generated;
    }

    // ── Anexa 6 ─────────────────────────────────────────────────────────────

    /** Builds the table from the representing sheets and locks them; empty when the unit or edition is unknown. */
    public Optional<CnfisUnitSheet> buildTable(CnfisUnitSheet.UnitKind kind, String unitId, int reportingYear, String headEmail) {
        Optional<Unit> unitOpt = unit(kind, unitId);
        if (CnfisEdition.ofReportingYear(reportingYear).isEmpty() || unitOpt.isEmpty()) {
            return Optional.empty();
        }
        Unit unit = unitOpt.get();
        Map<String, CnfisSheetSnapshot> representing = representingSheets(unit, reportingYear);
        CnfisUnitSheet table = new CnfisUnitSheet();
        table.setUnitKind(kind);
        table.setUnitId(unitId);
        table.setUnitName(unit.name());
        table.setReportingYear(reportingYear);
        table.setCreatedAt(Instant.now());
        table.setCreatedBy(headEmail);
        Set<String> publicationIds = new LinkedHashSet<>();
        Set<String> patentIds = new LinkedHashSet<>();
        for (User user : membersOf(unit)) {
            CnfisSheetSnapshot s = representing.get(user.getEmail());
            if (s == null) {
                table.getMissingMembers().add(displayName(user));
                continue;
            }
            CnfisUnitSheet.Member member = new CnfisUnitSheet.Member();
            member.setUserEmail(user.getEmail());
            member.setDisplayName(displayName(user));
            member.setSnapshotId(s.getId());
            member.setProvisional(s.isProvisional());
            member.setDepartmentName(unit.departmentByMember().get(user.getEmail()));
            table.getMembers().add(member);
            s.getRows().forEach(r -> publicationIds.add(r.getPublicationId()));
            s.getPatents().forEach(p -> patentIds.add(p.getActivityInstanceId()));
        }
        table.setRowCount(publicationIds.size());
        table.setPatentCount(patentIds.size());
        CnfisUnitSheet saved = unitSheetRepository.save(table);
        for (CnfisUnitSheet.Member member : saved.getMembers()) {
            CnfisSheetSnapshot s = representing.get(member.getUserEmail());
            s.setLockedByUnitSheetId(saved.getId());
            snapshotRepository.save(s);
        }
        return Optional.of(saved);
    }

    /** Deletes a table and releases the member sheets it had locked. */
    public boolean deleteTable(CnfisUnitSheet.UnitKind kind, String unitId, String tableId) {
        Optional<CnfisUnitSheet> table = unitSheetRepository.findById(tableId)
                .filter(t -> t.getUnitKind() == kind && unitId.equals(t.getUnitId()));
        if (table.isEmpty()) {
            return false;
        }
        for (CnfisSheetSnapshot s : snapshotRepository.findByLockedByUnitSheetId(tableId)) {
            s.setLockedByUnitSheetId(null);
            snapshotRepository.save(s);
        }
        unitSheetRepository.deleteById(tableId);
        return true;
    }

    /**
     * The workbook of a table, from the sheets it was built from. A paper two members share appears once;
     * so does a patent (the sheets are individual, the table is the unit's) — H145: the same patent declared by two
     * colleagues too (the same granted codes, else the same title and year), with the larger count of authors.
     */
    public Optional<byte[]> exportTable(CnfisUnitSheet.UnitKind kind, String unitId, String tableId) throws IOException {
        Optional<CnfisUnitSheet> tableOpt = unitSheetRepository.findById(tableId)
                .filter(t -> t.getUnitKind() == kind && unitId.equals(t.getUnitId()));
        if (tableOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, ScoringPublicationReadModel> publications = new LinkedHashMap<>();
        Map<String, CNFISReport2025> reports = new LinkedHashMap<>();
        Map<String, CNFISReport2025> patents = new LinkedHashMap<>();
        Set<String> forumIds = new LinkedHashSet<>();
        for (CnfisUnitSheet.Member member : tableOpt.get().getMembers()) {
            Optional<CnfisSheetSnapshot> s = snapshotRepository.findById(member.getSnapshotId());
            if (s.isEmpty()) {
                continue;
            }
            for (CnfisSheetSnapshot.Row row : s.get().getRows()) {
                if (publications.containsKey(row.getPublicationId())) {
                    continue;
                }
                publications.put(row.getPublicationId(), new ScoringPublication(
                        row.getPublicationId(), null, row.getForumId(),
                        row.getYear() == null ? null : row.getYear() + "-01-01", null, null, List.of(),
                        row.getAuthorCount(), row.getDoi(), row.getWosCode(), row.getTitle(), 0, Set.of()));
                reports.put(row.getPublicationId(), row.getClassification());
                if (row.getForumId() != null) {
                    forumIds.add(row.getForumId());
                }
            }
            for (CnfisSheetSnapshot.Patent p : s.get().getPatents()) {
                String key = patentKey(p);
                CNFISReport2025 known = patents.get(key);
                if (known != null) {
                    known.setNumarAutori(Math.max(known.getNumarAutori(), p.getAuthorCount()));
                    known.setNumarAutoriUniversitate(Math.max(known.getNumarAutoriUniversitate(), p.getUniversityAuthorCount()));
                    continue;
                }
                CNFISReport2025 r = new CNFISReport2025();
                r.setTitlu(p.getTitle());
                r.setBrevetCode(p.getCode());
                r.setOficiuBrevet(p.getOffice());
                r.setListYear(p.getYear() == null || p.getYear().isBlank() ? null : Integer.valueOf(p.getYear()));
                CnfisReportingFacade.setPatentType(r, p.getType());
                r.setNumarAutori(p.getAuthorCount());
                r.setNumarAutoriUniversitate(p.getUniversityAuthorCount());
                patents.put(key, r);
            }
        }
        Map<String, ScholardexForumView> forumMap = new HashMap<>();
        scholardexProjectionReadService.findForumsByIdIn(forumIds).forEach(f -> forumMap.put(f.getId(), f));
        return Optional.of(exportService.generateAnexa6(new ArrayList<>(publications.values()),
                new ArrayList<>(reports.values()), forumMap, new ArrayList<>(patents.values())));
    }

    /**
     * Anexa 6.1 of a table: the artistic performances of its members' sheets, each performance once — H145: also when
     * several colleagues declared it (the same work at the same event in the same year), counting them among its
     * university participants.
     */
    public Optional<byte[]> exportArtsTable(CnfisUnitSheet.UnitKind kind, String unitId, String tableId) throws IOException {
        Optional<CnfisUnitSheet> tableOpt = unitSheetRepository.findById(tableId)
                .filter(t -> t.getUnitKind() == kind && unitId.equals(t.getUnitId()));
        if (tableOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, CnfisSheetSnapshot.ArtsRow> rows = new LinkedHashMap<>();
        Map<String, Set<String>> declarers = new HashMap<>();
        for (CnfisUnitSheet.Member member : tableOpt.get().getMembers()) {
            snapshotRepository.findById(member.getSnapshotId()).ifPresent(s -> s.getArtsRows().forEach(r -> {
                String key = factKey(r.getWork(), r.getEvent(), r.getYear());
                declarers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member.getUserEmail());
                CnfisSheetSnapshot.ArtsRow known = rows.putIfAbsent(key, copy(r));
                if (known != null) {
                    known.setUniversityParticipants(Math.max(known.getUniversityParticipants(), r.getUniversityParticipants()));
                }
            }));
        }
        rows.forEach((key, r) -> r.setUniversityParticipants(Math.max(r.getUniversityParticipants(), declarers.get(key).size())));
        return Optional.of(exportService.generateAnexa61(CnfisReportingFacade.toExportArts(new ArrayList<>(rows.values()))));
    }

    /** Anexa 6.2 of a table: the sport performances of its members' sheets, each performance once (H145: by its facts). */
    public Optional<byte[]> exportSportTable(CnfisUnitSheet.UnitKind kind, String unitId, String tableId) throws IOException {
        Optional<CnfisUnitSheet> tableOpt = unitSheetRepository.findById(tableId)
                .filter(t -> t.getUnitKind() == kind && unitId.equals(t.getUnitId()));
        if (tableOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, CnfisSheetSnapshot.SportRow> rows = new LinkedHashMap<>();
        Map<String, Set<String>> declarers = new HashMap<>();
        for (CnfisUnitSheet.Member member : tableOpt.get().getMembers()) {
            snapshotRepository.findById(member.getSnapshotId())
                    .ifPresent(s -> (s.getSportRows() == null ? List.<CnfisSheetSnapshot.SportRow>of() : s.getSportRows())
                            .forEach(r -> {
                                String key = factKey(r.getActivity(), r.getChampionship(), r.getYear()) + "|" + r.getPlace();
                                declarers.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(member.getUserEmail());
                                CnfisSheetSnapshot.SportRow known = rows.putIfAbsent(key, copy(r));
                                if (known != null) {
                                    known.setUniversityParticipants(Math.max(known.getUniversityParticipants(), r.getUniversityParticipants()));
                                }
                            }));
        }
        rows.forEach((key, r) -> r.setUniversityParticipants(Math.max(r.getUniversityParticipants(), declarers.get(key).size())));
        return Optional.of(exportService.generateAnexa62(CnfisReportingFacade.toExportSport(new ArrayList<>(rows.values()))));
    }

    /** Anexa 6.3 of a table: the humanities rows of its members' sheets, each work once ("Fără dubluri"). */
    public Optional<byte[]> exportHumanitiesTable(CnfisUnitSheet.UnitKind kind, String unitId, String tableId) throws IOException {
        Optional<CnfisUnitSheet> tableOpt = unitSheetRepository.findById(tableId)
                .filter(t -> t.getUnitKind() == kind && unitId.equals(t.getUnitId()));
        if (tableOpt.isEmpty()) {
            return Optional.empty();
        }
        Map<String, CnfisSheetSnapshot.HumanitiesRow> rows = new LinkedHashMap<>();
        for (CnfisUnitSheet.Member member : tableOpt.get().getMembers()) {
            // H145: a work two colleagues declared (no shared publication id) is the same work by its DOI, else its titles
            snapshotRepository.findById(member.getSnapshotId())
                    .ifPresent(s -> s.getHumanitiesRows().forEach(r -> rows.putIfAbsent(humanitiesKey(r), r)));
        }
        return Optional.of(exportService.generateAnexa63(CnfisReportingFacade.toExportHumanities(new ArrayList<>(rows.values()))));
    }

    // ── pieces ──────────────────────────────────────────────────────────────

    /** H145: one patent, whoever declared it — its granted codes, else its title and year. */
    static String patentKey(CnfisSheetSnapshot.Patent p) {
        Set<String> codes = ro.uvt.pokedex.core.service.reporting.RegistryScoringSupport
                .grantedPatentCodes(p.getCode(), p.getOffice());
        return codes.isEmpty() ? "title:" + factKey(p.getTitle(), null, p.getYear()) : "codes:" + String.join(",", codes);
    }

    static String humanitiesKey(CnfisSheetSnapshot.HumanitiesRow r) {
        String doi = ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService.normalizeDoi(r.getDoi());
        if (doi != null && doi.startsWith("10.")) { // a source's placeholder ("null") is no DOI
            return "doi:" + doi;
        }
        String title = ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService.normalizeTitle(r.getItemTitle());
        return title == null && r.getSourceId() != null ? "id:" + r.getSourceId()
                : "title:" + factKey(r.getItemTitle(), r.getContainerTitle(), r.getYear());
    }

    /** Normalised titles and the year, so a colleague's spelling of the same entry lands on the same key. */
    static String factKey(String what, String where, String year) {
        return nz(ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService.normalizeTitle(what))
                + "|" + nz(ro.uvt.pokedex.core.service.importing.scopus.ScholardexPublicationCanonicalizationService.normalizeTitle(where))
                + "|" + nz(year);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static CnfisSheetSnapshot.ArtsRow copy(CnfisSheetSnapshot.ArtsRow r) {
        CnfisSheetSnapshot.ArtsRow c = new CnfisSheetSnapshot.ArtsRow();
        c.setActivityInstanceId(r.getActivityInstanceId());
        c.setYear(r.getYear());
        c.setWork(r.getWork());
        c.setEvent(r.getEvent());
        c.setLevel(r.getLevel());
        c.setKind(r.getKind());
        c.setUniversityParticipants(r.getUniversityParticipants());
        return c;
    }

    private static CnfisSheetSnapshot.SportRow copy(CnfisSheetSnapshot.SportRow r) {
        CnfisSheetSnapshot.SportRow c = new CnfisSheetSnapshot.SportRow();
        c.setActivityInstanceId(r.getActivityInstanceId());
        c.setYear(r.getYear());
        c.setActivity(r.getActivity());
        c.setChampionship(r.getChampionship());
        c.setLevel(r.getLevel());
        c.setPlace(r.getPlace());
        c.setRecord(r.getRecord());
        c.setUniversityParticipants(r.getUniversityParticipants());
        return c;
    }

    private Optional<Unit> unit(CnfisUnitSheet.UnitKind kind, String unitId) {
        if (kind == CnfisUnitSheet.UnitKind.DEPARTMENT) {
            return departmentRepository.findById(unitId).map(d -> {
                Map<String, String> byMember = new LinkedHashMap<>();
                departmentAffiliationService.listCurrentAffiliations(d.getId())
                        .forEach(a -> byMember.put(a.getUserId(), d.getName()));
                return new Unit(kind, d.getId(), d.getName(), byMember);
            });
        }
        return orgDivisionRepository.findById(unitId).map(division -> {
            Map<String, String> byMember = new LinkedHashMap<>();
            for (Department d : departmentRepository.findByDivisionId(division.getId())) {
                departmentAffiliationService.listCurrentAffiliations(d.getId())
                        .forEach(a -> byMember.putIfAbsent(a.getUserId(), d.getName()));
            }
            return new Unit(kind, division.getId(), division.getName(), byMember);
        });
    }

    /** The unit's members with a researcher profile, by name. */
    private List<User> membersOf(Unit unit) {
        List<User> users = new ArrayList<>();
        userRepository.findAllById(unit.departmentByMember().keySet()).forEach(u -> {
            if (u.getResearcherProfile() != null) {
                users.add(u);
            }
        });
        users.sort(Comparator.comparing(CnfisUnitFacade::displayName, String.CASE_INSENSITIVE_ORDER));
        return users;
    }

    /**
     * The sheet that represents each member: the latest one they froze themselves; failing that, the latest
     * provisional one a head generated.
     */
    private Map<String, CnfisSheetSnapshot> representingSheets(Unit unit, int reportingYear) {
        Map<String, CnfisSheetSnapshot> out = new HashMap<>();
        for (CnfisSheetSnapshot s : snapshotRepository.findByUserEmailInAndReportingYear(unit.departmentByMember().keySet(), reportingYear)) {
            CnfisSheetSnapshot current = out.get(s.getUserEmail());
            if (current == null || better(s, current)) {
                out.put(s.getUserEmail(), s);
            }
        }
        return out;
    }

    private static boolean better(CnfisSheetSnapshot candidate, CnfisSheetSnapshot current) {
        if (candidate.isProvisional() != current.isProvisional()) {
            return !candidate.isProvisional();
        }
        return candidate.getCreatedAt() != null
                && (current.getCreatedAt() == null || candidate.getCreatedAt().isAfter(current.getCreatedAt()));
    }

    private static String displayName(User user) {
        return user.getResearcherProfile() == null ? user.getEmail() : user.getResearcherProfile().getName();
    }
}
