const assert = require('assert');

const DEFAULT_BASE_URL = process.env.H48_SMOKE_BASE_URL || 'http://localhost:8080';
const AUTH_EMAIL = process.env.H48_SMOKE_EMAIL;
const AUTH_PASSWORD = process.env.H48_SMOKE_PASSWORD;

class HttpSession {
  constructor(baseUrl) {
    this.baseUrl = baseUrl.replace(/\/+$/, '');
    this.cookies = new Map();
  }

  cookieHeader() {
    return Array.from(this.cookies.entries())
      .map(([name, value]) => `${name}=${value}`)
      .join('; ');
  }

  storeCookies(response) {
    const setCookies = typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [];
    for (const header of setCookies) {
      const [cookiePair] = header.split(';');
      const separatorIndex = cookiePair.indexOf('=');
      if (separatorIndex <= 0) {
        continue;
      }
      const name = cookiePair.slice(0, separatorIndex).trim();
      const value = cookiePair.slice(separatorIndex + 1).trim();
      this.cookies.set(name, value);
    }
  }

  async request(pathname, options = {}) {
    const {
      method = 'GET',
      headers = {},
      body,
      redirect = 'follow'
    } = options;

    const url = pathname.startsWith('http') ? pathname : `${this.baseUrl}${pathname}`;
    const requestHeaders = new Headers(headers);
    const cookieHeader = this.cookieHeader();
    if (cookieHeader) {
      requestHeaders.set('cookie', cookieHeader);
    }

    const response = await fetch(url, {
      method,
      headers: requestHeaders,
      body,
      redirect
    });

    this.storeCookies(response);
    return response;
  }
}

function expectIncludes(haystack, needles, context) {
  for (const needle of needles) {
    assert(
      haystack.includes(needle),
      `${context}: expected content to include "${needle}"`
    );
  }
}

function expectNoFatalHtml(html, context) {
  const forbiddenMarkers = [
    'Whitelabel Error Page',
    'Unhandled API exception',
    'Exception Report',
    'There was an unexpected error'
  ];
  for (const marker of forbiddenMarkers) {
    assert(!html.includes(marker), `${context}: unexpected fatal marker "${marker}"`);
  }
}

async function fetchText(session, pathname, options = {}) {
  const response = await session.request(pathname, options);
  const text = await response.text();
  const expectedStatus = options.expectedStatus || 200;
  assert.strictEqual(
    response.status,
    expectedStatus,
    `${pathname}: expected HTTP ${expectedStatus}, got ${response.status}`
  );
  return text;
}

async function fetchJson(session, pathname, options = {}) {
  const response = await session.request(pathname, options);
  const text = await response.text();
  const expectedStatus = options.expectedStatus || 200;
  assert.strictEqual(
    response.status,
    expectedStatus,
    `${pathname}: expected HTTP ${expectedStatus}, got ${response.status}`
  );
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`${pathname}: expected JSON response, got:\n${text.slice(0, 400)}`);
  }
}

function extractCsrfToken(loginHtml) {
  const match = loginHtml.match(/name="_csrf"\s+value="([^"]+)"/);
  assert(match, 'Could not extract _csrf token from /login');
  return match[1];
}

async function login(session, username, password) {
  const loginHtml = await fetchText(session, '/login');
  const csrf = extractCsrfToken(loginHtml);
  const form = new URLSearchParams({
    username,
    password,
    _csrf: csrf
  });
  const response = await session.request('/login', {
    method: 'POST',
    redirect: 'manual',
    headers: {
      'content-type': 'application/x-www-form-urlencoded',
      referer: `${session.baseUrl}/login`
    },
    body: form.toString()
  });
  assert(
    response.status === 302 || response.status === 303,
    `POST /login: expected redirect, got ${response.status}`
  );
  const location = response.headers.get('location') || '';
  assert(location.includes('/'), `POST /login: expected redirect location, got "${location}"`);
}

function firstItemId(payload, label) {
  assert(Array.isArray(payload.items), `${label}: expected items array`);
  assert(payload.items.length > 0, `${label}: expected at least one item`);
  const item = payload.items[0];
  assert(item && item.id, `${label}: expected first item to have id, got ${JSON.stringify(item)}`);
  return item.id;
}

function firstItemKey(payload, label) {
  assert(Array.isArray(payload.items), `${label}: expected items array`);
  assert(payload.items.length > 0, `${label}: expected at least one item`);
  const item = payload.items[0];
  assert(item && item.key, `${label}: expected first item to have key, got ${JSON.stringify(item)}`);
  return item.key;
}

function firstHref(html, regex, label) {
  const match = html.match(regex);
  assert(match, `${label}: could not find expected link`);
  return match[1];
}

async function verifyPage(session, pathname, markers) {
  const html = await fetchText(session, pathname);
  expectNoFatalHtml(html, pathname);
  expectIncludes(html, markers, pathname);
  return html;
}

async function verifyPublicCoverage(baseUrl) {
  const session = new HttpSession(baseUrl);

  await verifyPage(session, '/', [
    'ScholarDex',
    'Browse Publications',
    'Explore research surfaces'
  ]);

  await verifyPage(session, '/login', [
    'Sign in | ScholarDex',
    'Sign in with local account',
    'Institutional account'
  ]);

  await verifyPage(session, '/publications', [
    'Browse scholarly publications indexed in ScholarDex.',
    'Publication Catalog',
    'publications-table-body'
  ]);

  const publications = await fetchJson(session, '/publications/data?page=0&size=1');
  const publicationId = firstItemId(publications, '/publications/data');
  await verifyPage(session, `/publications/${encodeURIComponent(publicationId)}`, [
    'Publication',
    'All publications',
    'Identifiers'
  ]);

  await verifyPage(session, '/forums', [
    'Search and filter journals and conferences indexed in the ScholarDex catalog.',
    'Forum Directory',
    'scholardex-forums-table-body'
  ]);

  const forums = await fetchJson(session, '/forums/data?page=0&size=1&wos=indexed');
  const forumId = firstItemId(forums, '/forums/data?wos=indexed');
  await verifyPage(session, `/forums/${encodeURIComponent(forumId)}`, [
    'Forum profile',
    'Authenticated ranking detail available',
    'Sign in to view Web of Science rankings'
  ]);

  await verifyPage(session, '/rankings', [
    'Rankings sections',
    'tab-panel-core',
    'tab-panel-universities',
    'tab-panel-events'
  ]);

  const coreRankings = await fetchJson(session, '/api/rankings/core?page=0&size=1');
  const coreId = firstItemId(coreRankings, '/api/rankings/core');
  await verifyPage(session, `/core/rankings/${encodeURIComponent(coreId)}`, [
    'CORE Conference',
    'CORE Rank',
    'Rankings Over the Years',
    'rankingChart'
  ]);

  const universities = await fetchJson(session, '/api/rankings/urap?page=0&size=1');
  const universityId = firstItemId(universities, '/api/rankings/urap');
  await verifyPage(session, `/universities/${encodeURIComponent(universityId)}`, [
    'University profile',
    'Overview Trends',
    'Detailed Indicators',
    'urap-total-score-chart',
    'urap-score-data',
    'urap-fields-data'
  ]);

  await verifyPage(session, '/publications/__h48_missing__', [
    'Page not found',
    'Browse forums'
  ]);
}

async function verifyAuthenticatedCoverage(baseUrl) {
  assert(
    AUTH_EMAIL && AUTH_PASSWORD,
    'verify-h48-browser-smoke requires H48_SMOKE_EMAIL and H48_SMOKE_PASSWORD for authenticated coverage'
  );

  const session = new HttpSession(baseUrl);
  await login(session, AUTH_EMAIL, AUTH_PASSWORD);

  await verifyPage(session, '/user/workspace', [
    'Research Workspace',
    'ScholarDex workspace',
    'Switch Workspace'
  ]);

  await verifyPage(session, '/rankings', [
    'Rankings sections',
    'tab-panel-wos',
    'wos-categories-table-body'
  ]);

  const categories = await fetchJson(session, '/api/rankings/categories?page=0&size=1');
  const wosKey = firstItemKey(categories, '/api/rankings/categories');
  await verifyPage(session, `/wos/categories/${encodeURIComponent(wosKey)}`, [
    'WoS Category',
    'All categories',
    'Journal Coverage',
    'wos-category-detail-table'
  ]);

  const forums = await fetchJson(session, '/forums/data?page=0&size=1&wos=indexed');
  const forumId = firstItemId(forums, '/forums/data?wos=indexed');
  await verifyPage(session, `/forums/${encodeURIComponent(forumId)}`, [
    'Forum profile',
    'General Metrics',
    'Category Rankings',
    'app-forum-detail__chart-shell',
    'forum-wos-score-data',
    'forum-wos-category-data'
  ]);

  await verifyPage(session, '/user/evaluation', [
    'My Snapshots'
  ]);

  await verifyPage(session, '/admin', [
    'Admin Dashboard',
    'System operations',
    'Conflicts queue'
  ]);

  await verifyPage(session, '/admin/users', [
    'User Management',
    'Manage platform access'
  ]);

  await verifyPage(session, '/admin/scholardex/publications', [
    'Publications',
    'Publications catalog pagination'
  ]);
  const authors = await fetchJson(session, '/api/entities/authors?page=0&size=1');
  const authorId = firstItemId(authors, '/api/entities/authors');

  await verifyPage(session, `/user/authors/view/${encodeURIComponent(authorId)}`, [
    'Author Publications'
  ]);

  await verifyPage(session, '/admin/conflicts', [
    'Canonical Identity Conflicts',
    'Identity Conflict Queue'
  ]);

  const groupsHtml = await verifyPage(session, '/admin/groups', [
    'Detailed View of Groups'
  ]);
  const groupId = firstHref(
    groupsHtml,
    /href="\/admin\/groups\/([^"\/?#]+)"/,
    '/admin/groups workspace link'
  );
  await verifyPage(session, `/admin/groups/${encodeURIComponent(groupId)}`, [
    'Individual Reports',
    'Overview',
    'Publications'
  ]);

  const institutionsHtml = await verifyPage(session, '/admin/institutions', [
    'Detailed View of Institutions'
  ]);
  const institutionId = firstHref(
    institutionsHtml,
    /href="\/admin\/institutions\/([^"\/?#]+)"/,
    '/admin/institutions workspace link'
  );
  await verifyPage(session, `/admin/institutions/${encodeURIComponent(institutionId)}`, [
    'Institution sections',
    'Total Publications'
  ]);

  const reportsHtml = await verifyPage(session, '/admin/individualReports', [
    'Detailed View of Individual Reports'
  ]);
  const reportId = firstHref(
    reportsHtml,
    /href="\/admin\/individualReports\/edit\/([^"\/?#]+)"/,
    '/admin/individualReports edit link'
  );
  await verifyPage(session, `/admin/individualReports/edit/${encodeURIComponent(reportId)}`, [
    'Edit Individual Report',
    'Criteria builder'
  ]);

  const activitiesHtml = await verifyPage(session, '/admin/activities', [
    'Detailed View of Activities'
  ]);
  const activityId = firstHref(
    activitiesHtml,
    /href="\/admin\/activities\/edit\/([^"\/?#]+)"/,
    '/admin/activities edit link'
  );
  await verifyPage(session, `/admin/activities/edit/${encodeURIComponent(activityId)}`, [
    'Edit Activity',
    'Referenced fields'
  ]);

  await verifyPage(session, '/publications/__h48_missing__', [
    'Page not found',
    'Browse forums'
  ]);
}

async function run() {
  await verifyPublicCoverage(DEFAULT_BASE_URL);
  await verifyAuthenticatedCoverage(DEFAULT_BASE_URL);
  console.log(`H48 browser smoke verification passed against ${DEFAULT_BASE_URL}.`);
}

run().catch((error) => {
  console.error(error.stack || String(error));
  process.exit(1);
});
