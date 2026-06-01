package ro.uvt.pokedex.core.service.reporting.formula;

import java.util.ArrayList;
import java.util.List;

/**
 * H52 slice 8b: a small tokenizer over the MVEL subset used by indicator formulas.
 * Exists to give {@link FormulaCanonicalizer} a real basis for normalizing spacing,
 * fixing the slice-5 gap where {@code "S+1"} and {@code "S + 1"} hashed differently.
 *
 * <p><strong>Scope.</strong> The tokenizer recognizes the MVEL constructs that appear
 * in production formulas: identifiers (with dot chains for qualified names like
 * {@code Math.max}), integer and decimal numeric literals, single- and double-quoted
 * string literals, the operator set {@code + - * / % > < >= <= == != && || ! ? : =},
 * grouping {@code ( )}, and the separators {@code , ;}. It is <em>not</em> a complete
 * MVEL parser — block comments, MVEL inline imports, bit-shift operators, and array
 * literals are out of scope (none appear in any production indicator). Anything the
 * tokenizer doesn't recognize is emitted as a single-character {@link Type#OP} token
 * so the canonical form is still produced (no exception thrown), but the {@link FormulaSandbox}
 * denylist still catches the dangerous identifier patterns at the same level it always did.</p>
 *
 * <p><strong>Why not the MVEL parser AST.</strong> MVEL's compiled-expression tree is
 * package-private and walking it would couple us to MVEL internals across upgrades.
 * A 100-line hand tokenizer covers the realistic input space and is easy to evolve.</p>
 */
public final class FormulaTokenizer {

    public enum Type {
        IDENT,       // foo, Math.max, B
        NUMBER,      // 1, 1.0, 1e5
        STRING,      // 'Membru', "x"
        OP,          // + - * / % > < ! ? : = and multi-char variants
        LPAREN,      // (
        RPAREN,      // )
        COMMA,       // ,
        SEMI         // ;
    }

    public record Token(Type type, String text) {}

    private FormulaTokenizer() {}

    public static List<Token> tokenize(String expr) {
        List<Token> out = new ArrayList<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(') { out.add(new Token(Type.LPAREN, "(")); i++; continue; }
            if (c == ')') { out.add(new Token(Type.RPAREN, ")")); i++; continue; }
            if (c == ',') { out.add(new Token(Type.COMMA, ",")); i++; continue; }
            if (c == ';') { out.add(new Token(Type.SEMI, ";")); i++; continue; }

            // Strings — preserve verbatim, including the quote characters
            if (c == '\'' || c == '"') {
                int start = i;
                char quote = c;
                i++;
                while (i < n) {
                    char cc = expr.charAt(i);
                    if (cc == '\\' && i + 1 < n) { i += 2; continue; }
                    if (cc == quote) { i++; break; }
                    i++;
                }
                out.add(new Token(Type.STRING, expr.substring(start, i)));
                continue;
            }

            // Numbers — leading digit; optional .DIGITS; optional e[+-]?DIGITS
            if (Character.isDigit(c)) {
                int start = i;
                while (i < n && Character.isDigit(expr.charAt(i))) i++;
                if (i < n && expr.charAt(i) == '.' && i + 1 < n && Character.isDigit(expr.charAt(i + 1))) {
                    i++;
                    while (i < n && Character.isDigit(expr.charAt(i))) i++;
                }
                if (i < n && (expr.charAt(i) == 'e' || expr.charAt(i) == 'E')) {
                    int save = i;
                    i++;
                    if (i < n && (expr.charAt(i) == '+' || expr.charAt(i) == '-')) i++;
                    if (i < n && Character.isDigit(expr.charAt(i))) {
                        while (i < n && Character.isDigit(expr.charAt(i))) i++;
                    } else {
                        i = save; // not a valid exponent — rewind
                    }
                }
                out.add(new Token(Type.NUMBER, expr.substring(start, i)));
                continue;
            }

            // Identifiers — letter/underscore start; can include dot for qualified names
            // (Math.max). We do NOT collapse spaces around the dot here; the tokenizer
            // only joins dot-chains when there is no whitespace between them, which
            // matches what the canonicalizer wants.
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n) {
                    char cc = expr.charAt(i);
                    if (Character.isLetterOrDigit(cc) || cc == '_') { i++; continue; }
                    if (cc == '.' && i + 1 < n) {
                        char next = expr.charAt(i + 1);
                        if (Character.isLetter(next) || next == '_') { i++; continue; }
                    }
                    break;
                }
                out.add(new Token(Type.IDENT, expr.substring(start, i)));
                continue;
            }

            // Multi-char operators first
            if (i + 1 < n) {
                String two = expr.substring(i, i + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("==")
                        || two.equals("!=") || two.equals("&&") || two.equals("||")
                        || two.equals("->")) {
                    out.add(new Token(Type.OP, two));
                    i += 2;
                    continue;
                }
            }

            // Single-char operators (covers + - * / % > < ! ? : =) and the long tail
            // of stray characters (which would already fail MVEL parsing — we just
            // emit them as a single-char OP and let MVEL surface the error if it
            // ever runs).
            out.add(new Token(Type.OP, String.valueOf(c)));
            i++;
        }
        return out;
    }

    /**
     * Emits the token list with normalized spacing. Rules:
     * <ul>
     *   <li>No space adjacent to {@code (} or {@code )} on the inside of the call;
     *       {@code (} sticks to the preceding identifier (function call).</li>
     *   <li>{@code ,} and {@code ;} take no space before, one space after.</li>
     *   <li>All other adjacent token pairs are separated by one space.</li>
     * </ul>
     *
     * <p>Production formulas like {@code Math.max(S, N) + 1} re-emit verbatim; the
     * spaced/unspaced variants of {@code S+1} both produce {@code S + 1}.</p>
     */
    public static String normalize(List<Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            Token tok = tokens.get(i);
            if (i > 0 && needSpace(tokens.get(i - 1), tok)) {
                sb.append(' ');
            }
            sb.append(tok.text);
        }
        return sb.toString();
    }

    private static boolean needSpace(Token prev, Token next) {
        // No space before closing/grouping that hugs the previous token.
        if (next.type == Type.RPAREN || next.type == Type.COMMA || next.type == Type.SEMI) {
            return false;
        }
        // No space after opening paren.
        if (prev.type == Type.LPAREN) {
            return false;
        }
        // Function-call: ident(...) — no space between identifier/RPAREN and following (
        if (next.type == Type.LPAREN && (prev.type == Type.IDENT || prev.type == Type.RPAREN)) {
            return false;
        }
        // Comma/semicolon: one space after.
        if (prev.type == Type.COMMA || prev.type == Type.SEMI) {
            return true;
        }
        // Everything else: one space.
        return true;
    }
}
