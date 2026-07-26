import $ from 'jquery';
import '@fortawesome/fontawesome-free/css/all.min.css';
import 'datatables.net-bs5/css/dataTables.bootstrap5.min.css';
import './styles/foundation.css';
import './styles/login.css';
import './styles/shared-header.css';
import './styles/shared-sidebar.css';
import './styles/shared-table.css';
import './styles/shared-badges.css';
import './styles/shared-form.css';
import './styles/shared-dashboard.css';
import './styles/shared-tabs.css';
import './styles/shared-skeleton.css';
import './styles/shared-search.css';
import './styles/shared-filter-panel.css';
import './styles/shared-breadcrumb.css';
import './styles/shared-notifications.css';
import './styles/error-pages.css';
import './styles/workspace-publications.css';
import './styles/workspace-activities.css';
import './styles/workspace-profile.css';
import './styles/workspace-onboarding.css';
import './styles/shared-shortcuts.css';
import './styles/shared-confirm-dialog.css';
import './styles/shared-toasts.css';
import './styles/shared-pagination.css';
import './styles/admin-dashboard.css';
import './styles/admin-tables.css';
import './styles/admin-forms.css';
import './styles/public-shell.css';
import './styles/public-forums.css';
import './styles/public-universities.css';
import './styles/public-changelog.css';
import { initConfirmationDialog } from './modules/shared/confirmationDialog';
import { initModalShell } from './modules/shared/modalShell';
import { initSearchInputs } from './modules/shared/searchInput';
import { initToastManager } from './modules/shared/toastManager';
import { initAdminUsers } from './modules/admin/adminUsers';
import { initOrgUnitReportDashboard } from './modules/admin/orgUnitReportDashboard';
import { initAdminBulkSelect } from './modules/shared/adminBulkSelect';
import { initAdminColumnToggle } from './modules/shared/adminColumnToggle';
import { initAdminShortcuts } from './modules/shared/adminShortcuts';
import { initPublicShell } from './modules/shared/publicShell';
import { initForumDetailCharts } from './modules/public/forumDetailCharts';
import { initUniversityDetailCharts } from './modules/public/universityDetailCharts';

window.$ = $;
window.jQuery = $;

import 'datatables.net-bs5';
import Chart from 'chart.js';
import { initSharedDomBehaviors } from './modules/shared/domBehaviors';
import { initSubmitLock } from './modules/shared/submitLock';
import { initSharedHeaderShell } from './modules/shared/headerShell';
import { initLegacyInteractions } from './modules/shared/legacyInteractions';
import { initPublicationSubtypeSync } from './modules/shared/publicationSubtypeSync';
import { initSharedSidebarShell } from './modules/shared/sidebarShell';
import { initSharedDataTables } from './modules/shared/tableEnhancer';
import { initThemeShell } from './modules/shared/themeShell';
import { alphaColor, getChartTheme } from './modules/shared/chartTheme';
import { t, tPlural } from './modules/shared/i18n';
import { initErrorPages } from './modules/shared/errorPages';
import { initWorkspaceTabs } from './modules/shared/workspaceTabs';
import { initWorkspaceShortcuts } from './modules/shared/workspaceShortcuts';
import { initWorkspacePanelLoader } from './modules/shared/workspacePanelLoader';
import { initWorkspaceOverview } from './modules/workspace/workspaceOverview';
import { initWorkspaceSearch } from './modules/workspace/workspaceSearch';
import { initWorkspaceNotifications } from './modules/workspace/workspaceNotifications';
import { initWorkspacePublications } from './modules/workspace/workspacePublications';
import { initWorkspaceActivities } from './modules/workspace/workspaceActivities';
import { initWorkspaceProfile } from './modules/workspace/workspaceProfile';
import { initWorkspaceOnboarding } from './modules/workspace/workspaceOnboarding';

window.Chart = Chart;
window.appChartTheme = {
  alphaColor,
  getChartTheme
};

// H91: static/js/individual-report-dashboard.js is not part of this bundle (no import possible), but it
// renders the researcher's own scoring drilldown and needs the same copy. Expose the SHARED lookup rather
// than letting that file grow its own — a second implementation of one rule is how the Lecture-Notes
// double count happened.
window.appT = t;
window.appTPlural = tPlural;

initSharedDomBehaviors();
initSubmitLock();
initThemeShell();
initSharedHeaderShell();
initSharedSidebarShell();
initModalShell();
initLegacyInteractions();
initSharedDataTables();
initErrorPages();
initConfirmationDialog();
initToastManager();
initSearchInputs();
initPublicationSubtypeSync();
initWorkspaceTabs();
initWorkspaceShortcuts();
initWorkspacePanelLoader();
initWorkspaceOverview();
initWorkspaceSearch();
initWorkspaceNotifications();
initWorkspacePublications();
initWorkspaceActivities();
initWorkspaceProfile();
initWorkspaceOnboarding();
initAdminUsers();
initOrgUnitReportDashboard();
window.initAdminBulkSelect = initAdminBulkSelect;
window.initAdminColumnToggle = initAdminColumnToggle;
window.initAdminShortcuts = initAdminShortcuts;
initPublicShell();
initForumDetailCharts();
initUniversityDetailCharts();
