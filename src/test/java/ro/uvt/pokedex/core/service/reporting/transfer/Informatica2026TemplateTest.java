package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.ActivitySnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.BindingPolicy;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H81 Slices 3–4: the informatica-2026 binding + template. Verifies the binding loads with the same
 * export roles as 2016 plus the director-count INDICATOR_TOTAL scalar cell, and that a rendered export
 * stamps the perspective-d "Număr proiecte ca director" cell from the run total while preserving the
 * new A*+A and director-project Centralizator criteria formulas.
 */
class Informatica2026TemplateTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2026/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @Test
    void bindingKeepsRolesAndAddsDirectorCountScalarCell() {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        assertThat(binding.getReportTypeKey()).isEqualTo("informatica-2026");
        assertThat(binding.getTemplateResource())
                .isEqualTo("classpath:report-templates/informatica-2026/template.xlsx");
        assertThat(binding.getRoles()).extracting(r -> r.getRoleKey())
                .containsExactly("journal-publications", "conference-publications",
                        "citations-per-publication", "activities-perspectiva-d");
        assertThat(binding.getScalarCells())
                .anySatisfy(sc -> {
                    assertThat(sc.getPolicy()).isEqualTo(BindingPolicy.INDICATOR_TOTAL);
                    assertThat(sc.getCell()).isEqualTo("'D-Perspectiva D'!K24");
                    assertThat(sc.getSource()).isEqualTo("perspectiva-d-director-count");
                });
    }

    @Test
    void exportStampsDirectorCountAndKeepsCriteriaFormulas() throws Exception {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);

        ActivitySnapshotItem grant = new ActivitySnapshotItem();
        grant.setActivityName("Granturi");
        grant.setDescription("Director proiect PN-III (225.000 EUR)");
        grant.setCategory("Director");
        grant.setScore(4.0);

        byte[] bytes = renderer.render(binding,
                Map.of("activities-perspectiva-d", List.of(grant.toRowMap())),
                Map.of(),
                Map.of("perspectiva-d-director-count", 1.0));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet d = out.getSheet("D-Perspectiva D");
            assertThat(d.getRow(23).getCell(10).getNumericCellValue()).isEqualTo(1.0); // K24

            Sheet cz = out.getSheet("Centralizator");
            // A*+A criterion (row 23) + director-project criterion (row 24) survive the render.
            assertThat(cz.getRow(22).getCell(3).getCellFormula())   // D23
                    .isEqualTo("'B-Reviste'!J17+'B-Reviste'!J18+'B-Conferinte'!K19+'B-Conferinte'!K20");
            assertThat(cz.getRow(23).getCell(3).getCellFormula())   // D24
                    .isEqualTo("'D-Perspectiva D'!K24");
            // Hirsch MANUAL scalar cells (binding-referenced) must remain untouched.
            assertThat(cz.getRow(24).getCell(3).getNumericCellValue()).isEqualTo(0.0); // D25

            // Perspectiva B corrected to 2026 "Publicații de top" per-rank gates.
            assertThat(cz.getRow(7).getCell(4).getCellFormula())    // E8 lector: points only, no top-pub gate
                    .isEqualTo("IF(D7>=12,\"DA\",\"NU\")");
            assertThat(cz.getRow(8).getCell(4).getCellFormula())    // E9 conf: A*+A+B >= 16
                    .contains("J22+'B-Conferinte'!K24>=16");
            String e10 = cz.getRow(9).getCell(4).getCellFormula();  // E10 prof: A*+A+B >= 40 AND A*+A >= 24
            assertThat(e10).contains("J22+'B-Conferinte'!K24>=40");
            assertThat(e10).contains("'B-Reviste'!J17+'B-Reviste'!J18+'B-Conferinte'!K19+'B-Conferinte'!K20>=24");

            // Abilitare block (rows 35-39): per-perspective thresholds + combined verdict.
            assertThat(cz.getRow(34).getCell(1).getStringCellValue()).isEqualTo("Abilitare");   // B35
            assertThat(cz.getRow(35).getCell(3).getCellFormula()).isEqualTo("D7");              // D36 → Perspectiva B points
            assertThat(cz.getRow(35).getCell(4).getCellFormula())                               // E36
                    .contains(">=44").contains(">=28").contains(">=12");
            assertThat(cz.getRow(36).getCell(4).getCellFormula()).contains(">=84").contains(">=26"); // E37
            assertThat(cz.getRow(37).getCell(4).getCellFormula()).contains(">=48");             // E38 Perspectiva D
            assertThat(cz.getRow(38).getCell(1).getStringCellValue()).isEqualTo("TOTAL abilitare"); // B39
            assertThat(cz.getRow(38).getCell(4).getCellFormula())                               // E39
                    .isEqualTo("IF(AND(E36=\"DA\",E37=\"DA\",E38=\"DA\"),\"DA\",\"NU\")");
        }
    }
}
