package ro.uvt.pokedex.core.service.application;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class IndexMaintenanceSupport {

    private IndexMaintenanceSupport() {}

    public static void ensureNamedIndex(
            IndexOperations ops,
            IndexDefinition definition,
            List<String> created,
            List<String> present,
            List<String> invalid,
            List<String> errors
    ) {
        try {
            List<IndexInfo> indexInfo = ops.getIndexInfo();
            IndexInfo exact = indexInfo.stream().filter(info -> definition.matchesByNameAndShape(info)).findFirst().orElse(null);
            if (exact != null) {
                present.add(definition.name());
                return;
            }

            IndexInfo sameShapeDifferentName = indexInfo.stream().filter(definition::matchesByShape).findFirst().orElse(null);
            if (sameShapeDifferentName != null) {
                invalid.add(definition.name() + " (existing=" + sameShapeDifferentName.getName() + ")");
                return;
            }

            Index index = new Index().named(definition.name());
            for (IndexField field : definition.fields()) {
                index.on(field.getKey(), Sort.Direction.ASC);
            }
            if (definition.unique()) {
                index.unique();
            }
            if (definition.sparse()) {
                index.sparse();
            }
            ops.createIndex(index);
            created.add(definition.name());
        } catch (Exception e) {
            errors.add(definition.name() + ": " + e.getMessage());
        }
    }

    public static IndexField field(String key) {
        return IndexField.create(key, Sort.Direction.ASC);
    }

    public record IndexDefinition(String name, boolean unique, boolean sparse, List<IndexField> fields) {
        /** Convenience constructor — sparse defaults to false (used by WoS and most Scopus indexes). */
        public IndexDefinition(String name, boolean unique, List<IndexField> fields) {
            this(name, unique, false, fields);
        }

        boolean matchesByNameAndShape(IndexInfo info) {
            if (info == null) return false;
            return Objects.equals(name, info.getName()) && matchesByShape(info);
        }

        boolean matchesByShape(IndexInfo info) {
            if (info == null) return false;
            if (unique != info.isUnique()) return false;
            if (sparse != info.isSparse()) return false;
            if (info.getIndexFields().size() != fields.size()) return false;
            String expected = fields.stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            String actual = info.getIndexFields().stream().map(IndexField::getKey).collect(Collectors.joining("|"));
            return Objects.equals(expected, actual);
        }
    }
}
