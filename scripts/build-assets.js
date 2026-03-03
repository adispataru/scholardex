const fs = require('fs');
const { expectedAssets } = require('./assets-contract');

function validatePrebuiltAssets() {
  const missing = expectedAssets.filter((file) => !fs.existsSync(file));
  if (missing.length > 0) {
    console.error('Missing assets and esbuild is unavailable:');
    missing.forEach((m) => console.error(`- ${m}`));
    process.exit(1);
  }
  console.warn('esbuild not installed; using committed prebuilt assets.');
}

let esbuild;
try {
  esbuild = require('esbuild');
} catch (_err) {
  validatePrebuiltAssets();
  process.exit(0);
}

esbuild
  .build({
    entryPoints: ['frontend/src/app.js'],
    bundle: true,
    minify: true,
    sourcemap: true,
    outdir: 'src/main/resources/static/assets',
    entryNames: 'app',
    assetNames: 'assets/[name]-[hash]',
    loader: {
      '.woff': 'file',
      '.woff2': 'file',
      '.ttf': 'file',
      '.eot': 'file',
      '.svg': 'file',
      '.png': 'file',
      '.jpg': 'file',
      '.gif': 'file'
    }
  })
  .catch((err) => {
    console.error(err);
    process.exit(1);
  });
