package ro.uvt.pokedex.core.service.reporting.transfer.projection;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryLetterMapperTest {

    @Test
    void mapsAStarRepresentationsToAA() {
        assertThat(CategoryLetterMapper.toTemplateLetter("A_STAR")).isEqualTo("AA");
        assertThat(CategoryLetterMapper.toTemplateLetter("A*")).isEqualTo("AA");
    }

    @Test
    void passesOtherLettersThrough() {
        assertThat(CategoryLetterMapper.toTemplateLetter("A")).isEqualTo("A");
        assertThat(CategoryLetterMapper.toTemplateLetter("B")).isEqualTo("B");
        assertThat(CategoryLetterMapper.toTemplateLetter(null)).isNull();
    }

    @Test
    void universalMapperPassesNonRankThrough() {
        assertThat(CategoryLetterMapper.toTemplateLetter("NON_RANK")).isEqualTo("NON_RANK");
    }

    @Test
    void publicationMapperMapsNonRankToD() {
        assertThat(CategoryLetterMapper.toPublicationTemplateLetter("NON_RANK")).isEqualTo("D");
        assertThat(CategoryLetterMapper.toPublicationTemplateLetter("A_STAR")).isEqualTo("AA");
        assertThat(CategoryLetterMapper.toPublicationTemplateLetter("B")).isEqualTo("B");
        assertThat(CategoryLetterMapper.toPublicationTemplateLetter(null)).isNull();
    }
}
