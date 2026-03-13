const fs = require('fs');
const path = require('path');

const checks = [
  {
    file: 'src/main/resources/templates/fragments.html',
    forbidden: [
      '/admin/rankings/wos"',
      '/admin/scopus/',
      '/rankings/wos"',
      '/scholardex/forums"'
    ]
  },
  {
    file: 'src/main/resources/templates/forums/list.html',
    forbidden: ['/rankings/wos"', '/admin/scopus/', '/scholardex/forums/data']
  },
  {
    file: 'src/main/resources/templates/forums/detail.html',
    forbidden: ['/rankings/wos/"', '/admin/scopus/', '/rankings/categories/']
  },
  {
    file: 'src/main/resources/templates/admin/scholardex-forums.html',
    forbidden: ['/admin/scopus/', '/admin/rankings/wos"']
  },
  {
    file: 'src/main/resources/static/js/scholardex-forums.js',
    forbidden: ['/rankings/wos/', '/admin/scopus/']
  },
  {
    file: 'src/main/resources/static/js/admin-scholardex-forums.js',
    forbidden: ['/admin/scopus/', '/rankings/wos/']
  }
];

const errors = [];
for (const check of checks) {
  const filePath = path.join(process.cwd(), check.file);
  const content = fs.readFileSync(filePath, 'utf8');
  for (const forbidden of check.forbidden) {
    if (content.includes(forbidden)) {
      errors.push(`${check.file}: contains forbidden canonical-route regression '${forbidden}'`);
    }
  }
}

if (errors.length > 0) {
  console.error('H23 UI guardrail verification failed:');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log('H23 UI guardrail verification passed.');
