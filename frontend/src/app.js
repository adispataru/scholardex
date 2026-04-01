import $ from 'jquery';
import 'bootstrap/dist/css/bootstrap.min.css';
import '@fortawesome/fontawesome-free/css/all.min.css';
import 'datatables.net-bs4/css/dataTables.bootstrap4.min.css';
import './styles/foundation.css';
import './styles/shared-header.css';
import './styles/shared-sidebar.css';

window.$ = $;
window.jQuery = $;

import 'bootstrap/dist/js/bootstrap.bundle.min.js';
import 'jquery.easing';
import 'datatables.net-bs4';
import Chart from 'chart.js';
import { initSharedDomBehaviors } from './modules/shared/domBehaviors';
import { initSharedHeaderShell } from './modules/shared/headerShell';
import { initPublicationSubtypeSync } from './modules/shared/publicationSubtypeSync';
import { initSharedSidebarShell } from './modules/shared/sidebarShell';
import { initThemeShell } from './modules/shared/themeShell';

window.Chart = Chart;

initSharedDomBehaviors();
initThemeShell();
initSharedHeaderShell();
initSharedSidebarShell();
initPublicationSubtypeSync();
