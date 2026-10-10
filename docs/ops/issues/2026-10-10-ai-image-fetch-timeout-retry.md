# issue：agnes-3.0-flash 经常「图片加载失败」而 2.5 几乎不报（`AI rejected the request`）

- **日期**：2026-10-10
- **状态**：已修复（加重试），待发布观察
- **触发**：`Scan AI call failed` WARN + `ApiError: AI rejected the request (invalid image or input)`，
  用户反馈 agnes-3.0-flash 比 2.5-flash 高发
- **根因**：图片以 URL 交给 provider，由 **provider 回源到我们的对象存储下载**。
  下载超时/抖动时 provider 返回 **400**（不是超时、不是 5xx），报文里写着
  `An exception occurred while loading IMAGE data at index 0: … Timed out while downloading media URL: …`。
  而代码把所有 400/422 一律当「不可重试的请求错误」→ 直接抛 ApiError，一次都不重试。
  **这是瞬态错误（图片没坏、key 也没坏），重试有实际收益**。

---

## 一、生产证据

`/data/app/log/core-api` 里同一整天只有 3 条 `non-retryable 400/422`，模型**全部**是 `agnes-3.0-flash`
（与用户"2.5 几乎没遇到"的观察一致），三者的 message 形态相同：

```
400: ***.BadRequestError: OpenAIException - {"object":"error","message":
  "An exception occurred while loading IMAGE data at index 0:
   Error while loading data ImageData(url='https://***') Timed out while downloading media URL: https://***"}
```

配套的另外两条日志：
- `ERROR AI rejected request (non-retryable 400/422) — abort without cooldown/retry`（SpringAiScanRunner）
- `WARN Scan AI call failed. projectId=… scanId=…`（ScanTaskService）

### 为什么重试是安全的

图片 URL 来自 `objectStorage.getPublicUrl("ugc", imageKey)`，**不是** presigned URL——
它是 R2 公开桶 + 自定义域（`https://u1.ifmix.com/<key>`）拼出来的**永久公开地址**，
没有签名参数、没有 expiry、无 Referer/IP/UA 限制。实测重复 GET 始终 200。
因此重复下载失败不会是 URL 过期，重试次数再多 URL 也不会失效。

> 顺带纠正一处过时注释：`ScanDto.ScanMediaItem.imageUrl` 的注释写的是"预签名下载 URL"，实际是公开 URL。

## 二、修复（2026-10-10）

改 `modules/ai/service/SpringAiScanRunner.kt`：

1. 新增 `isTransientImageFetchFailure(e)`：**先要求命中类型化 400/422**，再要求 message 含
   `loading image data` / `error while loading data` / `downloading media url` / `failed to download image`。
   两道门槛缺一不可——避免普通超时/错误因为路径里带了 "media URL" 字样就被误判。
2. 在 catch 里**先于** `isNonRetryableRequestError` 判定：命中则换 key 重试，独立上界 `MAX_IMAGE_FETCH_ATTEMPTS = 2`
   （比 `MAX_ATTEMPTS_PER_MODEL=4` 小，因为每次失败 provider 要先跑完图片下载超时才返回，放满会把 600s deadline 耗光）。
3. **不冷却、不禁用 key**——这是 provider 回源失败，key 没有任何过错；
   换 key 只是让它走另一个出口。
4. 重试耗尽才抛 `INVALID_REQUEST`，message 从含糊的 "invalid image or input" 改为
   **"AI could not load the image (upstream fetch failed)"**，与真正的坏图片区分开。
5. 真坏图片的 400（如 `Unsupported image format`）维持原语义：一次即止、不冷却、不重试。

测试 `SpringAiScanRunnerImageFetchRetryTest`（4 个用例，全过）：

| 用例 | 断言 |
|---|---|
| 图片拉取失败重试后成功 | 返回正常结果，`call()` 2 次，**keyStore 既未冷却也未禁用** |
| 一直失败到上界 | `INVALID_REQUEST` + 新 message，`call()` 恰好 2 次（不是 4） |
| 真坏图片 400 | `AI rejected the request (invalid image or input)`，`call()` 只有 1 次 |
| 非 400 但 message 含 "downloading media URL" | **不**被劫持，走正常 timeout 路径并冷却 |

## 三、如果还复发，该看什么

重试是把"一次抖动"变成"两次机会"，掩盖不了持续性问题。连续失败时要查的是：

1. **provider 回源能力**：同一时段是否大量图片都失败（可能是对端 downloader 整体超时，与具体图无关）。
2. **对象存储侧**：`u1.ifmix.com`（Cloudflare → R2 `upro`）当时是否可达、是否有地域性抖动：
   ```bash
   curl -sS -o /dev/null -w '%{http_code} %{time_total}s\n' 'https://u1.ifmix.com/<实际 key>'
   ```
3. **图片本身**：体积过大（几 MB 以上）会显著拉长 provider 的下载时间，超时概率随之上升——
   必要时在客户端/上传侧压一下分辨率。
4. **模型差异**：3.0 比 2.5 容易触发，若持续高发且重试覆盖不住，考虑把 2.5 配到
   `app.ai.model-fallback-order`（当前为空，即不做模型降级）。

注意日志会滚动，长期趋势要看 `error.log` / `Scan AI call failed` 的**按天计数**，
别指望几周前的 WARN 还在（见通用手册 §五）。
