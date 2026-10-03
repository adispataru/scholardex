package ro.uvt.pokedex.core.service.application;

import java.util.List;

/** H145 — a declared activity holds values its type does not accept ("Rol: «x»"); nothing was stored. */
public class ActivityValidationException extends RuntimeException {

    private final List<String> problems;

    public ActivityValidationException(List<String> problems) {
        super("activity values refused: " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<String> getProblems() {
        return problems;
    }
}
