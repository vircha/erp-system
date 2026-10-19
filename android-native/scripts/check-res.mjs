#!/usr/bin/env node
/**
 * 构建前自检：Android res 资源完整性
 *
 * 🔴 为什么需要它（2026-10-19 真实事故）：
 *   .gitignore 里的 `*.png` 把 25 个应用图标排除了。
 *   本机 `assembleRelease` 照常成功（文件在磁盘上），
 *   但 GitHub Actions 检出仓库后没有这些 png，
 *   直接报 `AAPT: error: resource drawable/ic_launcher_background not found`。
 *
 *   也就是说：本地过 ≠ CI 过。这个脚本把「CI 才会暴露的问题」
 *   提前到本地，在提交前就报错。
 *
 * 用法：node scripts/check-res.mjs
 * 退出码 0 = 通过，1 = 有缺失
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
// 脚本位于 <repo>/android-native/scripts/，res 在同级上两层的 app/src/main/res
const ROOT = path.join(__dirname, '..', 'app', 'src', 'main', 'res');

if (!fs.existsSync(ROOT)) {
  console.error('✗ 找不到 res 目录：%s', ROOT);
  console.error('  脚本位置：%s', __dirname);
  process.exit(1);
}
const problems = [];

// ---------- 1. 扫描所有 XML 里的 @drawable/ @mipmap/ @string/ @color/ 引用 ----------
const RES_DIRS = ['drawable', 'mipmap', 'string', 'color', 'style'];
const files = [];
(function walk(dir) {
  if (!fs.existsSync(dir)) return;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p);
    else files.push(p);
  }
})(ROOT);

/**
 * 某个资源名在 res/ 下是否有对应文件。
 *
 * 🔴 Android 的资源目录是**并列**的：`drawable/`、`drawable-hdpi/`、`drawable-xhdpi/` …
 *   不是 `drawable/hdpi/` 这种嵌套。一开始按嵌套写，导致所有资源都误报缺失。
 *   所以这里要在 res/ 下找出所有 `<kind>` 或 `<kind>-<qualifier>` 前缀的目录。
 */
function hasResource(kind, name) {
  const exts = ['.png', '.xml', '.webp', '.9.png'];
  let dirs = [];
  try {
    dirs = fs.readdirSync(ROOT, { withFileTypes: true });
  } catch {
    return false;
  }
  for (const e of dirs) {
    if (!e.isDirectory()) continue;
    // drawable / drawable-hdpi / drawable-anydpi-v26 都算
    if (e.name !== kind && !e.name.startsWith(kind + '-')) continue;
    const base = path.join(ROOT, e.name, name);
    if (exts.some((x) => fs.existsSync(base + x))) return true;
  }
  return false;
}

const refRx = /@(drawable|mipmap|string|color)\/([A-Za-z0-9_]+)/g;
for (const f of files) {
  if (!f.endsWith('.xml')) continue;
  const txt = fs.readFileSync(f, 'utf8');
  for (const m of txt.matchAll(refRx)) {
    const [, kind, name] = m;
    // 框架自带的资源（android: 命名空间）不在 res/ 里
    if (!hasResource(kind, name)) {
      problems.push(
        `资源缺失  ${path.relative(ROOT, f).replace(/\\/g, '/')} 引用了 @${kind}/${name}，但 res/ 下找不到`
      );
    }
  }
}

// ---------- 2. 检查自适应图标三件套（v26 变体必须有前景/背景/单色）----------
for (const mip of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
  const v26 = path.join(ROOT, 'mipmap-anydpi-v26', mip);
  if (!fs.existsSync(v26)) continue;
  const txt = fs.readFileSync(v26, 'utf8');
  for (const need of ['ic_launcher_background', 'ic_launcher_foreground', 'ic_launcher_monochrome']) {
    if (txt.includes('@drawable/' + need) && !hasResource('drawable', need)) {
      problems.push(`自适应图标不完整  ${mip} 引用 @drawable/${need}，但 drawable/ 下没有任何密度的文件`);
    }
  }
}

// ---------- 3. 关键检查：Android 图标是否真的会进仓库 ----------
// 🔴🔴 这项要查两种情况，缺一不可：
//   ① 未跟踪文件被 .gitignore 排除（git ls-files --others --ignored）
//   ② 文件压根不在 git 索引里（git ls-files 对不上）
//   只查① 会漏掉「本地已被 git add 过、后来改了 .gitignore」的假象 ——
//   因为文件已在索引里，git 就不再把它当「被忽略」了。
const { execFileSync } = await import('node:child_process');
const git = (...args) => {
  try {
    return execFileSync('git', args, {
      encoding: 'utf8',
      cwd: path.join(__dirname, '..', '..'),
      stdio: ['ignore', 'pipe', 'ignore'],
    })
      .split('\n')
      .filter(Boolean);
  } catch {
    return null; // 不在 git 仓库里
  }
};

// 归一化成仓库根相对路径（Windows 上 git 用 /，本脚本拼路径用 \）
const toRepoRel = (abs) => {
  const repoRoot = path.join(__dirname, '..', '..');
  return path.relative(repoRoot, abs).replace(/\\/g, '/');
};

const ignored = git('ls-files', '--others', '--ignored', '--exclude-standard', '--', toRepoRel(ROOT));
if (ignored && ignored.length) {
  const imgs = ignored.filter((p) => /\.(png|webp)$/.test(p));
  if (imgs.length) {
    problems.push(
      `🔴 有 ${imgs.length} 个 res 图片被 .gitignore 排除（本地构建能过、CI 必失败）：\n` +
        imgs
          .slice(0, 8)
          .map((p) => `      ${p}`)
          .join('\n') +
        (imgs.length > 8 ? `\n      ... 还有 ${imgs.length - 8} 个` : '') +
        `\n      修法：.gitignore 里放行 !android-native/app/src/main/res/**`
    );
  }
}

// ② 更关键：res 下应有的图片，是否都在 git 索引里
const tracked = git('ls-files', '--', toRepoRel(ROOT));
if (tracked) {
  const trackedSet = new Set(tracked);
  const shouldBeTracked = files.filter(
    (f) => /\.(png|webp|xml)$/.test(f) && !f.includes(`${path.sep}build${path.sep}`)
  );
  const missing = shouldBeTracked.filter((f) => !trackedSet.has(toRepoRel(f)));
  if (missing.length) {
    problems.push(
      `🔴 有 ${missing.length} 个 res 资源不在 git 索引里（别人克隆后会缺文件）：\n` +
        missing
          .slice(0, 8)
          .map((p) => `      ${toRepoRel(p)}`)
          .join('\n') +
        (missing.length > 8 ? `\n      ... 还有 ${missing.length - 8} 个` : '') +
        `\n      修法：git add -f android-native/app/src/main/res/`
    );
  }
}

// ---------- 4. 未被任何 XML 引用的 PNG（提示级，容易是遗留垃圾）----------
const referenced = new Set();
for (const f of files) {
  if (!f.endsWith('.xml')) continue;
  const txt = fs.readFileSync(f, 'utf8');
  for (const m of txt.matchAll(refRx)) referenced.add(path.basename(m[2]));
}
const orphans = [
  ...new Set(
    files
      .filter((f) => /\.(png|webp)$/.test(f))
      .map((f) => path.basename(f, path.extname(f)))
  )
].filter((n) => !referenced.has(n));

console.log('res 自检：扫描 %d 个资源文件', files.length);
if (orphans.length) {
  console.log('提示：%d 种图片未被任何 XML 引用（可能是遗留）：%s', orphans.length, orphans.join(', '));
}

if (problems.length) {
  console.error('\n✗ res 资源自检未通过（%d 项）：\n', problems.length);
  for (const p of problems) console.error('  · ' + p);
  console.error('\n这些问题在 CI 上才会暴露为 AAPT 错误，必须现在修。');
  process.exit(1);
}
console.log('✓ res 资源自检通过');