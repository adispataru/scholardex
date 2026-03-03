const fs = require('fs');

const expected = [
  'src/main/resources/static/assets/app.js',
  'src/main/resources/static/assets/app.css'
];

const missing = expected.filter((file) => !fs.existsSync(file));
if (missing.length > 0) {
  console.error('Missing built assets:');
  missing.forEach((m) => console.error(`- ${m}`));
  process.exit(1);
}

console.log('Asset verification passed.');
