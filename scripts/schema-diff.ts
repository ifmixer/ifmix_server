/**
 * GraphQL Schema 版本管理 + 语义 Diff。
 *
 * 用法:
 *   pnpm run schema:snapshot                    # 存档当前 schema 版本
 *   pnpm run schema:diff                        # 对比当前 vs 最近存档
 *   pnpm run schema:diff 20260823_105833        # 对比当前 vs 指定版本
 *   pnpm run schema:diff 20260823_105833 20260823_110000  # 对比两个指定版本 (old new)
 */
import { execSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, symlinkSync, unlinkSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");
const SCHEMA_DIR = join(ROOT, "core-api/src/main/resources/schema");
const VERSIONS_DIR = join(ROOT, "scripts/schema-versions");
const DIFFS_DIR = join(VERSIONS_DIR, "diffs");
const MERGED_FILE = "all_schema.graphql";
const MAX_VERSIONS = 10;

// ─── Helpers ──────────────────────────────────────────────────────────────────

function mergeSchemaFiles(dir: string): string {
  const lines: string[] = [];
  for (const sub of ["common", "customer"]) {
    const subDir = join(dir, sub);
    if (!existsSync(subDir)) continue;
    for (const file of readdirSync(subDir).filter((f) => f.endsWith(".graphqls")).sort()) {
      lines.push(`# --- ${sub}/${file} ---`);
      lines.push(readFileSync(join(subDir, file), "utf-8"));
      lines.push("");
    }
  }
  return lines.join("\n");
}

function getVersionDirs(): string[] {
  if (!existsSync(VERSIONS_DIR)) return [];
  return readdirSync(VERSIONS_DIR)
    .filter((d) => /^\d{8}_\d{6}$/.test(d))
    .sort()
    .reverse();
}

function timestamp(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
}

function runInspectorDiff(oldFile: string, newFile: string): string {
  try {
    const result = execSync(
      `npx --yes @graphql-inspector/cli diff "${oldFile}" "${newFile}" --rule ignoreDescriptionChanges`,
      { encoding: "utf-8", cwd: import.meta.dirname, stdio: ["pipe", "pipe", "pipe"] }
    );
    return result || "✅ No changes detected.";
  } catch (e: any) {
    // graphql-inspector exits non-zero when breaking changes found
    return e.stdout || e.message || "diff failed";
  }
}

function writeDiff(oldVersion: string, newVersion: string, content: string): string {
  mkdirSync(DIFFS_DIR, { recursive: true });
  const filename = `${oldVersion}-to-${newVersion}.diff`;
  const filepath = join(DIFFS_DIR, filename);
  writeFileSync(filepath, content);
  // Update latest.diff symlink
  const latestDiff = join(DIFFS_DIR, "latest.diff");
  try { unlinkSync(latestDiff); } catch {}
  symlinkSync(filename, latestDiff);
  return filepath;
}

function updateLatestVersionLink(version: string) {
  const link = join(VERSIONS_DIR, "latest_version");
  try { unlinkSync(link); } catch {}
  symlinkSync(version, link);
}

function getMergedPath(version: string): string {
  return join(VERSIONS_DIR, version, MERGED_FILE);
}

// ─── Commands ─────────────────────────────────────────────────────────────────

function snapshot() {
  const ts = timestamp();
  const destDir = join(VERSIONS_DIR, ts);
  mkdirSync(destDir, { recursive: true });

  // Copy schema directory structure
  cpSync(SCHEMA_DIR, join(destDir, "schema"), { recursive: true });

  // Write merged SDL for diff
  const merged = mergeSchemaFiles(SCHEMA_DIR);
  writeFileSync(join(destDir, MERGED_FILE), merged);

  // Trim old versions
  const versions = getVersionDirs();
  for (const old of versions.slice(MAX_VERSIONS)) {
    rmSync(join(VERSIONS_DIR, old), { recursive: true });
    console.log(`🗑️  Deleted old version: ${old}`);
  }

  console.log(`📦 Schema snapshot saved: schema-versions/${ts}/`);

  // Update latest_version symlink
  updateLatestVersionLink(ts);

  // Auto-diff with previous if exists
  const updatedVersions = getVersionDirs();
  if (updatedVersions.length >= 2) {
    const prev = updatedVersions[1];
    doDiff(prev, ts);
  }
}

function doDiff(oldVersion: string, newVersion: string, newMergedPath?: string) {
  const oldMerged = getMergedPath(oldVersion);
  const newMerged = newMergedPath || getMergedPath(newVersion);

  if (!existsSync(oldMerged)) {
    console.log(`❌ Version not found: ${oldVersion}`);
    process.exit(1);
  }
  if (!existsSync(newMerged)) {
    console.log(`❌ Version not found: ${newVersion}`);
    process.exit(1);
  }

  console.log(`\n═══════════════════════════════════════════════════════`);
  console.log(`📊 Schema Diff: ${oldVersion} → ${newVersion}`);
  console.log(`═══════════════════════════════════════════════════════`);

  const result = runInspectorDiff(oldMerged, newMerged);
  console.log(result);

  const diffFile = writeDiff(oldVersion, newVersion, `# ${oldVersion} → ${newVersion}\n\n${result}`);
  console.log(`📝 Diff saved: ${diffFile}`);
}

function diff(args: string[]) {
  const versions = getVersionDirs();

  if (args.length === 0) {
    // Diff current vs latest snapshot
    if (versions.length < 1) {
      console.log("❌ No snapshots found. Run `pnpm run schema:snapshot` first.");
      process.exit(1);
    }
    const latest = versions[0];
    // Write current merged to temp
    const currentMerged = join(VERSIONS_DIR, "_current.graphql");
    writeFileSync(currentMerged, mergeSchemaFiles(SCHEMA_DIR));
    doDiff(latest, "current", currentMerged);
    rmSync(currentMerged);
  } else if (args.length === 1) {
    // Diff current vs specified version
    const target = args[0];
    const currentMerged = join(VERSIONS_DIR, "_current.graphql");
    writeFileSync(currentMerged, mergeSchemaFiles(SCHEMA_DIR));
    doDiff(target, "current", currentMerged);
    rmSync(currentMerged);
  } else {
    // Diff two specified versions (old, new)
    doDiff(args[0], args[1]);
  }
}

// ─── CLI ──────────────────────────────────────────────────────────────────────

const args = process.argv.slice(2);
const command = args[0];

if (command === "--snapshot") {
  snapshot();
} else if (command === "--diff") {
  diff(args.slice(1));
} else if (command === "--list") {
  const versions = getVersionDirs();
  if (versions.length === 0) {
    console.log("No snapshots found.");
  } else {
    console.log(`📋 Schema versions (${versions.length}):`);
    versions.forEach((v) => console.log(`  ${v}`));
  }
} else {
  console.log(`Usage:
  pnpm run schema:snapshot                              # Save current schema version
  pnpm run schema:diff                                  # Diff current vs latest snapshot
  pnpm run schema:diff -- 20260823_105833               # Diff current vs specified version
  pnpm run schema:diff -- 20260823_105833 20260823_110000  # Diff two versions (old new)
  pnpm run schema:list                                  # List all snapshots`);
}
