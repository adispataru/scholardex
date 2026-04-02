import $ from 'jquery';
import '@fortawesome/fontawesome-free/css/all.min.css';
import 'datatables.net-bs5/css/dataTables.bootstrap5.min.css';
import './styles/foundation.css';
import './styles/shared-header.css';
import './styles/shared-sidebar.css';
import './styles/shared-table.css';
import './styles/shared-form.css';
import './styles/shared-dashboard.css';

window.$ = $;
window.jQuery = $;

import 'datatables.net-bs5';
import Chart from 'chart.js';
import { initSharedDomBehaviors } from './modules/shared/domBehaviors';
import { initSharedHeaderShell } from './modules/shared/headerShell';
import { initLegacyInteractions } from './modules/shared/legacyInteractions';
import { initPublicationSubtypeSync } from './modules/shared/publicationSubtypeSync';
import { initSharedSidebarShell } from './modules/shared/sidebarShell';
import { initSharedDataTables } from './modules/shared/tableEnhancer';
import { initThemeShell } from './modules/shared/themeShell';

window.Chart = Chart;

initSharedDomBehaviors();
initThemeShell();
initSharedHeaderShell();
initSharedSidebarShell();
initLegacyInteractions();
initSharedDataTables();
initPublicationSubtypeSync();
