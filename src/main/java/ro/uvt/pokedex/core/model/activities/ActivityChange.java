package ro.uvt.pokedex.core.model.activities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * H142 slice 7 — one change to an activity record after it was made: {@code EDITED} (the values that changed, in
 * {@code note}) or {@code MOVED} (to another type: {@code from} and {@code to} name the types, {@code note} the values
 * the new type has no field for).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityChange {
    private Instant at;
    private String by;
    private String action;
    private String from;
    private String to;
    private String note;
}
