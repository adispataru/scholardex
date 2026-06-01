package ro.uvt.pokedex.core.service.reporting.formula;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;
import ro.uvt.pokedex.core.model.reporting.Indicator;

/**
 * H52 slice 5: stamps {@link Indicator#setFormulaHash(String)} just before the document
 * gets converted to its Mongo representation. Fires on both inserts and updates
 * (every {@code save(...)} call), so the hash is always consistent with the formula
 * actually persisted.
 *
 * <p>Hooked on {@code onBeforeConvert} rather than {@code onBeforeSave}: by the latter
 * the {@link org.bson.Document} is already built, so mutating the entity has no effect.
 * The convert step still sees the live entity.</p>
 *
 * <p>The listener is intentionally idempotent and side-effect-light. A null/blank
 * formula leaves {@code formulaHash} untouched — no validation here. Indicator-validation
 * lives at the controller / service layer; this listener is just the identity stamp.</p>
 */
@Component
@Slf4j
public class IndicatorFormulaHashStamper extends AbstractMongoEventListener<Indicator> {

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Indicator> event) {
        Indicator indicator = event.getSource();
        String formula = indicator.getFormula();
        if (formula == null || formula.isBlank()) {
            // Don't touch the hash; let the document round-trip with whatever was there
            // (probably null on a fresh insert). The migration runner can handle empties.
            return;
        }
        try {
            // H52 slice 7: sandbox the formula at save time. Same denylist used by the
            // evaluator at runtime; here we want loud rejection during the admin edit
            // rather than silent persistence of a doc that would fail at every report
            // run. FormulaSandboxException is an IllegalArgumentException, so Spring
            // surfaces it as a 400 to the admin controller (preferable to the silent
            // log we used for hashing errors).
            String canonical = FormulaCanonicalizer.canonicalize(formula);
            FormulaSandbox.assertSafe(canonical);

            String newHash = FormulaHasher.hashCanonical(canonical);
            String existing = indicator.getFormulaHash();
            if (!newHash.equals(existing)) {
                indicator.setFormulaHash(newHash);
                if (log.isDebugEnabled()) {
                    log.debug("Stamped formulaHash on indicator id={} name='{}' hash={}",
                            indicator.getId(), indicator.getName(), newHash);
                }
            }
        } catch (FormulaSandboxException ex) {
            // Denylist hit — let it propagate so the save fails loudly. The admin sees
            // the rejection message in the controller's error handling.
            log.warn("Rejecting formula on indicator id={} name='{}': {}",
                    indicator.getId(), indicator.getName(), ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            // Hashing should never throw on a non-blank formula, but a defensive log
            // keeps a corrupted save from being silently mis-keyed.
            log.warn("Failed to stamp formulaHash on indicator id={} name='{}'; persisting without it. error={}",
                    indicator.getId(), indicator.getName(), ex.toString());
        }
    }
}
