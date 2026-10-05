#!/usr/bin/env python3
"""生成 wire v3 服务端测试用的 RFC 9180 官方向量文件（可复现、无手誊 hex）。

来源：HPKE 工作组官方向量仓库（JSON 的 canonical 位置，
RFC 9180 §8.4 指定的发布渠道）：
    https://raw.githubusercontent.com/cfrg/draft-irtf-cfrg-hpke/master/test-vectors.json
    （repo: github.com/cfrg/draft-irtf-cfrg-hpke，commit b1f7cb0cdeab6906c61b3d6574e8bdfdbe1cd3fb）
生成时把该 commit 的 JSON SHA-256 与本脚本一起写进输出文件 header，供审计。
hpkewg/test-vectors 仓库不存在（404）；官方向量的权威 JSON 就在 cfrg draft 仓库
（draft 文本 §8.4 的 TestVectors 指向处），本脚本据此下载。

用法（在项目根）：
    python3 core-api/src/test/resources/wire-v3/gen_vectors.py
输出：同目录 wire-v3-rfc-vectors.json。重跑 diff 为空（内容确定性：只含选定 suite/条目）。

抽取规则（本项目 wire v3 用 DHKEM(X25519,HKDF-SHA256)=0x0020 / HKDF-SHA256=0x0001 /
AES-256-GCM=0x0002）：
- 本 suite 全量 4 条（mode 0/1/2/3）：base mode（mode=0）作服务端测试主向量
  （固定 ikm 可复现 Seal/Open/Export 逐字节断言）；mode 1–3 仅留档对照
  （本实现只支持 base，测试不消费这三条）；
- 每条只保留 seal/open/export 验证所需字段（不留 skRm/skEm 之外的 ikm 冗余）。
"""
import hashlib
import json
import urllib.request

SOURCE_URL = "https://raw.githubusercontent.com/cfrg/draft-irtf-cfrg-hpke/master/test-vectors.json"
SOURCE_REPO = "github.com/cfrg/draft-irtf-cfrg-hpke"
SOURCE_COMMIT = "b1f7cb0cdeab6906c61b3d6574e8bdfdbe1cd3fb"
OUT = "wire-v3-rfc-vectors.json"

KEM, KDF, AEAD = 0x20, 0x01, 0x02
KEEP_FIELDS = [
    "mode", "kem_id", "kdf_id", "aead_id", "info",
    "ikmR", "ikmE", "skRm", "skEm", "pkRm", "pkEm",
    "enc", "shared_secret", "base_nonce", "exporter_secret", "key",
    "encryptions", "exports",
]


def main() -> None:
    with urllib.request.urlopen(SOURCE_URL, timeout=60) as resp:
        raw = resp.read()
    source_sha = hashlib.sha256(raw).hexdigest()
    vectors = json.loads(raw)
    sel = [v for v in vectors
           if v.get("kem_id") == KEM and v.get("kdf_id") == KDF
           and v.get("aead_id") == AEAD]
    assert len(sel) == 4, f"expect 4 vectors of our suite (modes 0..3), got {len(sel)}"
    out = {
        "source": {
            "url": SOURCE_URL,
            "repo": SOURCE_REPO,
            "commit": SOURCE_COMMIT,
            "sha256": source_sha,
            "generator_script": "gen_vectors.py",
        },
        "suite": {"kem": KEM, "kdf": KDF, "aead": AEAD, "mode": 0},
        "vectors": [{k: v[k] for k in KEEP_FIELDS} for v in sel],
    }
    text = json.dumps(out, indent=1) + "\n"
    with open(OUT, "w", encoding="utf-8") as f:
        f.write(text)
    print(f"source sha256: {source_sha}")
    print(f"wrote {OUT} with {len(sel)} vectors (modes {[v['mode'] for v in sel]})")


if __name__ == "__main__":
    main()
