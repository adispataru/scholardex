package ro.uvt.pokedex.core.service.brainmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * H64 slice 1 — one line of {@code data/brainmap/uvt_projects.jsonl}, the brainmap (RO national projects) source dump
 * produced by {@code scopus-python/brainmap_dump.py}. Field names mirror the scraper's output keys verbatim.
 *
 * <p>Note: brainmap carries <b>no budget</b> (verified — absent from the results list and the project detail page),
 * so there is no amount field here; A10's € stays declared on the activity (see the H64 task doc).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BrainmapProjectRecord {

    /** brainmap internal project primary key (e.g. {@code "8"}); 100% present — used as the source-fact id. */
    @JsonProperty("pkXProiectId")
    private String pkXProiectId;

    private String title;

    /** Submission/grant code, e.g. {@code PN-III-P2-2.1-PED-2016-0592} or {@code Horizon-239038-101061610}. */
    private String code;

    private String plan;
    private String competition;

    @JsonProperty("directorFirst")
    private String directorFirst;
    @JsonProperty("directorLast")
    private String directorLast;
    @JsonProperty("directorRole")
    private String directorRole;

    /** Lead-organization display name (brainmap "coordonator"), resolved to a canonical affiliation at canon time. */
    private String coordinator;

    /** Funder shorthand: UEFISCDI / MEd / EC / IFA / ROSA / … */
    private String funder;

    private String startYear;
    private String endYear;

    /** Deep link to the brainmap project detail page (carries the goToProiect token). */
    @JsonProperty("detailHref")
    private String detailHref;

    /** brainmap organization id the dump was scoped to (UVT = {@code O-1600-000Y-0280}). */
    private String orgId;

    /** ISO-8601 scrape timestamp from the dumper. */
    private String scrapedAt;
}
