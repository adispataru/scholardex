package ro.uvt.pokedex.core.model.reporting.wos;

public enum WosSourceType {
    GOV_AIS_RIS,
    OFFICIAL_WOS_EXTRACT,
    /** H66 A3: WoS Master Journal List coverage export (per-edition CSVs) — current edition membership. */
    MJL_COVERAGE,
    /** JCR matrix export (Title20 → full title + edition flags) — naming reference only, no facts. */
    JCR_REFERENCE
}
