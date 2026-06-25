package ro.uvt.pokedex.core.service.reporting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** H60: the thread-scoped referenceYear holder — set/restore, nesting, and no leak across runs. */
class ScoringReferenceYearContextTest {

    @Test
    void exposesValueInScopeAndRestoresAfter() {
        assertNull(ScoringReferenceYearContext.current());
        Integer inside = ScoringReferenceYearContext.with(2023, ScoringReferenceYearContext::current);
        assertEquals(2023, inside);
        assertNull(ScoringReferenceYearContext.current(), "must not leak after the scope closes");
    }

    @Test
    void nestsAndRestoresThePreviousValue() {
        ScoringReferenceYearContext.with(2023, () -> {
            assertEquals(2023, ScoringReferenceYearContext.current());
            ScoringReferenceYearContext.with(2021, () -> {
                assertEquals(2021, ScoringReferenceYearContext.current());
                return null;
            });
            assertEquals(2023, ScoringReferenceYearContext.current(), "inner scope must restore the outer value");
            return null;
        });
        assertNull(ScoringReferenceYearContext.current());
    }

    @Test
    void nullReferenceYearLeavesNoValueInScope() {
        Integer inside = ScoringReferenceYearContext.with(null, ScoringReferenceYearContext::current);
        assertNull(inside);
    }

    @Test
    void restoresEvenWhenBodyThrows() {
        assertThrows(IllegalStateException.class, () ->
                ScoringReferenceYearContext.with(2023, () -> {
                    throw new IllegalStateException("boom");
                }));
        assertNull(ScoringReferenceYearContext.current(), "finally must clear the value even on exception");
    }
}
