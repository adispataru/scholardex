const fs = require('fs');
const path = require('path');

const checks = [
  {
    file: 'src/main/resources/templates/fragments.html',
    forbidden: [
      '/scholardex/forums"',
      '/rankings/categories"',
      '/rankings/core"',
      '/rankings/urap"',
      '/rankings/events"',
      '/admin/rankings/core"',
      '/admin/rankings/urap"',
      '/admin/rankings/events"'
    ]
  },
  {
    file: 'src/main/resources/templates/rankings/category-detail.html',
    forbidden: ['/scholardex/forums/', '/rankings/categories"']
  },
  {
    file: 'src/main/resources/static/js/scholardex-forums.js',
    forbidden: ['/scholardex/forums/data?']
  },
  {
    file: 'src/main/resources/static/js/admin-scholardex-forums.js',
    forbidden: ['/scholardex/forums/data?']
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
