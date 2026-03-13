const fs = require('fs');
const path = require('path');

const checks = [
  {
    file: 'src/main/resources/templates/fragments.html',
    forbidden: [
      '/scholardex/forums"',
      '/rankings/wos"',
      '/rankings/categories"',
      '/rankings/core"',
      '/rankings/urap"',
      '/rankings/events"',
      '/core"',
      '/urap"',
      '/admin/rankings/core"',
      '/admin/rankings/urap"',
      '/admin/rankings/events"',
      '/admin/rankings/wos"'
    ]
  },
  {
    file: 'src/main/resources/templates/rankings/category-detail.html',
    forbidden: ['/scholardex/forums/', '/rankings/categories"']
  },
  {
    file: 'src/main/resources/templates/rankings/categories.html',
    forbidden: ['data-detail-base="/rankings/categories"']
  },
  {
    file: 'src/main/resources/templates/rankings/core.html',
    forbidden: ['data-detail-base="/rankings/core"']
  },
  {
    file: 'src/main/resources/templates/rankings/urap.html',
    forbidden: ['data-detail-base="/rankings/urap"']
  },
  {
    file: 'src/main/resources/templates/scholardex/forum-detail.html',
    forbidden: ['/rankings/categories/']
  },
  {
    file: 'src/main/resources/templates/user/indicators-apply-citations.html',
    forbidden: ['/scholardex/forums/']
  },
  {
    file: 'src/main/resources/static/js/scholardex-forums.js',
    forbidden: ['/scholardex/forums/data?', '/rankings/wos/']
  },
  {
    file: 'src/main/resources/static/js/admin-scholardex-forums.js',
    forbidden: ['/scholardex/forums/data?']
  },
  {
    file: 'src/main/resources/static/js/rankings-categories.js',
    forbidden: ["/rankings/categories'"]
  },
  {
    file: 'src/main/resources/static/js/rankings-core.js',
    forbidden: ["/rankings/core'"]
  },
  {
    file: 'src/main/resources/static/js/rankings-urap.js',
    forbidden: ["/rankings/urap'"]
  }
];

const errors = [];
for (const check of checks) {
  const filePath = path.join(process.cwd(), check.file);
  const content = fs.readFileSync(filePath, 'utf8');
  for (const forbidden of check.forbidden) {
    if (content.includes(forbidden)) {
      errors.push(`${check.file}: contains forbidden H25 route regression '${forbidden}'`);
    }
  }
}

if (errors.length > 0) {
  console.error('H25 route guardrail verification failed:');
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  process.exit(1);
}

console.log('H25 route guardrail verification passed.');
