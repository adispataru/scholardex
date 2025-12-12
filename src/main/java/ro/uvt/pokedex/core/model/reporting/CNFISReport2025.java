package ro.uvt.pokedex.core.model.reporting;
import lombok.Data;

@Data
public class CNFISReport2025 {

    // Article / Patent Details
    private String titlu;               // "Titlul articolului / Titlul brevetului"
    private String doi;                 // "cod DOI" (digital object identifier)
    private String wosCode;             // "cod WOS" (Web of Science identifier)
    private String brevetCode;          // "cod Breve" (patent code for triadic patents)

    // Publication Details
    private String denumireJurnal;      // "Denumirea jurnalului / volumului / oficiului de brevete"
    private String issnOnline;          // "cod ISSN onlie" (format xxxx-xxxx)
    private String issnPrint;           // "cod ISSN print" (format xxxx-xxxx)
    private String isbn;                // "cod ISBN"
    private String oficiuBrevet;        // "date identificare oficiu brevete"

    // Article Classification (Încadrare articole)
    private boolean natureScience;                          // "Nature/Science"
    private boolean isiQ1;                                // "ISI Roşu"
    private boolean isiQ2;                              // "ISI Galben"
    private boolean isiQ3;                                 // "ISI Alb"
    private boolean isiQ4;                                 // "ISI Alb"
    private boolean isiArtsHumanities;                      // "ISI Arts&Humanities"
    private boolean isiEmergingSourcesCitationIndex;        // "ISI Emerging Sources Citation Index"
    private boolean erihPlus;                               // "ERIH+"
    private boolean isiProceedings;                         // "ISI Proceedings"
    private boolean ieeeProceedings;                        // "IEEE Proceedings"
    private boolean triadice;                               // "Triadice"

    // Patent Classification (Încadrare brevete)
    private boolean europene;                // "Europene"
    private boolean internationale;                // "Internaţionale"
    private boolean nationale;                              // "Naţionale"

    // Authors
    private int numarAutori;                // "Număr autori"
    private int numarAutoriUniversitate;    // "Număr autori din universitate"
}
