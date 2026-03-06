#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const baselinePath = path.join(__dirname, '..', 'src', 'main', 'resources', 'wos', 'parity', 'baseline-v1.json');
const metadataPath = path.join(__dirname, '..', 'src', 'main', 'resources', 'wos', 'parity', 'baseline-v1.metadata.json');

const errors = [];

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (err) {
    errors.push(`${file}: invalid JSON (${err.message})`);
    return null;
  }
}

function requireKeys(obj, keys, label) {
  if (!obj || typeof obj !== 'object') {
    errors.push(`${label}: expected object`);
    return;
  }
  for (const key of keys) {
    if (!(key in obj)) {
      errors.push(`${label}: missing key '${key}'`);
    }
  }
}

const baseline = readJson(baselinePath);
const metadata = readJson(metadataPath);

if (baseline) {
  requireKeys(
    baseline,
    [
      'version',
      'counts',
      'editionCounts',
      'scienceToScieExpectedCount',
      'bundledSplitChecks',
      'timelineChecks',
      'categoryChecks',
      'scoringChecks',
      'ifMissingChecks',
      'allowlistedMismatches'
    ],
    'baseline-v1.json'
  );

  requireKeys(baseline.counts, ['importEvents', 'metricFacts', 'categoryFacts', 'rankingView', 'scoringView'], 'baseline.counts');

  if (!Array.isArray(baseline.timelineChecks)) {
    errors.push('baseline.timelineChecks: must be an array');
  } else {
    baseline.timelineChecks.forEach((item, i) => {
      requireKeys(item, ['journalId', 'metricType', 'edition', 'expected'], `baseline.timelineChecks[${i}]`);
    });
  }

  if (!Array.isArray(baseline.categoryChecks)) {
    errors.push('baseline.categoryChecks: must be an array');
  } else {
    baseline.categoryChecks.forEach((item, i) => {
      requireKeys(item, ['journalId', 'categoryNameCanonical', 'metricType', 'edition', 'expected'], `baseline.categoryChecks[${i}]`);
    });
  }

  if (!Array.isArray(baseline.scoringChecks)) {
    errors.push('baseline.scoringChecks: must be an array');
  } else {
    baseline.scoringChecks.forEach((item, i) => {
      requireKeys(item, ['categoryNameCanonical', 'edition', 'metricType', 'year', 'quarter', 'expectedTopCount'], `baseline.scoringChecks[${i}]`);
    });
  }

  if (!Array.isArray(baseline.ifMissingChecks)) {
    errors.push('baseline.ifMissingChecks: must be an array');
  } else {
    baseline.ifMissingChecks.forEach((item, i) => {
      requireKeys(item, ['journalId', 'year', 'edition', 'expectedMissing'], `baseline.ifMissingChecks[${i}]`);
    });
  }
}

if (metadata) {
  requireKeys(metadata, ['baselineVersion', 'source', 'fixture', 'allowlistPolicy'], 'baseline-v1.metadata.json');

  const fixture = metadata.fixture || {};
  requireKeys(fixture, ['journalCount', 'requiredCoverageTags', 'journals'], 'metadata.fixture');

  if (!Array.isArray(fixture.journals)) {
    errors.push('metadata.fixture.journals: must be an array');
  } else {
    if (fixture.journals.length !== 20) {
      errors.push(`metadata.fixture.journals: expected 20 journals, found ${fixture.journals.length}`);
    }
    if (Number(fixture.journalCount) !== 20) {
      errors.push(`metadata.fixture.journalCount: expected 20, found ${fixture.journalCount}`);
    }

    const seen = new Set();
    fixture.journals.forEach((item, i) => {
      requireKeys(item, ['journalId', 'tags'], `metadata.fixture.journals[${i}]`);
      if (item.journalId) {
        if (seen.has(item.journalId)) {
          errors.push(`metadata.fixture.journals[${i}]: duplicate journalId '${item.journalId}'`);
        }
        seen.add(item.journalId);
      }
      if (!Array.isArray(item.tags) || item.tags.length === 0) {
        errors.push(`metadata.fixture.journals[${i}].tags: must be a non-empty array`);
      }
    });

    if (Array.isArray(fixture.requiredCoverageTags)) {
      const allTags = new Set(fixture.journals.flatMap((j) => Array.isArray(j.tags) ? j.tags : []));
      fixture.requiredCoverageTags.forEach((tag) => {
        if (!allTags.has(tag)) {
          errors.push(`metadata.fixture.requiredCoverageTags: '${tag}' missing from selected journals`);
        }
      });
    } else {
      errors.push('metadata.fixture.requiredCoverageTags: must be an array');
    }
  }
}

if (errors.length > 0) {
  console.error('WoS parity baseline verification failed:');
  for (const error of errors) {
    console.error(` - ${error}`);
  }
  process.exit(1);
}

console.log('WoS parity baseline verification passed.');
