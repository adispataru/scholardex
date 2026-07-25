/**
 * The bundle at src/main/resources/static/assets/app.js is a COMMITTED build artifact, and nothing in the
 * Gradle build regenerates it. So a change to frontend/src that is committed without running `npm run build`
 * ships the previous bundle: the source looks localized in review, the app still serves English, and every
 * Java test passes. That is exactly how H87's workspace-JS localization went out inert — twice.
 *
 * Worse, `npm run build` is the only thing that parses these modules at all, so a syntax error (a single-quoted
 * string with `${...}` in it, say) sits in the tree undetected until someone happens to rebuild.
 *
 * This check rebuilds into a temp dir and byte-compares. It fails on BOTH failure modes: a stale bundle and a
 * bundle that cannot be produced. Fix either one with `npm run build`.
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

let esbuild;
try {
    esbuild = require('esbuild');
} catch (_err) {
    // Mirrors build-assets.js: without esbuild the committed bundle is all we have, so freshness is unknowable.
    console.warn('esbuild not installed; skipping bundle freshness check.');
    process.exit(0);
}

const COMMITTED = 'src/main/resources/static/assets/app.js';
const outdir = fs.mkdtempSync(path.join(os.tmpdir(), 'scholardex-assets-'));

esbuild
    .build({
        entryPoints: ['frontend/src/app.js'],
        bundle: true,
        minify: true,
        sourcemap: true,
        outdir,
        entryNames: 'app',
        assetNames: 'assets/[name]-[hash]',
        loader: {
            '.woff': 'file', '.woff2': 'file', '.ttf': 'file', '.eot': 'file',
            '.svg': 'file', '.png': 'file', '.jpg': 'file', '.gif': 'file'
        }
    })
    .then(() => {
        const fresh = fs.readFileSync(path.join(outdir, 'app.js'));
        const committed = fs.existsSync(COMMITTED) ? fs.readFileSync(COMMITTED) : Buffer.alloc(0);
        fs.rmSync(outdir, { recursive: true, force: true });
        if (!fresh.equals(committed)) {
            console.error(`Stale bundle: ${COMMITTED} does not match a fresh build of frontend/src.`);
            console.error('Anything you changed under frontend/src is NOT in the served bundle.');
            console.error('\nRun:  npm run build   — then commit the regenerated assets.');
            process.exit(1);
        }
        console.log('Bundle freshness verified: the committed app.js matches frontend/src.');
    })
    .catch((err) => {
        fs.rmSync(outdir, { recursive: true, force: true });
        console.error('The frontend bundle does not build — the committed app.js cannot be trusted.\n');
        console.error(err.message || err);
        process.exit(1);
    });
