#!/usr/bin/env python3
"""
生成 JWT 签发用的 Ed25519 密钥对（JWK 格式），供 AUTH_JWT_PRIVATE_KEY 使用。

用法:
  python3 scripts/gen_jwt_key.py                 # 随机 kid
  python3 scripts/gen_jwt_key.py --kid prod-2026  # 指定 kid

输出:
  1) PRIVATE JWK（含 d+x）—— 填入 .env.prod 的 AUTH_JWT_PRIVATE_KEY（整段一行，勿泄露）。
  2) PUBLIC JWK（仅 x）—— 服务端会自动从私钥派生并经 GET /.well-known/jwks 对外发布，
     这里打印仅供核对/离线分发。

说明:
  - 服务端 AuthJwtKeys 用 nimbus OctetKeyPair.parse() 解析私钥；公钥 = signingKey.toPublicJWK()。
  - 重新生成 = 换 key：所有旧 access token 立即验签失败（客户端需重新登录/refresh）。
  - kid 用于多钥轮换：换钥时新老 kid 并存一段时间可平滑过渡（当前 v1 单钥）。
  - 依赖: cryptography（pip install cryptography）。

安全:
  - 私钥只放 .env.prod / 服务器 /opt/app/env（640 root:app），不提交 git、不进日志。
"""
import argparse
import base64
import json
import os
import sys


def b64u(b: bytes) -> str:
    """base64url 无填充（JWK 约定）。"""
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 Ed25519 JWK（JWT 签发私钥）")
    ap.add_argument("--kid", default=None, help="key id（默认随机 prod-XXXX）")
    args = ap.parse_args()

    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
        from cryptography.hazmat.primitives import serialization
    except ModuleNotFoundError:
        print("缺少依赖：pip install cryptography", file=sys.stderr)
        return 1

    priv = Ed25519PrivateKey.generate()
    d = priv.private_bytes(
        serialization.Encoding.Raw,
        serialization.PrivateFormat.Raw,
        serialization.NoEncryption(),
    )
    x = priv.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )

    kid = args.kid or ("prod-" + b64u(os.urandom(6)))
    base = {"kty": "OKP", "crv": "Ed25519", "kid": kid, "use": "sig"}
    private_jwk = {**base, "d": b64u(d), "x": b64u(x)}
    public_jwk = {**base, "x": b64u(x)}

    compact = lambda o: json.dumps(o, separators=(",", ":"))

    print("=== PRIVATE JWK（填入 AUTH_JWT_PRIVATE_KEY，整段一行，勿泄露）===")
    print(compact(private_jwk))
    print()
    print("=== .env 行 ===")
    print("AUTH_JWT_PRIVATE_KEY=" + compact(private_jwk))
    print()
    print("=== PUBLIC JWK（仅核对；实际由 /.well-known/jwks 自动发布）===")
    print(compact(public_jwk))
    print(f"\nkid = {kid}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
