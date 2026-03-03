const fs = require('fs');
const { expectedAssets } = require('./assets-contract');

const missing = expectedAssets.filter((file) => !fs.existsSync(file));
if (missing.length > 0) {
  console.error('Missing built assets:');
  missing.forEach((m) => console.error(`- ${m}`));
  process.exit(1);
}

console.log('Asset verification passed.');
