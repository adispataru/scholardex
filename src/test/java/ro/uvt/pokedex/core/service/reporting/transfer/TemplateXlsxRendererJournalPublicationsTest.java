package ro.uvt.pokedex.core.service.reporting.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import ro.uvt.pokedex.core.model.reporting.transfer.PublicationSnapshotItem;
import ro.uvt.pokedex.core.model.reporting.transfer.binding.TemplateBinding;
import ro.uvt.pokedex.core.service.reporting.transfer.binding.TemplateBindingLoader;
import ro.uvt.pokedex.core.service.reporting.transfer.render.TemplateXlsxRenderer;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateXlsxRendererJournalPublicationsTest {

    private static final String BINDING_RESOURCE = "report-templates/informatica-2016/binding.json";

    private final TemplateBindingLoader loader = new TemplateBindingLoader(new ObjectMapper());
    private final TemplateXlsxRenderer renderer = new TemplateXlsxRenderer();

    @Test
    void rendersJournalPublicationsIntoFixedTableAndPreservesFormulas() throws Exception {
        TemplateBinding binding = loader.load(BINDING_RESOURCE);
        assertThat(binding.getReportTypeKey()).isEqualTo("informatica-2016");
        assertThat(binding.getRoles()).extracting("roleKey")
                .contains("journal-publications", "conference-publications");

        PublicationSnapshotItem pub1 = publication("Self-supervised graph learning",
                "Popescu, A.; Ionescu, R.", "Journal of Machine Learning Research",
                "Vol. 24, pp. 1-30", 2023, "A", 2);
        PublicationSnapshotItem pub2 = publication("Sparse attention is all you need",
                "Ionescu, R.; Popa, M.", "Neural Computation",
                "Vol. 35(2), pp. 200-240", 2024, "AA", 3);

        byte[] bytes = renderer.render(binding, Map.of(
                "journal-publications", List.of(pub1.toRowMap(), pub2.toRowMap())
        ));

        try (Workbook out = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = out.getSheet("B-Reviste");
            assertThat(sheet).isNotNull();

            // Row 8 (index 7) should be pub1.
            Row row8 = sheet.getRow(7);
            assertThat(row8.getCell(2).getStringCellValue()).isEqualTo("Self-supervised graph learning"); // C
            assertThat(row8.getCell(3).getStringCellValue()).isEqualTo("Popescu, A.; Ionescu, R.");      // D
            assertThat(row8.getCell(4).getStringCellValue()).isEqualTo("Journal of Machine Learning Research"); // E
            assertThat(row8.getCell(5).getStringCellValue()).isEqualTo("Vol. 24, pp. 1-30");           // F
            assertThat(row8.getCell(6).getNumericCellValue()).isEqualTo(2023.0);                        // G
            assertThat(row8.getCell(7).getStringCellValue()).isEqualTo("A");                            // H
            assertThat(row8.getCell(8).getNumericCellValue()).isEqualTo(2.0);                           // I

            // Column J on row 8 must still be a formula (Punctaj P).
            Cell j8 = row8.getCell(9);
            assertThat(j8.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(j8.getCellFormula()).contains("H8");

            // K / L / M policies: MANUAL / MANUAL / SKIP_FORMULA — none should have been overwritten.
            // M is a formula in the template (=L8), K and L are blank/manual.
            Cell m8 = row8.getCell(12);
            assertThat(m8.getCellType()).isEqualTo(CellType.FORMULA);

            // Row 9 (index 8) should be pub2.
            Row row9 = sheet.getRow(8);
            assertThat(row9.getCell(2).getStringCellValue()).isEqualTo("Sparse attention is all you need");
            assertThat(row9.getCell(7).getStringCellValue()).isEqualTo("AA");
            assertThat(row9.getCell(8).getNumericCellValue()).isEqualTo(3.0);

            // Row 10 (index 9) was not provided — its writable cells are cleared (no leftover
            // "Titlu articol" placeholder), so the score parser stops at this blank row.
            Row row10 = sheet.getRow(9);
            assertThat(row10.getCell(2)).matches(c -> c == null || c.getCellType() == CellType.BLANK
                    || c.getStringCellValue().isEmpty());

            // Aggregation formula at J23 (index 22, column 9) must remain a formula.
            Row row23 = sheet.getRow(22);
            Cell j23 = row23.getCell(9);
            assertThat(j23.getCellType()).isEqualTo(CellType.FORMULA);
        }
    }

    private PublicationSnapshotItem publication(String title, String authors, String forum,
                                                String volume, int year, String category, int authorCount) {
        PublicationSnapshotItem p = new PublicationSnapshotItem();
        p.setItemKey(title);
        p.setTitle(title);
        p.setAuthors(authors);
        p.setForumName(forum);
        p.setVolumeInfo(volume);
        p.setYear(year);
        p.setForumCategoryLetter(category);
        p.setAuthorCount(authorCount);
        return p;
    }
}
