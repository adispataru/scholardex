package ro.uvt.pokedex.core.view;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.FormElement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.format.FormatterRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.CompositionNode;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Criterion;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Perspective;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.Threshold;
import ro.uvt.pokedex.core.model.reporting.AbstractReport.ThresholdCapAddition;
import ro.uvt.pokedex.core.model.reporting.Indicator;
import ro.uvt.pokedex.core.model.reporting.IndividualReport;
import ro.uvt.pokedex.core.model.reporting.Position;
import ro.uvt.pokedex.core.repository.InstitutionRepository;
import ro.uvt.pokedex.core.repository.reporting.IndicatorRepository;
import ro.uvt.pokedex.core.repository.reporting.IndividualReportRepository;
import ro.uvt.pokedex.core.service.application.IndividualReportsManagementFacade;
import ro.uvt.pokedex.core.service.application.ReportTransferFacade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin report edit form manages only part of a report: title, indicators, export binding, and per
 * criterion the name, indicators, thresholds and the plafon. Perspectives, criterion weights, percent caps
 * and threshold-cap additions are written by scripts and have no inputs on the page — so a plain save of the
 * bound form object used to wipe them, changing scores and verdicts without a word.
 *
 * <p>These tests post <b>what the page really sends</b>: the edit page is rendered, its form is read the way
 * a browser submits it, and that payload goes to the update endpoint. The real facade runs; only the
 * repositories are mocked.</p>
 */
@WebMvcTest(AdminIndividualReportsController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(IndividualReportsManagementFacade.class)
class AdminIndividualReportFormRoundTripTest {

    private static final String REPORT_ID = "rep-1";

    /** Stands in for Spring Data's DomainClassConverter, which resolves posted ids through real repositories. */
    @TestConfiguration
    static class IdConverters implements WebMvcConfigurer {
        @Override
        public void addFormatters(FormatterRegistry registry) {
            registry.addConverter(new Converter<String, Indicator>() {
                @Override
                public Indicator convert(String id) {
                    return id.isBlank() ? null : indicator(id);
                }
            });
            registry.addConverter(new Converter<Indicator, String>() {
                @Override
                public String convert(Indicator indicator) {
                    return indicator.getId();
                }
            });
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IndividualReportRepository individualReportRepository;
    @MockitoBean
    private IndicatorRepository indicatorRepository;
    @MockitoBean
    private InstitutionRepository institutionRepository;
    @MockitoBean
    private ReportTransferFacade reportTransferFacade;

    @BeforeEach
    void setUp() {
        when(indicatorRepository.findAll()).thenReturn(
                List.of(indicator("i-art"), indicator("i-cit"), indicator("i-hirsch"), indicator("i-extra")));
        when(institutionRepository.findAll()).thenReturn(List.of());
        when(individualReportRepository.save(any(IndividualReport.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ fixtures

    private static Indicator indicator(String id) {
        Indicator indicator = new Indicator();
        indicator.setId(id);
        indicator.setName("name of " + id);
        return indicator;
    }

    private static Threshold threshold(Position position, double value) {
        Threshold threshold = new Threshold();
        threshold.setPosition(position);
        threshold.setValue(value);
        return threshold;
    }

    private static Criterion criterion(String name, List<Integer> indicatorIndices, Threshold... thresholds) {
        Criterion criterion = new Criterion();
        criterion.setName(name);
        criterion.setIndicatorIndices(new ArrayList<>(indicatorIndices));
        criterion.setThresholds(new ArrayList<>(List.of(thresholds)));
        return criterion;
    }

    private static CompositionNode leaf(int criterionIndex) {
        CompositionNode node = new CompositionNode();
        node.setCriterion(criterionIndex);
        return node;
    }

    /**
     * A report shaped like the 2026 ones: a weighted total (physics T = I/2 + C/20 + h/5), a percent cap, a
     * threshold-cap addition (FEAA), and perspectives with a nested any-route and a perspective reference.
     */
    private static IndividualReport scriptedReport() {
        IndividualReport report = new IndividualReport();
        report.setId(REPORT_ID);
        report.setTitle("FV Test 2026");
        report.setDescription("Standarde minimale, test");
        report.setIndicators(new ArrayList<>(List.of(indicator("i-art"), indicator("i-cit"), indicator("i-hirsch"))));

        Criterion articles = criterion("I — articole", List.of(0),
                threshold(Position.CONF_UNIV, 2), threshold(Position.PROF_UNIV, 4));
        Criterion impact = criterion("C — citări și Hirsch", List.of(1, 2), threshold(Position.CONF_UNIV, 20));
        impact.setMaxTotal(50.0);
        impact.setMaxPercentOfTotal(new LinkedHashMap<>(Map.of(2, 10.0)));
        Criterion total = criterion("T — punctaj total", List.of(0, 1, 2),
                threshold(Position.CONF_UNIV, 5), threshold(Position.HABIL, 11.5));
        Map<Integer, Double> weights = new LinkedHashMap<>();
        weights.put(0, 0.5);
        weights.put(1, 0.05);
        weights.put(2, 0.2);
        total.setWeights(weights);
        ThresholdCapAddition addition = new ThresholdCapAddition();
        addition.setIndicatorIndex(0);
        addition.setPercent(50.0);
        addition.setThresholdCriterionIndex(0);
        total.setThresholdCapAdditions(new ArrayList<>(List.of(addition)));
        report.setCriteria(new ArrayList<>(List.of(articles, impact, total)));

        Perspective research = new Perspective();
        research.setName("Activitatea de cercetare");
        CompositionNode routeA = leaf(1);
        routeA.setLabel("Ruta a");
        CompositionNode routes = new CompositionNode();
        routes.setAny(new ArrayList<>(List.of(routeA, leaf(2))));
        CompositionNode researchRoot = new CompositionNode();
        researchRoot.setAll(new ArrayList<>(List.of(leaf(0), routes)));
        research.setComposition(researchRoot);

        Perspective verdict = new Perspective();
        verdict.setName("Total");
        CompositionNode earlier = new CompositionNode();
        earlier.setPerspective(0);
        CompositionNode verdictRoot = new CompositionNode();
        verdictRoot.setAll(new ArrayList<>(List.of(earlier, leaf(2))));
        verdict.setComposition(verdictRoot);
        report.setPerspectives(new ArrayList<>(List.of(research, verdict)));
        return report;
    }

    /** Every read hands out a fresh copy, as the database does — nothing is shared between render and save. */
    private void stored(java.util.function.Supplier<IndividualReport> report) {
        when(individualReportRepository.findById(REPORT_ID)).thenAnswer(inv -> Optional.of(report.get()));
    }

    // ------------------------------------------------------------------ the browser's side

    /** Renders the edit page and returns the payload a browser would submit from its form. */
    private List<String[]> renderedForm() throws Exception {
        String html = mockMvc.perform(get("/admin/individualReports/edit/" + REPORT_ID))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Document page = Jsoup.parse(html);
        FormElement form = (FormElement) page.selectFirst("form#individual-report-admin-form");
        assertNotNull(form, "the edit page has no report form");
        List<String[]> payload = new ArrayList<>();
        for (Connection.KeyVal field : form.formData()) {
            payload.add(new String[]{field.key(), field.value()});
        }
        return payload;
    }

    private MvcResult submit(List<String[]> payload) throws Exception {
        MockHttpServletRequestBuilder request = post("/admin/individualReports/update");
        for (String[] field : payload) {
            request = request.param(field[0], field[1]);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static void set(List<String[]> payload, String name, String value) {
        for (String[] field : payload) {
            if (field[0].equals(name)) {
                field[1] = value;
                return;
            }
        }
        payload.add(new String[]{name, value});
    }

    private static void remove(List<String[]> payload, String prefix) {
        payload.removeIf(field -> field[0].startsWith(prefix));
    }

    private IndividualReport saved() {
        ArgumentCaptor<IndividualReport> captor = ArgumentCaptor.forClass(IndividualReport.class);
        verify(individualReportRepository).save(captor.capture());
        return captor.getValue();
    }

    private static void assertScriptedFieldsIntact(IndividualReport saved) {
        IndividualReport original = scriptedReport();
        assertEquals(original.getPerspectives(), saved.getPerspectives(), "perspectives");
        for (int i = 0; i < original.getCriteria().size(); i++) {
            Criterion expected = original.getCriteria().get(i);
            Criterion actual = saved.getCriteria().get(i);
            assertEquals(expected.getWeights(), actual.getWeights(), "weights of criterion " + i);
            assertEquals(expected.getMaxPercentOfTotal(), actual.getMaxPercentOfTotal(),
                    "percent caps of criterion " + i);
            assertEquals(expected.getThresholdCapAdditions(), actual.getThresholdCapAdditions(),
                    "threshold-cap additions of criterion " + i);
        }
    }

    // ------------------------------------------------------------------ the wipe

    @Test
    void savingThePageUntouchedChangesNothing() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);

        mockMvc.perform(buildPost(renderedForm()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/individualReports"));

        IndividualReport saved = saved();
        assertScriptedFieldsIntact(saved);
        // …and what the form does manage came through the round trip unchanged as well.
        IndividualReport original = scriptedReport();
        assertEquals(original.getTitle(), saved.getTitle());
        assertEquals(List.of("i-art", "i-cit", "i-hirsch"),
                saved.getIndicators().stream().map(Indicator::getId).toList());
        assertEquals(original.getCriteria(), saved.getCriteria());
    }

    private MockHttpServletRequestBuilder buildPost(List<String[]> payload) {
        MockHttpServletRequestBuilder request = post("/admin/individualReports/update");
        for (String[] field : payload) {
            request = request.param(field[0], field[1]);
        }
        return request;
    }

    @Test
    void theCompetitionFamilyOfAReportSurvivesASave() throws Exception {
        // H138: the family that makes a report domain-selectable has its input (H116: no input → wiped on save)
        stored(() -> {
            IndividualReport report = scriptedReport();
            report.setCompetitionFamily(ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.EXACT);
            return report;
        });
        List<String[]> untouched = renderedForm();
        assertTrue(untouched.stream().anyMatch(f -> f[0].equals("competitionFamily") && f[1].equals("EXACT")));
        mockMvc.perform(buildPost(untouched)).andExpect(status().is3xxRedirection());
        assertEquals(ro.uvt.pokedex.core.model.reporting.uefiscdi.CompetitionFamily.EXACT, saved().getCompetitionFamily());
    }

    @Test
    void thePhdLimitOfAReportSurvivesASave() throws Exception {
        // H137: the UEFISCDI PhD-age limit and its deadline have inputs, so a save keeps them (H116)
        stored(() -> {
            IndividualReport report = scriptedReport();
            report.setPhdLimitYears(12);
            report.setCompetitionDeadline(java.time.LocalDate.of(2026, 7, 30));
            return report;
        });

        List<String[]> untouched = renderedForm();
        assertTrue(untouched.stream().anyMatch(f -> f[0].equals("phdLimitYears") && f[1].equals("12")));
        assertTrue(untouched.stream().anyMatch(f -> f[0].equals("competitionDeadline") && f[1].equals("2026-07-30")));
        mockMvc.perform(buildPost(untouched)).andExpect(status().is3xxRedirection());
        assertEquals(12, saved().getPhdLimitYears());
        assertEquals(java.time.LocalDate.of(2026, 7, 30), saved().getCompetitionDeadline());
    }

    @Test
    void theAuthorityOfAReportSurvivesASaveAndCanBeChangedOnThePage() throws Exception {
        // H129: a field the form has no input for is wiped by a save (H116) — this one has its input
        stored(() -> {
            IndividualReport report = scriptedReport();
            report.setAuthority(ro.uvt.pokedex.core.model.reporting.ReportAuthority.UEFISCDI);
            return report;
        });

        List<String[]> untouched = renderedForm();
        assertTrue(untouched.stream().anyMatch(f -> f[0].equals("authority") && f[1].equals("UEFISCDI")),
                "the page must post the stored authority");
        mockMvc.perform(buildPost(untouched)).andExpect(status().is3xxRedirection());
        assertEquals(ro.uvt.pokedex.core.model.reporting.ReportAuthority.UEFISCDI, saved().getAuthority());
    }

    @Test
    void aReportThatNamesNoAuthorityStaysSoAndCountsAsCnatdcu() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);

        mockMvc.perform(buildPost(renderedForm())).andExpect(status().is3xxRedirection());

        IndividualReport saved = saved();
        assertEquals(null, saved.getAuthority());
        assertEquals(ro.uvt.pokedex.core.model.reporting.ReportAuthority.CNATDCU, saved.effectiveAuthority());
    }

    @Test
    void thePageSendsNothingForTheScriptedFields() throws Exception {
        // The premise of every other test here: if the page ever grows inputs for these, the carry-over
        // below stops being the thing that protects them and this suite must be revisited.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        for (String[] field : renderedForm()) {
            assertTrue(!field[0].startsWith("perspectives") && !field[0].contains(".weights")
                            && !field[0].contains(".maxPercentOfTotal") && !field[0].contains(".thresholdCapAdditions"),
                    "the form now posts " + field[0]);
        }
    }

    // ------------------------------------------------------------------ edits the form is there for

    @Test
    void renamingACriterionAndChangingAThresholdKeepsTheScriptedFields() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        set(payload, "criteria[2].name", "T — punctaj total CNATDCU");
        set(payload, "criteria[0].thresholds[0].value", "3");
        set(payload, "reportTypeKey", "fizica-2026");

        submit(payload);

        IndividualReport saved = saved();
        assertScriptedFieldsIntact(saved);
        assertEquals("T — punctaj total CNATDCU", saved.getCriteria().get(2).getName());
        assertEquals(3.0, saved.getCriteria().get(0).getThresholds().get(0).getValue());
        assertEquals("fizica-2026", saved.getReportTypeKey());
    }

    @Test
    void changingWhichIndicatorsACriterionSumsKeepsTheScriptedFields() throws Exception {
        // Weights are keyed by the report-level indicator index, so dropping an indicator from one criterion
        // leaves every other key meaning what it meant.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        remove(payload, "criteria[2].indicatorIndices[2]");

        submit(payload);

        IndividualReport saved = saved();
        assertScriptedFieldsIntact(saved);
        assertEquals(List.of(0, 1), saved.getCriteria().get(2).getIndicatorIndices());
    }

    @Test
    void addingAnIndicatorAndACriterionAtTheEndKeepsTheScriptedFields() throws Exception {
        // Everything that existed keeps its position, so every stored index still points where it did.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        payload.add(new String[]{"indicators[3]", "i-extra"});
        payload.add(new String[]{"criteria[3].name", "Criteriu nou"});
        payload.add(new String[]{"criteria[3].indicatorIndices[0]", "3"});
        payload.add(new String[]{"criteria[3].thresholds[0].position", "CONF_UNIV"});
        payload.add(new String[]{"criteria[3].thresholds[0].value", "1"});

        submit(payload);

        IndividualReport saved = saved();
        assertScriptedFieldsIntact(saved);
        assertEquals(4, saved.getIndicators().size());
        assertEquals(4, saved.getCriteria().size());
        assertNull(saved.getCriteria().get(3).getWeights());
    }

    // ------------------------------------------------------------------ edits that would leave stale indices

    private void assertRefused(List<String[]> payload, String... messageParts) throws Exception {
        MvcResult result = mockMvc.perform(buildPost(payload))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/individualReports/edit/" + REPORT_ID))
                .andExpect(flash().attributeExists("errorMessage"))
                .andReturn();
        String message = String.valueOf(result.getFlashMap().get("errorMessage"));
        for (String part : messageParts) {
            assertTrue(message.contains(part), "the message does not mention '" + part + "': " + message);
        }
        verify(individualReportRepository, never()).save(any(IndividualReport.class));
    }

    /**
     * What the page's own script does when "Remove Criterion" is pressed: drop the card with everything in
     * it, then renumber the cards after it — their inputs and the hidden checkbox markers Thymeleaf renders
     * next to each checkbox ({@code _criteria[n].contributesToTotal}).
     */
    private static void removeCriterionAsThePageDoes(List<String[]> payload, int index, int count) {
        removeCriterionAsThePageUsedTo(payload, index, count);
        for (int i = index + 1; i < count; i++) {
            for (String[] field : payload) {
                if (field[0].startsWith("_criteria[" + i + "].")) {
                    field[0] = "_criteria[" + (i - 1) + "]." + field[0].substring(("_criteria[" + i + "].").length());
                }
            }
        }
    }

    /**
     * The page before H116's follow-up: the markers of the following cards kept their old number, and the
     * binder, which grows a list up to the highest index it is told about, added an empty criterion for the
     * last of them. A browser tab opened before the deploy still posts this.
     */
    private static void removeCriterionAsThePageUsedTo(List<String[]> payload, int index, int count) {
        remove(payload, "criteria[" + index + "].");
        remove(payload, "_criteria[" + index + "].");
        for (int i = index + 1; i < count; i++) {
            for (String[] field : payload) {
                if (field[0].startsWith("criteria[" + i + "].")) {
                    field[0] = "criteria[" + (i - 1) + "]." + field[0].substring(("criteria[" + i + "].").length());
                }
            }
        }
    }

    @Test
    void removingACriterionIsRefusedBecausePerspectivesPointAtPositions() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        removeCriterionAsThePageDoes(payload, 1, 3);

        assertRefused(payload, "perspectives", "Nothing was saved");
    }

    @Test
    void replacingACriterionIsRefusedEvenThoughTheCountIsUnchanged() throws Exception {
        // Remove the middle criterion, add a new one at the end: still three criteria, but position 1 now
        // holds what used to be position 2 — its weights must not slide onto it.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        removeCriterionAsThePageDoes(payload, 1, 3);
        payload.add(new String[]{"criteria[2].name", "Criteriu nou"});
        payload.add(new String[]{"criteria[2].indicatorIndices[0]", "0"});

        assertRefused(payload, "criterion 2");
    }

    @Test
    void removingAnIndicatorIsRefusedBecauseWeightsAreKeyedByItsPosition() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        // The page closes the gap: i-cit leaves, i-hirsch moves from position 2 to position 1.
        set(payload, "indicators[1]", "i-hirsch");
        remove(payload, "indicators[2]");

        assertRefused(payload, "indicator", "Nothing was saved");
    }

    @Test
    void aRefusedSaveShowsItsReasonOnTheEditPage() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        String html = mockMvc.perform(get("/admin/individualReports/edit/" + REPORT_ID)
                        .flashAttr("errorMessage", "The save was refused for a reason."))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(Jsoup.parse(html).select("[role=alert]").text().contains("The save was refused for a reason."));
    }

    // ------------------------------------------------------------------ where nothing is at stake

    private static IndividualReport plainReport() {
        IndividualReport report = scriptedReport();
        report.setPerspectives(null);
        report.getCriteria().forEach(criterion -> {
            criterion.setWeights(null);
            criterion.setMaxPercentOfTotal(null);
            criterion.setThresholdCapAdditions(null);
        });
        return report;
    }

    @Test
    void aReportWithoutScriptedFieldsCanChangeShapeFreely() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        removeCriterionAsThePageDoes(payload, 1, 3);
        set(payload, "indicators[1]", "i-hirsch");
        remove(payload, "indicators[2]");

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        IndividualReport saved = saved();
        assertEquals(List.of("I — articole", "T — punctaj total"),
                saved.getCriteria().stream().map(Criterion::getName).toList());
        assertEquals(2, saved.getIndicators().size());
        assertNull(saved.getPerspectives());
    }

    // ------------------------------------------------------------------ gaps in what is posted

    private static List<String> criterionNames(IndividualReport report) {
        return report.getCriteria().stream().map(Criterion::getName).toList();
    }

    @Test
    void removingACriterionLeavesNoEmptyOneBehind() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        removeCriterionAsThePageDoes(payload, 1, 3);

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        assertEquals(List.of("I — articole", "T — punctaj total"), criterionNames(saved()));
    }

    @Test
    void aPageOpenedBeforeTheFixStillSavesWithoutAPhantomCriterion() throws Exception {
        // The server-side net: whatever numbering the markers arrive with, a criterion that carries
        // nothing at all at the end of the list is not a criterion anybody entered.
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        removeCriterionAsThePageUsedTo(payload, 0, 3);

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        assertEquals(List.of("C — citări și Hirsch", "T — punctaj total"), criterionNames(saved()));
    }

    @Test
    void severalStrayMarkersAreAllDropped() throws Exception {
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        payload.add(new String[]{"_criteria[5].contributesToTotal", "on"});

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        assertEquals(3, saved().getCriteria().size());
    }

    @Test
    void aCriterionSomebodyStartedIsKeptEvenWhenItIsLast() throws Exception {
        // "Add Criterion" followed by a save: no name yet, but it sums an indicator and has a threshold.
        // And a criterion that only has a name, or only the plafon, or only the total flag, is kept too.
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        payload.add(new String[]{"criteria[3].name", ""});
        payload.add(new String[]{"criteria[3].indicatorIndices[0]", "0"});
        payload.add(new String[]{"criteria[3].thresholds[0].position", "CONF_UNIV"});
        payload.add(new String[]{"criteria[3].thresholds[0].value", "0.0"});
        payload.add(new String[]{"criteria[4].name", "Doar nume"});
        payload.add(new String[]{"criteria[5].maxTotal", "50"});
        payload.add(new String[]{"criteria[6].contributesToTotal", "true"});

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        IndividualReport saved = saved();
        assertEquals(7, saved.getCriteria().size());
        assertEquals(List.of(0), saved.getCriteria().get(3).getIndicatorIndices());
        assertEquals("Doar nume", saved.getCriteria().get(4).getName());
        assertEquals(50.0, saved.getCriteria().get(5).getMaxTotal());
        assertTrue(saved.getCriteria().get(6).isContributesToTotal());
    }

    @Test
    void anEmptyCriterionInTheMiddleIsLeftAlone() throws Exception {
        // Dropping it would move every criterion after it up by one, and positions are what perspectives
        // and threshold-cap additions point at. Only the end of the list is safe to trim.
        stored(AdminIndividualReportFormRoundTripTest::plainReport);
        List<String[]> payload = renderedForm();
        remove(payload, "criteria[1].");

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        IndividualReport saved = saved();
        assertEquals(3, saved.getCriteria().size());
        assertEquals("T — punctaj total", saved.getCriteria().get(2).getName());
    }

    @Test
    void aGapInTheRowsOfACriterionLeavesNoEmptyRowBehind() throws Exception {
        // Removing the middle indicator row or threshold row used to leave the rows after it with their old
        // numbers; the binder fills such a gap with a null index and an empty threshold.
        stored(() -> {
            IndividualReport copy = plainReport();
            copy.getCriteria().get(2).getThresholds().add(1, threshold(Position.PROF_UNIV, 12.5));
            return copy;
        });
        List<String[]> payload = renderedForm();
        remove(payload, "criteria[2].indicatorIndices[1]");
        remove(payload, "criteria[2].thresholds[1].");

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        Criterion total = saved().getCriteria().get(2);
        assertEquals(List.of(0, 2), total.getIndicatorIndices());
        assertEquals(List.of(threshold(Position.CONF_UNIV, 5), threshold(Position.HABIL, 11.5)), total.getThresholds());
    }

    @Test
    void aScriptedReportSavedFromAStalePageKeepsItsFieldsAndGainsNoCriterion() throws Exception {
        // Untouched save, but with a marker beyond the last criterion: the net trims it before the
        // carry-over looks at positions, so this is an ordinary save.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        payload.add(new String[]{"_criteria[3].contributesToTotal", "on"});

        mockMvc.perform(buildPost(payload)).andExpect(redirectedUrl("/admin/individualReports"));

        IndividualReport saved = saved();
        assertScriptedFieldsIntact(saved);
        assertEquals(3, saved.getCriteria().size());
    }

    @Test
    void aReportThatIsNotStoredYetIsSavedAsPosted() throws Exception {
        when(individualReportRepository.findById("rep-new")).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/individualReports/update")
                        .param("id", "rep-new").param("title", "T").param("description", "D")
                        .param("criteria[0].name", "C"))
                .andExpect(redirectedUrl("/admin/individualReports"));

        assertEquals("C", saved().getCriteria().getFirst().getName());
    }

    // ------------------------------------------------------------------ requests that do carry the fields

    @Test
    void aRequestThatSendsPerspectivesReplacesTheStoredOnes() throws Exception {
        // How FV Psihologie 2026 was built: the binder accepts perspectives[…] although the page has no
        // inputs for them. What the request states wins; what it leaves out is still carried over.
        stored(AdminIndividualReportFormRoundTripTest::scriptedReport);
        List<String[]> payload = renderedForm();
        payload.add(new String[]{"perspectives[0].name", "Una singură"});
        payload.add(new String[]{"perspectives[0].composition.all[0].criterion", "2"});
        payload.add(new String[]{"criteria[1].weights[1]", "0.75"});

        submit(payload);

        IndividualReport saved = saved();
        assertEquals(1, saved.getPerspectives().size());
        assertEquals("Una singură", saved.getPerspectives().getFirst().getName());
        assertEquals(2, saved.getPerspectives().getFirst().getComposition().getAll().getFirst().getCriterion());
        assertEquals(Map.of(1, 0.75), saved.getCriteria().get(1).getWeights());
        // Left out of the request, so kept from the stored report.
        IndividualReport original = scriptedReport();
        assertEquals(original.getCriteria().get(1).getMaxPercentOfTotal(),
                saved.getCriteria().get(1).getMaxPercentOfTotal());
        assertEquals(original.getCriteria().get(2).getWeights(), saved.getCriteria().get(2).getWeights());
        assertEquals(original.getCriteria().get(2).getThresholdCapAdditions(),
                saved.getCriteria().get(2).getThresholdCapAdditions());
    }
}
