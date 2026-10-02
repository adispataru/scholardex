package ro.uvt.pokedex.core.service.importing.grid;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * H142 — the rows of the Music fișă de verificare (OM 3.019/2025, Comisia 35) and the activity each row's items
 * become. A row is recognised by the WORDS of its "Categorii și restricții" label, not by its number: the official
 * text numbers the research table 1.1–4.1, the faculty's Word template 2.1.1–2.4.1, and a colleague may renumber.
 */
public final class MusicGridLayout {

    public enum Table { DID, CS, RIA }

    /** A row of the grid: its table, the activity type its items become, and the field that holds the item's text. */
    public enum Row {
        DID_1_1(Table.DID, "Tratat, studiu amplu sau volum de studii publicat (Comisia 35, DID 1.1)", "Titlu"),
        DID_1_2(Table.DID, "Capitol într-un volum colectiv (Comisia 35, DID 1.2)", "Titlu"),
        DID_1_3(Table.DID, "Manual, curs sau alt suport didactic tipărit (Comisia 35, DID 1.3)", "Titlu"),
        DID_1_4(Table.DID, "Traducere, ediție critică sau îngrijire redacțională (Comisia 35, DID 1.4)", "Titlu"),
        DID_2_1(Table.DID, "Înregistrare pe CD, DVD, vinil sau în streaming (Comisia 35, DID 2.1)", "Titlu"),
        CS_1_1(Table.CS, MusicGridLayout.EVENT_TYPE, "Dovezi"),
        CS_1_2(Table.CS, MusicGridLayout.EVENT_TYPE, "Dovezi"),
        CS_2_1(Table.CS, "Articol într-o revistă sau într-un volum indexat în baze de date internaționale (Comisia 35, CS 2.1)", "Titlu"),
        CS_2_2(Table.CS, "Articol în lexicon sau dicționar muzical, pe site-ul unui festival, rezumat RIPM, RILM, RISM, RIDIM (Comisia 35, CS 2.2)", "Titlu"),
        CS_2_3(Table.CS, "Comunicare la o conferință cu comitet de selecție sau peer review (Comisia 35, CS 2.3)", "Titlu"),
        CS_3_1(Table.CS, MusicGridLayout.GRANT_TYPE, "Nume Proiect"),
        CS_4_1(Table.CS, "Compoziție amplă, ciclu sau album de lucrări publicat la o editură de profil (Comisia 35, CS 4.1)", "Titlu"),
        RIA_1_1(Table.RIA, "Funcție de management (Comisia 35, RIA 1.1)", "Functia"),
        RIA_1_2(Table.RIA, MusicGridLayout.GRANT_TYPE, "Nume Proiect"),
        RIA_1_3(Table.RIA, "Membru în colectivul de redacție sau recenzor al unei publicații ori edituri indexate (Comisia 35, RIA 1.3)", "Publicatia_sau_editura"),
        RIA_1_4(Table.RIA, "Organizator al unei manifestări științifice sau artistice (Comisia 35, RIA 1.4 și 1.5)", "Manifestarea"),
        RIA_1_5(Table.RIA, "Organizator al unei manifestări științifice sau artistice (Comisia 35, RIA 1.4 și 1.5)", "Manifestarea"),
        RIA_2_1(Table.RIA, "Distincție sau premiu de stat (Comisia 35, RIA 2.1)", "Denumire"),
        RIA_2_2(Table.RIA, "Distincție sau premiu acordat de o organizație profesională sau de media (Comisia 35, RIA 2.2)", "Denumire"),
        RIA_2_3(Table.RIA, MusicGridLayout.EVENT_TYPE, "Dovezi"),
        RIA_3_1(Table.RIA, "Membru sau funcție într-o academie, organizație ori asociație profesională (Comisia 35, RIA 3.1 și 3.2)", "Organizatia"),
        RIA_3_2(Table.RIA, "Membru sau funcție într-o academie, organizație ori asociație profesională (Comisia 35, RIA 3.1 și 3.2)", "Organizatia"),
        RIA_3_3(Table.RIA, "Membru în juriul unui concurs sau pentru acordarea unor distincții (Comisia 35, RIA 3.3)", "Concursul"),
        RIA_3_4(Table.RIA, "Lucrare achiziționată sau comandată (Comisia 35, RIA 3.4)", "Lucrarea"),
        RIA_3_5(Table.RIA, "Curs, masterclass sau conferință la altă instituție (Comisia 35, RIA 3.5)", "Denumire"),
        RIA_3_6(Table.RIA, "Portret sau interviu ca invitat unic în media (Comisia 35, RIA 3.6)", "Publicatia_sau_postul"),
        RIA_3_7(Table.RIA, "Keynote speaker la o manifestare științifică (Comisia 35, RIA 3.7)", "Manifestarea");

        private final Table table;
        private final String activityType;
        private final String textField;

        Row(Table table, String activityType, String textField) {
            this.table = table;
            this.activityType = activityType;
            this.textField = textField;
        }

        public Table table() { return table; }
        public String activityType() { return activityType; }
        public String textField() { return textField; }
        /** The row as the standard names it: "DID 1.1", "RIA 3.7". */
        public String label() { return name().replaceFirst("_", " ").replace('_', '.'); }
    }

    public static final String EVENT_TYPE = "Participare eveniment artistic";
    public static final String GRANT_TYPE = "Grant Cercetare";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NATIONAL = Pattern.compile("\\bnational\\b");

    private MusicGridLayout() {
    }

    /** The table a sheet holds, from its name or its title ("Tabelul 2", "(CS)", "cercetare"). */
    public static Optional<Table> tableOf(String sheetNameOrTitle) {
        String t = " " + normalize(sheetNameOrTitle) + " ";
        if (t.contains(" ria ") || t.contains("tabelul 3") || t.contains("recunoastere")) return Optional.of(Table.RIA);
        if (t.contains(" cs ") || t.contains("tabelul 2") || t.contains("cercetare")) return Optional.of(Table.CS);
        if (t.contains(" did ") || t.contains("tabelul 1") || t.contains("didactic")) return Optional.of(Table.DID);
        return Optional.empty();
    }

    /** The row a "Categorii și restricții" label names, within its table. */
    public static Optional<Row> classify(Table table, String label) {
        String l = normalize(label);
        if (l.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (table) {
            case DID -> {
                if (l.contains("tratat") || l.contains("studiu amplu") || l.contains("volum de studii")) yield Row.DID_1_1;
                if (l.contains("capitol")) yield Row.DID_1_2;
                if (l.contains("manual") || l.contains("suport de curs") || l.contains("crestomatie") || l.contains("indrumator")) yield Row.DID_1_3;
                if (l.contains("traducere") || l.contains("editare critica") || l.contains("ingrijire redactionala")) yield Row.DID_1_4;
                if (l.contains("inregistrar") || l.contains("dvd") || l.contains("streaming")) yield Row.DID_2_1;
                yield null;
            }
            case CS -> {
                if (l.contains("vizibilitate internationala") || l.contains("nationala de varf")) yield Row.CS_1_1;
                if (l.contains("vizibilitate regionala") || l.contains("locala")) yield Row.CS_1_2;
                if (l.contains("lexicoane") || l.contains("dictionare") || l.contains("rilm") || l.contains("site urile")) yield Row.CS_2_2;
                if (l.contains("comunicare")) yield Row.CS_2_3;
                if (l.contains("studiu sau articol") || l.contains("articol publicat") || l.contains("indexata")) yield Row.CS_2_1;
                if (l.contains("echipa de cercetare") || l.contains("grant") || l.contains("proiect")) yield Row.CS_3_1;
                if (l.contains("compozitii") || l.contains("cicluri") || l.contains("albume")) yield Row.CS_4_1;
                yield null;
            }
            case RIA -> {
                if (l.contains("functii de management")) yield Row.RIA_1_1;
                if ((l.contains("director") || l.contains("coordonator")) && (l.contains("grant") || l.contains("proiect"))) yield Row.RIA_1_2;
                if (l.contains("redactie") || l.contains("recenzor")) yield Row.RIA_1_3;
                if (l.contains("organizator") && l.contains("international")) yield Row.RIA_1_4;
                if (l.contains("organizator") && NATIONAL.matcher(l).find()) yield Row.RIA_1_5;
                // the interview row speaks of "media" too: it is decided before the prizes
                if (l.contains("interviu") || l.contains("portret")) yield Row.RIA_3_6;
                if (l.contains("key note") || l.contains("keynote")) yield Row.RIA_3_7;
                if (l.contains("premii de stat")) yield Row.RIA_2_1;
                if (l.contains("concursuri de creatie") || l.contains("interpretare de prestigiu")) yield Row.RIA_2_3;
                if (l.contains("organizatii profesionale") || l.contains("premii acordate") || l.contains("media")) yield Row.RIA_2_2;
                if (l.contains("detinator") || l.contains("functii in academii")) yield Row.RIA_3_2;
                if (l.contains("membru in academii") || (l.contains("membru") && l.contains("asociatii"))) yield Row.RIA_3_1;
                if (l.contains("jurii")) yield Row.RIA_3_3;
                if (l.contains("achizitionate") || l.contains("comandate")) yield Row.RIA_3_4;
                if (l.contains("masterclass") || l.contains("cursuri")) yield Row.RIA_3_5;
                yield null;
            }
        });
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String n = MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ");
        return n.trim().replaceAll("\\s+", " ");
    }
}
