package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.time.Year;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestYearRangeSupportTest {

    @Test
    void privateConstructorIsCovered() throws Exception {
        Constructor<RequestYearRangeSupport> ctor = RequestYearRangeSupport.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ctor.newInstance();
    }

    @Test
    void parseAndValidateReturnsRangeWhenValid() {
        RequestYearRangeSupport.YearRange range = RequestYearRangeSupport.parseAndValidate("2001", "2005");
        assertEquals(2001, range.start());
        assertEquals(2005, range.end());
    }

    @Test
    void parseAndValidateAcceptsMinAndCurrentYearBoundaries() {
        int currentYear = Year.now().getValue();
        RequestYearRangeSupport.YearRange range = RequestYearRangeSupport.parseAndValidate("1900", Integer.toString(currentYear));
        assertEquals(1900, range.start());
        assertEquals(currentYear, range.end());
    }

    @Test
    void parseAndValidateAcceptsEqualStartAndEndAtBoundaries() {
        int currentYear = Year.now().getValue();
        RequestYearRangeSupport.YearRange minRange = RequestYearRangeSupport.parseAndValidate("1900", "1900");
        RequestYearRangeSupport.YearRange currentRange = RequestYearRangeSupport.parseAndValidate(
                Integer.toString(currentYear),
                Integer.toString(currentYear)
        );
        assertEquals(1900, minRange.start());
        assertEquals(1900, minRange.end());
        assertEquals(currentYear, currentRange.start());
        assertEquals(currentYear, currentRange.end());
    }

    @Test
    void parseAndValidateRejectsNonNumericInput() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> RequestYearRangeSupport.parseAndValidate("19x0", "2020")
        );
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("must be integers");
    }

    @Test
    void parseAndValidateRejectsOutOfRangeYears() {
        int currentYear = Year.now().getValue();
        assertThrows(IllegalArgumentException.class, () -> RequestYearRangeSupport.parseAndValidate("1899", "1900"));
        assertThrows(IllegalArgumentException.class, () -> RequestYearRangeSupport.parseAndValidate("1900", "1899"));
        assertThrows(IllegalArgumentException.class, () -> RequestYearRangeSupport.parseAndValidate("1900", Integer.toString(currentYear + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> RequestYearRangeSupport.parseAndValidate(Integer.toString(currentYear + 1), Integer.toString(currentYear + 1)));
    }

    @Test
    void parseAndValidateRejectsStartAfterEnd() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> RequestYearRangeSupport.parseAndValidate("2022", "2021")
        );
        org.assertj.core.api.Assertions.assertThat(ex.getMessage()).contains("Start year must be less than or equal to end year");
    }
}
