// H51 unique-index audit: duplicate-value check + index-spec drift check.
//
// Run with mongosh against the target database:
//   mongosh "mongodb://user:pass@host:port/dbname" scripts/h51-unique-index-duplicate-audit.js
//
// For each unique index declared in the Java model layer:
//   1. drift: compare the declared (name, keys, sparse) against the live index
//      under the same name. Differences cause auto-index-creation to throw
//      IndexKeySpecsConflict at startup.
//   2. duplicates: group the collection by the declared keys and report any
//      group with count > 1, honoring `sparse` and multikey ($unwind) semantics.
//
// Read-only. Exits non-zero if any drift or duplicates are found.

const EXAMPLE_LIMIT = 5;

// keys:     ordered list of field paths used by the index
// sparse:   true if the declaration sets sparse=true
// multikey: subset of `keys` known to be array-typed
const INDEXES = [
  // @Indexed(unique=true)
  { collection: "institutions",                                 index: "name",                                                  keys: ["name"] },
  { collection: "domains",                                      index: "name",                                                  keys: ["name"] },

  // @CompoundIndex declarations (multi-line and single-line)
  { collection: "userIndicatorResults",                         index: "uniq_user_indicator_mode",                              keys: ["userEmail", "indicatorId", "mode"] },
  { collection: "wos.metric_facts",                             index: "uniq_metric_fact",                                      keys: ["journalId", "year", "metricType"] },
  { collection: "wos.category_facts",                           index: "uniq_category_fact",                                    keys: ["journalId", "year", "categoryNameCanonical", "editionNormalized", "metricType"] },
  { collection: "wos.journal_identity",                         index: "uniq_identity_key",                                     keys: ["identityKey"] },
  { collection: "wos.import_events",                            index: "uniq_wos_import_event_key",                             keys: ["sourceType", "sourceFile", "sourceVersion", "sourceRowItem"] },
  { collection: "scopus.author_facts",                          index: "uniq_scopus_author_fact_author_id",                     keys: ["authorId"] },
  { collection: "scopus.affiliation_facts",                     index: "uniq_scopus_affiliation_fact_afid",                     keys: ["afid"] },
  { collection: "scopus.publication_facts",                     index: "uniq_scopus_publication_fact_eid",                      keys: ["eid"] },
  { collection: "scopus.funding_facts",                         index: "uniq_scopus_funding_fact_key",                          keys: ["fundingKey"] },
  { collection: "scopus.forum_facts",                           index: "uniq_scopus_forum_fact_source_id",                      keys: ["sourceId"] },
  { collection: "scopus.citation_facts",                        index: "uniq_scopus_citation_fact_edge",                        keys: ["citedEid", "citingEid"] },
  { collection: "scopus.import_events",                         index: "uniq_scopus_import_event_idempotence",                  keys: ["entityType", "source", "sourceRecordId", "payloadHash"] },
  { collection: "user_defined.forum_facts",                     index: "uniq_user_defined_forum_source_record_id",              keys: ["sourceRecordId"] },
  { collection: "user_defined.publication_facts",               index: "uniq_user_defined_publication_source_record_id",        keys: ["sourceRecordId"] },
  { collection: "scholardex.source_links",                      index: "uniq_scholardex_source_link",                           keys: ["entityType", "source", "sourceRecordId"] },
  { collection: "scholardex.authorship_facts",                  index: "uniq_scholardex_authorship_edge",                       keys: ["publicationId", "authorId", "source"] },
  { collection: "scholardex.citation_facts",                    index: "uniq_scholardex_citation_edge",                         keys: ["citedPublicationId", "citingPublicationId"] },
  { collection: "scholardex.publication_authorship_decisions",  index: "uniq_publication_authorship_decision_user_publication", keys: ["userEmail", "publicationId"] },
  { collection: "scholardex.author_affiliation_facts",          index: "uniq_scholardex_author_affiliation_edge",               keys: ["authorId", "affiliationId", "source"] },
  { collection: "scholardex.publication_author_affiliation_facts", index: "uniq_scholardex_publication_author_affiliation_edge", keys: ["publicationId", "authorId", "affiliationId", "source"] },
  { collection: "scholardex.identity_conflicts",                index: "uniq_scholardex_open_identity_conflict",                keys: ["entityType", "incomingSource", "incomingSourceRecordId", "reasonCode", "status"], sparse: true },
  { collection: "scholardex.affiliation_facts",                 index: "uniq_scholardex_affiliation_scopus_id",                 keys: ["scopusAffiliationIds"], partial: true, multikey: ["scopusAffiliationIds"] },
  { collection: "scholardex.forum_facts",                       index: "uniq_scholardex_forum_scopus_id",                       keys: ["scopusForumIds"],       partial: true, multikey: ["scopusForumIds"] },
  { collection: "scholardex.forum_facts",                       index: "uniq_scholardex_forum_wos_id",                          keys: ["wosForumIds"],          partial: true, multikey: ["wosForumIds"] },
  { collection: "scholardex.author_facts",                      index: "uniq_scholardex_author_scopus_id",                      keys: ["scopusAuthorIds"],      partial: true, multikey: ["scopusAuthorIds"] },
  { collection: "scholardex.publication_facts",                 index: "uniq_scholardex_publication_fact_eid",                  keys: ["eid"],                  sparse: true },
  { collection: "scholardex.publication_facts",                 index: "uniq_scholardex_publication_fact_wos_id",               keys: ["wosId"],                sparse: true },
  { collection: "scholardex.publication_facts",                 index: "uniq_scholardex_publication_fact_google_scholar_id",    keys: ["googleScholarId"],      sparse: true },
  { collection: "scholardex.publication_facts",                 index: "uniq_scholardex_publication_fact_user_source_id",       keys: ["userSourceId"],         sparse: true },
  { collection: "scholardex.publication_dblp_evidence",         index: "uniq_scholardex_publication_dblp_publication",          keys: ["publicationId"] },
  { collection: "scholardex.division_report_selections",        index: "division_report_unique",                                keys: ["divisionId", "reportId"] },
  { collection: "scholardex.department_report_hides",           index: "department_report_unique",                              keys: ["departmentId", "reportId"] },
];

function buildPipeline(spec) {
  const pipeline = [];
  const skipEmpty = spec.sparse || spec.partial; // sparse and {$type:string} partials both ignore empty/absent
  if (skipEmpty) {
    const match = {};
    for (const k of spec.keys) match[k] = { $exists: true, $nin: [null, []] };
    pipeline.push({ $match: match });
  }
  if (spec.multikey && spec.multikey.length > 0) {
    for (const k of spec.multikey) {
      pipeline.push({ $unwind: { path: "$" + k, preserveNullAndEmptyArrays: !skipEmpty } });
    }
  }
  const groupId = {};
  for (const k of spec.keys) groupId[k.replace(/\./g, "_")] = "$" + k;
  pipeline.push({ $group: { _id: groupId, count: { $sum: 1 }, ids: { $push: "$_id" } } });
  pipeline.push({ $match: { count: { $gt: 1 } } });
  pipeline.push({ $sort: { count: -1 } });
  return pipeline;
}

function liveIndexFor(coll, indexName) {
  const all = coll.getIndexes();
  for (const idx of all) if (idx.name === indexName) return idx;
  return null;
}

function liveKeyList(idx) {
  const out = [];
  for (const k of Object.keys(idx.key)) out.push(k);
  return out;
}

function arraysEqual(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

const summary = [];
let totalDriftIndexes = 0;
let totalDuplicateGroups = 0;
let totalMissingIndexes = 0;

for (const spec of INDEXES) {
  const exists = db.getCollectionInfos({ name: spec.collection }).length > 0;
  if (!exists) {
    summary.push({ collection: spec.collection, index: spec.index, status: "collection-missing" });
    continue;
  }
  const coll = db.getCollection(spec.collection);

  // Drift check
  const live = liveIndexFor(coll, spec.index);
  let driftNote = null;
  if (!live) {
    driftNote = "live-missing"; // declared in code, never created on server
    totalMissingIndexes++;
  } else {
    const liveKeys = liveKeyList(live);
    const liveSparse = !!live.sparse;
    const livePartial = !!live.partialFilterExpression;
    const liveUnique = !!live.unique;
    if (!arraysEqual(liveKeys, spec.keys)
        || !liveUnique
        || liveSparse !== !!spec.sparse
        || livePartial !== !!spec.partial) {
      driftNote = "spec-drift";
      totalDriftIndexes++;
    }
  }

  // Duplicate check under DECLARED key
  let dupErr = null;
  const examples = [];
  let groupCount = 0;
  try {
    const cursor = coll.aggregate(buildPipeline(spec), { allowDiskUse: true });
    while (cursor.hasNext()) {
      const row = cursor.next();
      groupCount++;
      if (examples.length < EXAMPLE_LIMIT) {
        examples.push({ key: row._id, count: row.count, sampleIds: row.ids.slice(0, 3) });
      }
    }
  } catch (e) {
    dupErr = String(e);
  }
  totalDuplicateGroups += groupCount;

  summary.push({
    collection: spec.collection,
    index: spec.index,
    declared: { keys: spec.keys, sparse: !!spec.sparse, partial: !!spec.partial, multikey: spec.multikey || [] },
    live: live ? { keys: liveKeyList(live), sparse: !!live.sparse, unique: !!live.unique } : null,
    driftNote,
    duplicateGroups: groupCount,
    examples,
    dupError: dupErr,
  });
}

print("\n========== H51 unique-index audit ==========");
print("Database: " + db.getName());
print("Indexes audited: " + INDEXES.length);
print("Spec drift: " + totalDriftIndexes + " | Live-missing: " + totalMissingIndexes + " | Duplicate groups (total): " + totalDuplicateGroups);
print("--------------------------------------------");

for (const row of summary) {
  if (row.status === "collection-missing") {
    print("\n- " + row.collection + " :: " + row.index + "  [collection-missing]");
    continue;
  }
  let status = "clean";
  if (row.driftNote === "spec-drift") status = "DRIFT";
  else if (row.driftNote === "live-missing") status = "live-missing";
  if (row.duplicateGroups > 0) status += "+duplicates";
  print("\n- " + row.collection + " :: " + row.index + "  [" + status + "]");
  print("    declared: keys=" + JSON.stringify(row.declared.keys) + " sparse=" + row.declared.sparse + (row.declared.multikey.length ? " multikey=" + JSON.stringify(row.declared.multikey) : ""));
  if (row.live) {
    print("    live:     keys=" + JSON.stringify(row.live.keys) + " sparse=" + row.live.sparse + " unique=" + row.live.unique);
  } else {
    print("    live:     <not present>");
  }
  if (row.dupError) print("    dupError: " + row.dupError);
  if (row.duplicateGroups > 0) {
    print("    duplicate groups: " + row.duplicateGroups);
    for (const ex of row.examples) {
      print("      - key=" + JSON.stringify(ex.key) + " count=" + ex.count + " sampleIds=" + JSON.stringify(ex.sampleIds));
    }
    if (row.duplicateGroups > row.examples.length) {
      print("      ... and " + (row.duplicateGroups - row.examples.length) + " more.");
    }
  }
}

print("\n============================================");
if (totalDriftIndexes > 0 || totalDuplicateGroups > 0) {
  print("RESULT: NOT safe to flip spring.data.mongodb.auto-index-creation=true.");
  print("        Fix drift (drop+recreate) and dedup before enabling.");
  quit(1);
} else {
  print("RESULT: declared == live; no duplicates. Safe to enable auto-index-creation.");
  quit(0);
}
