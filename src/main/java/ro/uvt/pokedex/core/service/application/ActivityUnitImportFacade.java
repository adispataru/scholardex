package ro.uvt.pokedex.core.service.application;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import ro.uvt.pokedex.core.model.org.Department;
import ro.uvt.pokedex.core.model.org.OrgDivision;
import ro.uvt.pokedex.core.model.user.User;
import ro.uvt.pokedex.core.repository.org.DepartmentRepository;
import ro.uvt.pokedex.core.repository.org.OrgDivisionRepository;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * H142 slice 2 — a head (or an admin) imports the filled fișe of a unit's members at once, when the faculty already
 * has them; each colleague importing their own fișă stays the normal flow. Every file goes to the member its heading
 * or its file name names ("Lect.univ.dr. NUME PRENUME", "Fișă NUME PRENUME.xlsx"): their last name and one of their
 * first names. A file that names nobody, or more than one member, is not imported; with a single file the head may
 * choose the member.
 */
@Service
@RequiredArgsConstructor
public class ActivityUnitImportFacade {

    public record Member(String email, String lastName, String firstName) {
        public String displayName() {
            String shown = (firstName + " " + lastName).trim();
            return shown.isEmpty() ? email : shown;
        }
    }

    public record UnitPage(String kind, String unitId, String unitName, List<Member> members) {
    }

    public record UploadedFile(String name, byte[] bytes) {
    }

    /** {@code outcome}: IMPORTED, NOT_MATCHED, AMBIGUOUS or REFUSED (the file is no grid nor Anexa 5.1). */
    public record FileResult(String fileName, String memberEmail, String memberName, String outcome,
                             ActivityFileImportService.ImportReport report) {
    }

    private final OrgUnitRosterService rosterService;
    private final ActivityFileImportService importService;
    private final DepartmentRepository departmentRepository;
    private final OrgDivisionRepository divisionRepository;
    private final ro.uvt.pokedex.core.repository.RegistryDomainExpertsRepository domainExpertsRepository;
    private final ArtisticEventFacultyRanking facultyRanking;

    /** H142 slice 7 — the source a faculty's submitted CNFIS files are recorded under. */
    public static final String FACULTY_REPORT_SOURCE = "Raportare CNFIS 2025 (depusă de facultate)";

    /** The domains whose experts rank artistic events, and the one mapped to the unit (a department), if any. */
    public record FacultyImportForm(List<String> domains, String defaultDomain) {
    }

    /** What a faculty's submitted files did: each file's result, and the events ranked at the level reported. */
    public record FacultyImportResult(List<FileResult> files, ArtisticEventFacultyRanking.Outcome ranking) {
    }

    public Optional<UnitPage> page(String kind, String unitId) {
        String name = unitName(kind, unitId);
        if (name == null) {
            return Optional.empty();
        }
        return Optional.of(new UnitPage(kind, unitId, name, members(kind, unitId)));
    }

    public List<FileResult> importFiles(String kind, String unitId, List<UploadedFile> files, String chosenMember,
                                        String uploadedBy) {
        List<Member> members = members(kind, unitId);
        List<FileResult> results = new ArrayList<>();
        for (UploadedFile file : files) {
            String heading;
            ActivityFileImportService.FileKind fileKind;
            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
                fileKind = ActivityFileImportService.kindOf(workbook);
                heading = ActivityFileImportService.headingOf(workbook);
            } catch (Exception unreadable) {
                fileKind = ActivityFileImportService.FileKind.UNSUPPORTED;
                heading = null;
            }
            if (!fileKind.importable()) {
                results.add(new FileResult(file.name(), null, null, "REFUSED", null));
                continue;
            }
            List<Member> matched;
            if (chosenMember != null && !chosenMember.isBlank() && files.size() == 1) {
                matched = members.stream().filter(m -> m.email().equalsIgnoreCase(chosenMember.trim())).toList();
            } else {
                matched = matching(members, (heading == null ? "" : heading) + " " + file.name());
            }
            if (matched.size() != 1) {
                results.add(new FileResult(file.name(), null, null, matched.isEmpty() ? "NOT_MATCHED" : "AMBIGUOUS", null));
                continue;
            }
            Member member = matched.getFirst();
            ActivityFileImportService.ImportReport report = importService.importFile(member.email(), file.name(),
                    new ByteArrayInputStream(file.bytes()), uploadedBy);
            results.add(new FileResult(file.name(), member.email(), member.displayName(), "IMPORTED", report));
        }
        return results;
    }

    public FacultyImportForm facultyImportForm(String kind, String unitId) {
        List<String> domains = new ArrayList<>();
        String mapped = null;
        for (ro.uvt.pokedex.core.model.registry.RegistryDomainExperts d : domainExpertsRepository.findAll()) {
            if (d.getDomain() == null) continue;
            domains.add(d.getDomain());
            if ("departments".equals(kind) && d.getDepartmentIds() != null && d.getDepartmentIds().contains(unitId)) {
                mapped = d.getDomain();
            }
        }
        domains.sort(Comparator.naturalOrder());
        return new FacultyImportForm(domains, mapped);
    }

    /**
     * H142 slice 7 — files the faculty already submitted (its CNFIS report): each is imported as its member's, the records
     * checked rather than "to check"; then the artistic events the Anexa 5.1 sheets name are ranked once, at the level
     * the faculty reported ({@link ArtisticEventFacultyRanking}), in {@code domain}.
     */
    public FacultyImportResult importFacultySubmission(String kind, String unitId, List<UploadedFile> files, String uploadedBy,
                                                       String domain) {
        List<Member> members = members(kind, unitId);
        ActivityFileImportService.ImportOptions options = new ActivityFileImportService.ImportOptions(true, FACULTY_REPORT_SOURCE);
        List<FileResult> results = new ArrayList<>();
        List<ActivityFileImportService.ReportedLevel> levels = new ArrayList<>();
        for (UploadedFile file : files) {
            Optional<Member> member = match(members, file, results);
            if (member.isEmpty()) continue;
            ActivityFileImportService.ImportReport report = importService.importFile(member.get().email(), file.name(),
                    new ByteArrayInputStream(file.bytes()), uploadedBy, options);
            results.add(new FileResult(file.name(), member.get().email(), member.get().displayName(), "IMPORTED", report));
            levels.addAll(report.reportedLevels());
        }
        ArtisticEventFacultyRanking.Outcome ranking = domain == null || domain.isBlank()
                ? ArtisticEventFacultyRanking.Outcome.NONE
                : facultyRanking.rank(levels, domain.trim(), uploadedBy, FACULTY_REPORT_SOURCE);
        return new FacultyImportResult(results, ranking);
    }

    /** The member a file names (its heading, its name); a refused, unmatched or ambiguous file is reported instead. */
    private static Optional<Member> match(List<Member> members, UploadedFile file, List<FileResult> results) {
        String heading;
        ActivityFileImportService.FileKind fileKind;
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(file.bytes()))) {
            fileKind = ActivityFileImportService.kindOf(workbook);
            heading = ActivityFileImportService.headingOf(workbook);
        } catch (Exception unreadable) {
            fileKind = ActivityFileImportService.FileKind.UNSUPPORTED;
            heading = null;
        }
        if (!fileKind.importable()) {
            results.add(new FileResult(file.name(), null, null, "REFUSED", null));
            return Optional.empty();
        }
        List<Member> matched = matching(members, (heading == null ? "" : heading) + " " + file.name());
        if (matched.size() != 1) {
            results.add(new FileResult(file.name(), null, null, matched.isEmpty() ? "NOT_MATCHED" : "AMBIGUOUS", null));
            return Optional.empty();
        }
        return Optional.of(matched.getFirst());
    }

    /** The members a text names: every token of their last name and one of their first names. */
    static List<Member> matching(List<Member> members, String text) {
        Set<String> words = tokens(text);
        List<Member> out = new ArrayList<>();
        for (Member member : members) {
            Set<String> last = tokens(member.lastName());
            Set<String> first = tokens(member.firstName());
            if (!last.isEmpty() && words.containsAll(last) && (first.isEmpty() || first.stream().anyMatch(words::contains))) {
                out.add(member);
            }
        }
        return out;
    }

    private List<Member> members(String kind, String unitId) {
        List<OrgUnitRosterService.RosterMember> roster = "divisions".equals(kind)
                ? rosterService.divisionRoster(unitId) : rosterService.departmentRoster(unitId);
        return roster.stream()
                .map(OrgUnitRosterService.RosterMember::user)
                .map(ActivityUnitImportFacade::member)
                .sorted(Comparator.comparing(Member::lastName).thenComparing(Member::firstName))
                .toList();
    }

    private static Member member(User user) {
        User.ResearcherProfile p = user.getResearcherProfile();
        String last = p == null || p.getLastName() == null ? "" : p.getLastName();
        String first = p == null || p.getFirstName() == null ? "" : p.getFirstName();
        return new Member(user.getEmail(), last, first);
    }

    private String unitName(String kind, String unitId) {
        if ("divisions".equals(kind)) {
            return divisionRepository.findById(unitId).map(OrgDivision::getName).orElse(null);
        }
        if ("departments".equals(kind)) {
            return departmentRepository.findById(unitId).map(Department::getName).orElse(null);
        }
        return null;
    }

    private static Set<String> tokens(String text) {
        String n = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        return Arrays.stream(n.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
