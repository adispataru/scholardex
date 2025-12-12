package ro.uvt.pokedex.core.service.reporting;

@FunctionalInterface
public interface Func4Arity <A, B, C, D, R> {
    R apply(A a, B b, C c, D d);
}
