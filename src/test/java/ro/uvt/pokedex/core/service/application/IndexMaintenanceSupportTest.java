package ro.uvt.pokedex.core.service.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexMaintenanceSupportTest {

    @Test
    void ensureNamedIndexMarksPresentWhenExactMatchExists() {
        IndexOperations ops = mock(IndexOperations.class);
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        IndexMaintenanceSupport.IndexDefinition definition =
                new IndexMaintenanceSupport.IndexDefinition("idx_a", true, List.of(IndexMaintenanceSupport.field("a")));

        when(ops.getIndexInfo()).thenReturn(List.of(info("idx_a", true, false, "a")));
        IndexMaintenanceSupport.ensureNamedIndex(ops, definition, created, present, invalid, errors);

        assertEquals(List.of("idx_a"), present);
        assertTrue(created.isEmpty());
        assertTrue(invalid.isEmpty());
        verify(ops, never()).createIndex(any());
    }

    @Test
    void ensureNamedIndexMarksInvalidWhenSameShapeWithDifferentNameExists() {
        IndexOperations ops = mock(IndexOperations.class);
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        IndexMaintenanceSupport.IndexDefinition definition =
                new IndexMaintenanceSupport.IndexDefinition("idx_a", true, List.of(IndexMaintenanceSupport.field("a")));

        when(ops.getIndexInfo()).thenReturn(List.of(info("idx_other", true, false, "a")));
        IndexMaintenanceSupport.ensureNamedIndex(ops, definition, created, present, invalid, errors);

        assertTrue(present.isEmpty());
        assertTrue(created.isEmpty());
        assertEquals(1, invalid.size());
        verify(ops, never()).createIndex(any());
    }

    @Test
    void ensureNamedIndexCreatesMissingIndex() {
        IndexOperations ops = mock(IndexOperations.class);
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        IndexMaintenanceSupport.IndexDefinition definition =
                new IndexMaintenanceSupport.IndexDefinition(
                        "idx_ab", true, true,
                        List.of(IndexMaintenanceSupport.field("a"), IndexMaintenanceSupport.field("b"))
                );

        when(ops.getIndexInfo()).thenReturn(List.of());
        IndexMaintenanceSupport.ensureNamedIndex(ops, definition, created, present, invalid, errors);

        assertEquals(List.of("idx_ab"), created);
        assertTrue(present.isEmpty());
        assertTrue(invalid.isEmpty());
        verify(ops).createIndex(any());
    }

    @Test
    void ensureNamedIndexCollectsErrors() {
        IndexOperations ops = mock(IndexOperations.class);
        List<String> created = new ArrayList<>();
        List<String> present = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        IndexMaintenanceSupport.IndexDefinition definition =
                new IndexMaintenanceSupport.IndexDefinition("idx_a", false, List.of(IndexMaintenanceSupport.field("a")));

        when(ops.getIndexInfo()).thenThrow(new IllegalStateException("db down"));
        IndexMaintenanceSupport.ensureNamedIndex(ops, definition, created, present, invalid, errors);

        assertTrue(created.isEmpty());
        assertTrue(present.isEmpty());
        assertTrue(invalid.isEmpty());
        assertEquals(1, errors.size());
        assertTrue(errors.getFirst().contains("idx_a"));
    }

    private static IndexInfo info(String name, boolean unique, boolean sparse, String... keys) {
        return new IndexInfo(
                java.util.Arrays.stream(keys).map(k -> IndexField.create(k, Sort.Direction.ASC)).toList(),
                name,
                unique,
                sparse,
                null
        );
    }
}

