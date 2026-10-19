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
// 🔴 必须带 `--cached`（或不给 --），且**不带路径限制**，否则只拿到子集。
//   一开始写成 git ls-files -- <res路径>，结果第 6 项拿它去比对全仓库，
//   结果把 73 个正常文件全误报成「未入库」。
const tracked = git('ls-files', '--cached');
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

// ---------- 4. 关键检查：.gitignore 的行尾注释（会让规则整条失效）----------
// 🔴 .gitignore **不支持行尾注释**（和 .gitattributes 一样）。
//   写 `uploads/    # 意见反馈附件` 时，git 会把 `# 意见反馈附件`
//   当成路径的一部分去匹配，结果这条规则**完全不生效** ——
//   本该忽略的目录照样会被提交（或者反过来，该入库的源码被忽略）。
//   2026-10-19 真实踩过：/uploads/ 因带行尾注释而失效。
const repoRoot = path.join(__dirname, '..', '..');
const giPath = path.join(repoRoot, '.gitignore');
if (fs.existsSync(giPath)) {
  const lines = fs.readFileSync(giPath, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    const t = line.trim();
    if (!t || t.startsWith('#')) return; // 整行注释是合法的
    const m = t.match(/^(\S+)\s+#/); // 规则后面跟 # 且前面有空白
    if (m) {
      problems.push(
        `.gitignore 第 ${i + 1} 行有行尾注释，**该规则会整条失效**：` +
          `\n      ${line}` +
          `\n      注释必须独占一行（.gitignore 与 .gitattributes 共同的坑）`
      );
    }
  });
}

// ---------- 5. 未被任何 XML 引用的 PNG（提示级，容易是遗留垃圾）----------
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

// ---------- 6. 全仓库源码完整性：磁盘上有但 git 没收录 ----------
// 🔴 这是 2026-10-19 两个 bug 的通用探测手段：
//   ① *.png 吞掉 25 个图标 → AAPT 报 resource not found
//   ② data/ 吞掉 4 个 .kt  → Kotlin 报 Unresolved reference
//   两者症状完全不同，但**根因完全一样**：.gitignore 误伤源码。
//   所以这里统一扫一遍：凡是源码/资源类文件，磁盘上有就必须已入库。
if (tracked) {
  const trackedSet = new Set(tracked.map((p) => p));
  const SRC_EXT = /\.(kt|kts|java|js|mjs|cjs|html|css|xml|json|sh|yml|yaml|gradle|properties|sql|png|webp|svg|jar)$/;
  const SKIP = new Set(['node_modules', '.git', 'build', '.gradle', 'local.properties']);

  //🔴 白名单：这些文件**必须**保持在 git 之外，扫到了不能报错
  //   keystore.properties = 签名密钥口令；local.properties = 服务器地址与签名路径
  const MUST_STAY_OUT = new Set(['keystore.properties', 'keystore.properties.example']);
  const isExempt = (r) => {
    const base = path.basename(r);
    return MUST_STAY_OUT.has(base) || /\.(jks|keystore)$/.test(base) || /\.(pem|key|crt|p12|pfx)$/.test(base);
  };

  const walkRepo = (dir, rel = '') => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      if (SKIP.has(e.name)) continue;
      const abs = path.join(dir, e.name);
      const r = (rel ? rel + '/' : '') + e.name;
      if (e.isDirectory()) {
        // 数据/证书目录本来就该被忽略，跳过以免误报
        if (/^(data|backup|backups|uploads|tls|local\.properties)$/.test(e.name)) continue;
        walkRepo(abs, r);
      } else if (e.isFile() && SRC_EXT.test(e.name)) {
        if (isExempt(r)) continue;
        if (!trackedSet.has(r)) untracked.push(r);
      }
    }
  };

  const untracked = [];
  try {
    walkRepo(repoRoot);
  } catch {
    /* 目录不可读就跳过 */
  }
  if (untracked.length) {
    problems.push(
      `🔴 有 ${untracked.length} 个源码/资源文件在磁盘上但**没有进 git 仓库**` +
        `（本机能过、别人克隆后会缺文件）：\n` +
        untracked
          .slice(0, 10)
          .map((p) => `      ${p}`)
          .join('\n') +
        (untracked.length > 10 ? `\n      ... 还有 ${untracked.length - 10} 个` : '') +
        `\n      修法：git add -f <这些文件>，并检查 .gitignore 哪条规则误伤了它们`
    );
  }

  // 🔴 反向检查：已入库但磁盘上不存在（被人手删了、或 .gitignore 收得太紧
  //   导致 git rm --cached 之后又被忽略回来）。只查源码/资源类文件。
  const vanished = [...trackedSet]
    .filter((r) => SRC_EXT.test(r) && !isExempt(r))
    .filter((r) => !fs.existsSync(path.join(repoRoot, r)))
    .filter((r) => !/^android-native\/app\/build\//.test(r)); // 产物目录例外
  if (vanished.length) {
    problems.push(
      `🔴 有 ${vanished.length} 个文件在 git 索引里但磁盘上不存在` +
        `（提交记录与工作区不一致，容易漏改后推上去）：\n` +
        vanished
          .slice(0, 10)
          .map((p) => `      ${p}`)
          .join('\n') +
        (vanished.length > 10 ? `\n      ... 还有 ${vanished.length - 10} 个` : '')
    );
  }
}

console.log('res 自检：扫描 %d 个资源文件', files.length);
if (orphans.length) {
  console.log('提示：%d 种图片未被任何 XML 引用（可能是遗留）：%s', orphans.length, orphans.join(', '));
}

if (problems.length) {
  console.error('\n✗ 自检未通过（%d 项）：\n', problems.length);
  for (const p of problems) console.error('  · ' + p);
  console.error('\n这些问题在 CI 上才会暴露，必须现在修。');
  process.exit(1);
}
console.log('✓ res 资源与仓库完整性自检通过');