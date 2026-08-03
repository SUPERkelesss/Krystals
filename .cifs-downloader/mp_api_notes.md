# Materials Project API 配置经验

> 本文档总结 `download_cifs.py` 中调用 Materials Project (MP) API 的关键经验，每条附原文代码段（行号对应 `download_cifs.py`）。
> 适用场景：批量按化学式 + 空间群搜索 mp-id 并下载 CIF 文本。

---

## 0. 总体架构：双端点分离

MP 有两套端点，本脚本把它们分工拆开：

| 端点 | 用途 | 是否需要 key |
|---|---|---|
| `api.materialsproject.org`（新 API） | 搜索 mp-id、取 structure JSON | **需要** `X-API-KEY` |
| `legacy.materialsproject.org/rest/v1`（旧端点） | 下载 CIF 文本 | **不需要** key |

核心思路：把"搜索（耗配额、需 key）"和"下载 CIF（免费、无 key）"分离，省 API 额度。

```python
# download_cifs.py:36-37
MP_NEW = "https://api.materialsproject.org"
MP_LEGACY = "https://legacy.materialsproject.org/rest/v1"
```

---

## 1. 必须带浏览器风格 User-Agent，否则被 Cloudflare 拦截

MP 的 CDN（Cloudflare）会拒绝无 UA 的默认 urllib 请求，返回 `403 error 1010`。因此 `http_get` 强制注入 `User-Agent: Mozilla/5.0`，同时声明 `Accept` 和 `Accept-Encoding`。

```python
# download_cifs.py:132-139
def http_get(url, headers=None, timeout=60):
    # MP 的 CDN(Cloudflare) 会拒绝无 User-Agent 的默认 urllib 请求(403 error 1010)，
    # 故必须带上浏览器风格的 User-Agent。
    base = {"User-Agent": "Mozilla/5.0", "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate"}
    if headers:
        base.update(headers)
    req = urllib.request.Request(url, headers=base)
```

---

## 2. 手动处理 gzip/deflate 响应解压

因为用标准库 `urllib` 而非 `requests`，带 `Accept-Encoding: gzip, deflate` 后必须自己根据响应头 `Content-Encoding` 解压，否则拿到的是压缩字节流。错误分支同样要解压才能读到报错正文。

```python
# download_cifs.py:141-162
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = resp.read()
            ctype = resp.headers.get("Content-Type", "")
            enc = resp.headers.get("Content-Encoding", "")
        if enc == "gzip":
            import gzip
            data = gzip.decompress(data)
        elif enc == "deflate":
            import zlib
            data = zlib.decompress(data, -zlib.MAX_WBITS)
        return data, ctype
    except urllib.error.HTTPError as e:
        body = ""
        try:
            raw = e.read()
            if dict(e.headers).get("Content-Encoding", "") == "gzip":
                import gzip
                raw = gzip.decompress(raw)
            body = raw.decode("utf-8", "replace")
        except Exception:
            pass
        raise RuntimeError("HTTP %s %s\n%s" % (e.code, e.reason, body[:500])) from None
```

---

## 3. API key 三级加载：环境变量 > 文件 > 无

优先读 `MP_API_KEY` 环境变量（适合 CI / 服务器），其次读仓库内 `mp_api_key.txt` 明文文件（适合本地便携），都没有则返回 `None`，由调用方决定是否报错。

```python
# download_cifs.py:35, 165-172
KEY_FILE = os.path.join(ROOT, "mp_api_key.txt")

def load_api_key():
    env = os.environ.get("MP_API_KEY")
    if env and env.strip():
        return env.strip()
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    return None
```

---

## 4. 搜索：用 `_fields` 精简返回 + 空间群号锁定多晶型

新 API 搜索用 `/materials/summary/` 端点，两个关键技巧：

- **`_fields` 只取需要的字段**：减少传输量，明确拿到 `material_id / formula_pretty / symmetry / energy_above_hull / is_stable / nsites / density / volume`。
- **`spacegroup_number` 约束**：同一化学式常有多个多晶型，用空间群号锁定目标相，避免选错。
- **排序**：`is_stable` 降序在前，再按 `energy_above_hull` 升序，优先取稳定相。

```python
# download_cifs.py:178-192
def mp_search(formula, sg, api_key, limit=20):
    """返回候选 list，按 is_stable 降序、energy_above_hull 升序。"""
    params = {
        "formula": formula,
        "_fields": "material_id,formula_pretty,symmetry,energy_above_hull,is_stable,nsites,density,volume",
        "_per_page": str(limit),
    }
    if sg:
        params["spacegroup_number"] = str(sg)
    url = MP_NEW + "/materials/summary/?" + urllib.parse.urlencode(params)
    data, _ = http_get(url, headers={"X-API-KEY": api_key})
    obj = json.loads(data.decode("utf-8"))
    cands = obj.get("data", []) or []
    cands.sort(key=lambda d: (0 if d.get("is_stable") else 1, d.get("energy_above_hull") or 9e9))
    return cands
```

请求头带 key：

```python
data, _ = http_get(url, headers={"X-API-KEY": api_key})
```

---

## 5. 指定空间群无结果时不退化，宁可缺不要错相

`resolve_mp` 的策略：若指定了 `sg` 却搜不到候选，直接判 FAIL，**不退化为随机选一个相**，避免拿错多晶型。只有 `sg=None` 时才按 ehull 选最优。这保护了"必须取特定相"的语义。

```python
# download_cifs.py:363-381
def resolve_mp(item, api_key):
    """返回 (mpid, chosen_cand) 或 (None, None)。
    若指定了 sg 且无结果，视为未找到（不退化为随机相，避免选错多晶型）。
    仅当 sg=None 时按 ehull 选最优。"""
    k = item["key"]
    if k.get("mpid"):
        return k["mpid"], None
    try:
        cands = mp_search(k["formula"], k.get("sg"), api_key, limit=20)
    except Exception as e:
        item["_err"] = "search error: %s" % e
        return None, None
    if not cands:
        if k.get("sg"):
            item["_err"] = "no candidate with sg=%s" % k.get("sg")
            return None, None
        item["_err"] = "no candidate"
        return None, None
    return cands[0].get("material_id"), cands[0]
```

---

## 6. 下载 CIF：legacy 优先，新格式 mp-id 降级到 pymatgen

`mp_get_cif` 分两级：

1. **legacy 端点** `legacy.materialsproject.org/rest/v1/materials/{mpid}/cif`，无需 key，返回 conventional_standard CIF。但它**只认旧格式 `mp-数字` id**。
2. 对新格式 id（如 `mp-aaaabieb`）legacy 会失败，回退到**新 API 取 structure JSON，再用 pymatgen 的 `CifWriter` 本地对称化转 CIF**。

```python
# download_cifs.py:195-234
def mp_get_cif(mpid, api_key):
    """优先用 legacy 端点取 conventional_standard CIF；对新格式 mp-id 失败时
    回退到新 API 取 structure JSON 再用 pymatgen 转 CIF（symm 化）。"""
    # 1) legacy 端点（仅识别旧格式 mp-数字 id）
    try:
        url = "%s/materials/%s/cif" % (MP_LEGACY, mpid)
        data, ctype = http_get(url)
        if "json" in ctype.lower():
            obj = json.loads(data.decode("utf-8"))
            if obj.get("cif"):
                return obj["cif"], "legacy"
    except Exception as e:
        legacy_err = str(e)
    else:
        legacy_err = "no cif field"
    # 2) 新 API structure + pymatgen
    try:
        url = (MP_NEW + "/materials/core/?material_ids=%s&_fields=material_id,structure"
               % urllib.parse.quote(mpid))
        data, _ = http_get(url, headers={"X-API-KEY": api_key})
        obj = json.loads(data.decode("utf-8"))
        s = obj["data"][0]["structure"]
        text = structure_to_cif(s)
        if text:
            return text, "pymatgen"
    except Exception as e:
        raise RuntimeError("legacy(%s) + pymatgen(%s)" % (legacy_err[:120], str(e)[:120]))
    raise RuntimeError("legacy(%s)" % legacy_err[:120])


def structure_to_cif(struct_dict):
    """用 pymatgen 把 MP structure dict 转 CIF（对称化 conventional cell）。"""
    try:
        from pymatgen.core import Structure
        from pymatgen.io.cif import CifWriter
    except ImportError:
        return None
    struct = Structure.from_dict(struct_dict)
    cw = CifWriter(struct, symprec=0.1)
    return str(cw)
```

> 注意 `urllib.parse.quote(mpid)`：新格式 mp-id 含字母，拼进 URL query 时需转义。

---

## 7. 仅用标准库，pymatgen 按需 import

整个 HTTP 栈只用 `urllib / json / gzip / zlib`，不引入 `requests`。pymatgen 只在"新格式 id 转 CIF"和"手动构造相"时才 import，且包在 `try/except ImportError` 里，缺失时优雅降级返回 `None` 而非崩溃。降低部署依赖。

```python
# download_cifs.py:18-27  依赖区
import argparse
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error
```

---

## 8. Windows 控制台强制 UTF-8，避免 GBK 崩溃

脚本一启动就把 stdout/stderr 重包成 UTF-8 的 `TextIOWrapper`。因为输出含大量中文与化学式，Windows 默认 GBK 控制台会抛 `UnicodeEncodeError`。这是 Windows 下做中文输出的必经一步。

```python
# download_cifs.py:29-31
# 强制 stdout/stderr 用 UTF-8，避免 Windows GBK 控制台编码错误
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace", line_buffering=True)
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace", line_buffering=True)
```

---

## 9. 可重入 + CIF 有效性校验

下载前先检查目标文件是否存在且 `valid_cif`。已存在且有效就跳过，使脚本可反复运行不重复耗 API 额度。

校验函数的两条规则都踩过坑：
- 不能要求文件 `strip()` 后直接以 `data_` 开头——pymatgen 生成的 CIF 头部带 `# generated using pymatgen` 注释行，再接 `data_`。
- 因此只要求"含 `data_` 行"且"含 `_cell_length_a`"即可。

```python
# download_cifs.py:298-304
def valid_cif(text):
    if not text:
        return False
    s = text.strip()
    # MP/pymatgen 的 CIF 头部带注释行(# generated using pymatgen)再接 data_，
    # 故不能要求 strip() 直接以 data_ 开头；只要含 data_ 行且含晶胞参数即可。
    return re.search(r"(?m)^data_\S", s) is not None and "_cell_length_a" in text
```

可重入跳过逻辑：

```python
# download_cifs.py:387-398
    for i, item in enumerate(MANIFEST, 1):
        path = out_path(item)
        # 可重入：已存在且有效则跳过
        if os.path.exists(path):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    old = f.read()
                if valid_cif(old):
                    print("[%2d] SKIP  %s -> %s" % (i, item["cn"], os.path.relpath(path, ROOT)))
                    results.append(make_row(i, item, "OK", "skip-existing", path))
                    continue
            except Exception:
                pass
```

---

## 10. MP/COD/IZA 三数据源按"是否需要 key"分类

MP 只是三个数据源之一。COD、IZA 都是 CIF 直链、**完全免 key**，无需上述认证与降级逻辑：

```python
# download_cifs.py:283-292
def cod_get_cif(codid):
    url = "%s/%s.cif" % (COD_BASE, codid)
    data, _ = http_get(url)
    return data.decode("utf-8", "replace")

def iza_get_cif(izaid):
    url = "%s?ID=%s" % (IZA_BASE, izaid)
    data, _ = http_get(url)
    return data.decode("utf-8", "replace")
```

| source | 端点 | 需要 key | 备注 |
|---|---|---|---|
| `mp` | 新 API 搜索 + legacy 下载 | 搜索需要 | 双端点，新格式 id 降级 pymatgen |
| `cod` | `www.crystallography.net/cod/{id}.cif` | 否 | CIF 直链 |
| `iza` | `europe.iza-structure.org/IZA-SC/download_cif.php?ID={id}` | 否 | 理想化骨架 CIF |
| `manual` | 本地 pymatgen 构造 | 否 | MP/COD 都没有的相（如 CsV3Sb5、冰X） |

---

## 速查清单

配置 MP API 时必做的 8 件事：

1. ✅ 搜索走新 API，下载 CIF 走无 key 的 legacy 端点
2. ✅ 带浏览器 `User-Agent` 绕过 Cloudflare 1010
3. ✅ 声明 `Accept-Encoding` 后手动解 gzip/deflate
4. ✅ API key：env 优先、文件兜底
5. ✅ 搜索用 `_fields` 精简 + `spacegroup_number` 锁多晶型
6. ✅ 新格式 mp-id legacy 失败时降级到 pymatgen 本地转 CIF
7. ✅ Windows 下强制 stdout UTF-8
8. ✅ 可重入校验，且校验不能要求 `data_` 开头（pymatgen 带注释头）
