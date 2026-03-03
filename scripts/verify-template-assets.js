const fs = require('fs');
const path = require('path');

const roots = [
  'src/main/resources/templates/admin',
  'src/main/resources/templates/user'
];

function listHtmlFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...listHtmlFiles(full));
    } else if (entry.isFile() && full.endsWith('.html') && !full.endsWith('-bak.html')) {
      out.push(full);
    }
  }
  return out;
}

const files = roots.flatMap(listHtmlFiles);
const errors = [];

for (const file of files) {
  const content = fs.readFileSync(file, 'utf8');
  if (content.includes('/vendor/')) {
    errors.push(`${file}: contains forbidden /vendor/ reference`);
  }
  if (!content.includes('/assets/app.css')) {
    errors.push(`${file}: missing /assets/app.css reference`);
  }
  if (!content.includes('/assets/app.js')) {
    errors.push(`${file}: missing /assets/app.js reference`);
  }
}

if (errors.length > 0) {
  console.error('Template asset verification failed:');
  errors.forEach((e) => console.error(`- ${e}`));
  process.exit(1);
}

console.log('Template asset verification passed.');
