#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按 todo.md 清单批量下载晶体结构 CIF 并分类整理。

数据源：
  - mp  : Materials Project。用新 API (api.materialsproject.org) 按化学式+空间群
          搜索 mp-id（需 X-API-KEY），再用 legacy 端点下载 CIF 文本（无需 key）。
  - cod : Crystallography Open Database，CIF 直链下载。
  - iza : IZA 沸石结构数据库，按框架数字 ID 下载理想化骨架 CIF。

仅使用 Python 标准库（urllib + json）。可重入：已存在且有效的 CIF 跳过。
用法:
  python download_cifs.py --search   # 仅打印每个 MP 项的候选 mp-id 供核对
  python download_cifs.py            # 下载全部，生成 report.md
"""

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

# 强制 stdout/stderr 用 UTF-8，避免 Windows GBK 控制台编码错误
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace", line_buffering=True)
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace", line_buffering=True)

ROOT = os.path.dirname(os.path.abspath(__file__))
CIF_ROOT = os.path.join(ROOT, "cifs")
KEY_FILE = os.path.join(ROOT, "mp_api_key.txt")
MP_NEW = "https://api.materialsproject.org"
MP_LEGACY = "https://legacy.materialsproject.org/rest/v1"
COD_BASE = "https://www.crystallography.net/cod"
IZA_BASE = "https://europe.iza-structure.org/IZA-SC/download_cif.php"

# ---------------------------------------------------------------------------
# MANIFEST：每项 {cn, formula, source, key, folder, file, note}
#   source="mp"  -> key={"formula":..,"sg":int|None,"mpid":"mp-xxx"|None}
#   source="cod" -> key=cod_id(int)
#   source="iza" -> key=iza_id(int)
# ---------------------------------------------------------------------------
MANIFEST = [
    # 01 基础晶体结构
    dict(cn="金刚石", formula="C", source="mp", key={"formula": "C", "sg": 227, "mpid": "mp-66"}, folder="01_basic", file="diamond_C.cif"),
    dict(cn="石墨", formula="C", source="mp", key={"formula": "C", "sg": 194, "mpid": "mp-48"}, folder="01_basic", file="graphite_C.cif"),
    dict(cn="黑磷", formula="P", source="mp", key={"formula": "P", "sg": 64}, folder="01_basic", file="blackP_P.cif"),
    dict(cn="C60富勒烯", formula="C60", source="cod", key=1531302, folder="01_basic", file="fullerene_C60.cif"),
    dict(cn="氯化钠", formula="NaCl", source="mp", key={"formula": "NaCl", "sg": 225, "mpid": "mp-22862"}, folder="01_basic", file="rocksalt_NaCl.cif"),
    dict(cn="氯化铯", formula="CsCl", source="mp", key={"formula": "CsCl", "sg": 221}, folder="01_basic", file="cscl_CsCl.cif"),
    dict(cn="萤石", formula="CaF2", source="mp", key={"formula": "CaF2", "sg": 225}, folder="01_basic", file="fluorite_CaF2.cif"),
    dict(cn="金红石", formula="TiO2", source="mp", key={"formula": "TiO2", "sg": 136}, folder="01_basic", file="rutile_TiO2.cif"),
    dict(cn="刚玉", formula="Al2O3", source="mp", key={"formula": "Al2O3", "sg": 167}, folder="01_basic", file="corundum_Al2O3.cif"),
    dict(cn="β-Ga2O3", formula="Ga2O3", source="mp", key={"formula": "Ga2O3", "sg": 12}, folder="01_basic", file="betaGa2O3_Ga2O3.cif"),
    dict(cn="纤锌矿ZnO", formula="ZnO", source="mp", key={"formula": "ZnO", "sg": 186}, folder="01_basic", file="wurtziteZnO_ZnO.cif"),
    dict(cn="闪锌矿ZnS", formula="ZnS", source="mp", key={"formula": "ZnS", "sg": 216}, folder="01_basic", file="sphaleriteZnS_ZnS.cif"),
    dict(cn="砷化镍", formula="NiAs", source="mp", key={"formula": "NiAs", "sg": 194}, folder="01_basic", file="nias_NiAs.cif"),
    dict(cn="石英", formula="SiO2", source="mp", key={"formula": "SiO2", "sg": 152}, folder="01_basic", file="quartz_SiO2.cif"),
    dict(cn="二硫化钼", formula="MoS2", source="mp", key={"formula": "MoS2", "sg": 194}, folder="01_basic", file="mos2_MoS2.cif"),
    dict(cn="1T-TaS2", formula="TaS2", source="mp", key={"formula": "TaS2", "sg": 164}, folder="01_basic", file="1TTaS2_TaS2.cif"),
    dict(cn="纤锌矿GaN", formula="GaN", source="mp", key={"formula": "GaN", "sg": 186}, folder="01_basic", file="wurtziteGaN_GaN.cif"),
    # 02 多元氧化物
    dict(cn="钙钛矿", formula="CaTiO3", source="mp", key={"formula": "CaTiO3", "sg": 62}, folder="02_oxides", file="perovskite_CaTiO3.cif"),
    dict(cn="尖晶石", formula="MgAl2O4", source="mp", key={"formula": "MgAl2O4", "sg": 227}, folder="02_oxides", file="spinel_MgAl2O4.cif"),
    dict(cn="Ruddlesden-Popper", formula="Sr3Ti2O7", source="mp", key={"formula": "Sr3Ti2O7", "sg": 139}, folder="02_oxides", file="rp_Sr3Ti2O7.cif"),
    dict(cn="Aurivillius", formula="SrBi2Ta2O9", source="mp", key={"formula": "SrBi2Ta2O9", "sg": None, "mpid": "mp-aaaabieb"}, folder="02_oxides", file="aurivillius_SrBi2Ta2O9.cif", note="MP写作SrTa2Bi2O9,sg36 Cmc21,即SBT铁电相"),
    dict(cn="烧绿石", formula="La2Zr2O7", source="mp", key={"formula": "La2Zr2O7", "sg": 227}, folder="02_oxides", file="pyrochlore_La2Zr2O7.cif"),
    dict(cn="三氧化铼", formula="ReO3", source="mp", key={"formula": "ReO3", "sg": 221}, folder="02_oxides", file="reo3_ReO3.cif"),
    dict(cn="双钙钛矿", formula="Sr2FeMoO6", source="mp", key={"formula": "Sr2FeMoO6", "sg": 225}, folder="02_oxides", file="doubleperovskite_Sr2FeMoO6.cif"),
    dict(cn="钛酸钡", formula="BaTiO3", source="mp", key={"formula": "BaTiO3", "sg": 221, "mpid": "mp-aaaaaeli"}, folder="02_oxides", file="perovskite_BaTiO3.cif", note="立方钙钛矿相Pm-3m"),
    # 03 硅酸盐与碳酸盐矿物
    dict(cn="方解石", formula="CaCO3", source="mp", key={"formula": "CaCO3", "sg": 167}, folder="03_silicates", file="calcite_CaCO3.cif"),
    dict(cn="锆石", formula="ZrSiO4", source="mp", key={"formula": "ZrSiO4", "sg": 141}, folder="03_silicates", file="zircon_ZrSiO4.cif"),
    dict(cn="绿柱石", formula="Be3Al2Si6O18", source="mp", key={"formula": "Be3Al2Si6O18", "sg": 192}, folder="03_silicates", file="beryl_Be3Al2Si6O18.cif"),
    dict(cn="石榴石", formula="Ca3Al2Si3O12", source="mp", key={"formula": "Ca3Al2Si3O12", "sg": 230}, folder="03_silicates", file="garnet_Ca3Al2Si3O12.cif"),
    dict(cn="橄榄石", formula="Mg2SiO4", source="mp", key={"formula": "Mg2SiO4", "sg": 62}, folder="03_silicates", file="olivine_Mg2SiO4.cif"),
    dict(cn="辉石", formula="MgSiO3", source="mp", key={"formula": "MgSiO3", "sg": 60}, folder="03_silicates", file="pyroxene_MgSiO3.cif"),
    dict(cn="角闪石", formula="Ca2Mg5Si8O22(OH)2", source="cod", key=1011222, folder="03_silicates", file="hornblende_Ca2Mg5Si8O22OH2.cif", note="透闪石端元"),
    dict(cn="云母", formula="KAl2(AlSi3O10)(OH)2", source="cod", key=1011049, folder="03_silicates", file="mica_KAl2AlSi3O10OH2.cif", note="白云母muscovite"),
    dict(cn="长石", formula="KAlSi3O8", source="mp", key={"formula": "KAlSi3O8", "sg": None, "mpid": "mp-aaabnsbm"}, folder="03_silicates", file="feldspar_KAlSi3O8.cif", note="MP无sg12,取stable的sg2 P-1近似(微斜长石)"),
    dict(cn="蒙脱石", formula="Na0.33Al2Si4O10(OH)2·2H2O", source="cod", key=1100106, folder="03_silicates", file="montmorillonite_NaAl2Si4O10OH2.cif", note="COD 1100106,有坐标,K-蒙脱石近似(层状结构)"),
    # 04 分子筛与沸石 (IZA 纯 SiO2 理想骨架)
    dict(cn="LTA型分子筛", formula="Na12Al12Si12O48·27H2O", source="iza", key=138, folder="04_zeolites", file="LTA_zeolite.cif"),
    dict(cn="FAU型分子筛", formula="Na56Al56Si136O384", source="iza", key=93, folder="04_zeolites", file="FAU_zeolite.cif"),
    dict(cn="MFI型ZSM-5", formula="Na3Al3Si93O192", source="iza", key=149, folder="04_zeolites", file="MFI_ZSM5.cif"),
    dict(cn="MOR型丝光沸石", formula="Na8Al8Si40O96·24H2O", source="iza", key=152, folder="04_zeolites", file="MOR_mordenite.cif"),
    dict(cn="BEA型Beta", formula="Na3Al3Si61O128", source="iza", key=51, folder="04_zeolites", file="BEA_beta.cif", note="BEC有序端元近似*BEA"),
    dict(cn="CHA型菱沸石", formula="Ca2Al2Si4O12·4H2O", source="iza", key=65, folder="04_zeolites", file="CHA_chabazite.cif"),
    dict(cn="MEL型ZSM-11", formula="Na3Al3Si93O192", source="iza", key=146, folder="04_zeolites", file="MEL_ZSM11.cif"),
    dict(cn="SOD型方钠石", formula="Na8Al6Si6O24Cl2", source="iza", key=221, folder="04_zeolites", file="SOD_sodalite.cif"),
    # 05 MOF/COF
    dict(cn="MOF-5", formula="Zn4O(C8H4O4)3", source="cod", key=1516287, folder="05_mof_cof", file="MOF5_Zn4OBDC3.cif", note="活化态"),
    dict(cn="HKUST-1", formula="Cu3(C9H3O6)2", source="cod", key=1546786, folder="05_mof_cof", file="HKUST1_Cu3BTC2.cif", note="活化态"),
    dict(cn="ZIF-8", formula="C8H10N4Zn", source="cod", key=7111973, folder="05_mof_cof", file="ZIF8_C8H10N4Zn.cif"),
    dict(cn="COF-1", formula="C6H6B2O3", source="cod", key=None, folder="05_mof_cof", file="COF1_C6H6B2O3.cif", note="COD无,需人工补"),
    # 06 金属间化合物与特殊合金相
    dict(cn="L1₂有序合金Ni3Al", formula="Ni3Al", source="mp", key={"formula": "Ni3Al", "sg": 221}, folder="06_intermetallics", file="Ni3Al_L12.cif"),
    dict(cn="Laves相MgCu2", formula="MgCu2", source="mp", key={"formula": "MgCu2", "sg": 227}, folder="06_intermetallics", file="MgCu2_Laves.cif"),
    dict(cn="Laves相MgZn2", formula="MgZn2", source="mp", key={"formula": "MgZn2", "sg": 194}, folder="06_intermetallics", file="MgZn2_Laves.cif"),
    dict(cn="Heusler合金", formula="Cu2MnAl", source="mp", key={"formula": "Cu2MnAl", "sg": 225}, folder="06_intermetallics", file="Cu2MnAl_Heusler.cif"),
    dict(cn="Sigma相", formula="FeCr", source="mp", key={"formula": "FeCr", "sg": None, "mpid": "mp-aaacrtxz"}, folder="06_intermetallics", file="FeCr_sigma.cif", note="MP无sg136 sigma相,取sg65 CrFe近似"),
    dict(cn="Chi相", formula="Fe2Cr", source="mp", key={"formula": "Fe2Cr", "sg": None}, folder="06_intermetallics", file="Fe2Cr_chi.cif", note="MP无Fe2Cr,需人工补"),
    dict(cn="Mu相", formula="Fe7W6", source="mp", key={"formula": "Fe7W6", "sg": 166}, folder="06_intermetallics", file="Fe7W6_mu.cif"),
    # 07 超导、拓扑与热电先进功能材料
    dict(cn="二硼化镁超导体", formula="MgB2", source="mp", key={"formula": "MgB2", "sg": 191}, folder="07_functional", file="MgB2.cif"),
    dict(cn="YBCO高温超导", formula="YBa2Cu3O7", source="mp", key={"formula": "YBa2Cu3O7", "sg": 47}, folder="07_functional", file="YBCO_YBa2Cu3O7.cif"),
    dict(cn="BSCCO铋系超导", formula="Bi2Sr2Ca2Cu3O10", source="mp", key={"formula": "Bi2Sr2Ca2Cu3O10", "sg": None, "mpid": "mp-aaacqump"}, folder="07_functional", file="BSCCO_Bi2Sr2Ca2Cu3O10.cif", note="MP写作Sr2Ca2Cu3(BiO5)2,sg139,2223相"),
    dict(cn="笼目超导体", formula="CsV3Sb5", source="manual", key="CsV3Sb5", folder="07_functional", file="CsV3Sb5_kagome.cif", note="MP/COD无,按Ortiz2020文献P6/mmm构造"),
    dict(cn="Chevrel相超导", formula="PbMo6S8", source="mp", key={"formula": "PbMo6S8", "sg": 148}, folder="07_functional", file="PbMo6S8_chevrel.cif"),
    dict(cn="方钴矿热电", formula="CoAs3", source="mp", key={"formula": "CoAs3", "sg": 204}, folder="07_functional", file="CoAs3_skutterudite.cif"),
    dict(cn="拓扑绝缘体", formula="Bi2Se3", source="mp", key={"formula": "Bi2Se3", "sg": 166}, folder="07_functional", file="Bi2Se3_topological.cif"),
    dict(cn="狄拉克半金属", formula="Cd3As2", source="mp", key={"formula": "Cd3As2", "sg": 142}, folder="07_functional", file="Cd3As2_dirac.cif"),
    # 08 分子晶体与冰的多晶型
    dict(cn="干冰", formula="CO2", source="cod", key=1010060, folder="08_molecular_ice", file="dryice_CO2.cif"),
    dict(cn="苯", formula="C6H6", source="cod", key=2100348, folder="08_molecular_ice", file="benzene_C6H6.cif"),
    dict(cn="六方冰Ih", formula="H2O", source="cod", key=1011023, folder="08_molecular_ice", file="iceIh_H2O.cif"),
    dict(cn="立方冰Ic", formula="H2O", source="cod", key=1541503, folder="08_molecular_ice", file="iceIc_H2O.cif"),
    dict(cn="质子有序冰XI", formula="H2O", source="mp", key={"formula": "H2O", "sg": 36, "mpid": "mp-aaabnrfz"}, folder="08_molecular_ice", file="iceXI_H2O.cif", note="sg36 Cmc21,质子有序相"),
    dict(cn="高压冰VII", formula="H2O", source="cod", key=9016611, folder="08_molecular_ice", file="iceVII_H2O.cif", note="Pn-3m,2.5GPa"),
    dict(cn="超高压冰X", formula="H2O", source="manual", key="iceX", folder="08_molecular_ice", file="iceX_H2O.cif", note="MP/COD无独立冰X,按Pn-3m氢对称化构造"),
    dict(cn="高压冰VIII", formula="H2O", source="mp", key={"formula": "H2O", "sg": 141, "mpid": "mp-aaabnrez"}, folder="08_molecular_ice", file="iceVIII_H2O.cif", note="sg141 I41/amd"),
]


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
def http_get(url, headers=None, timeout=60):
    # MP 的 CDN(Cloudflare) 会拒绝无 User-Agent 的默认 urllib 请求(403 error 1010)，
    # 故必须带上浏览器风格的 User-Agent。
    base = {"User-Agent": "Mozilla/5.0", "Accept": "application/json",
            "Accept-Encoding": "gzip, deflate"}
    if headers:
        base.update(headers)
    req = urllib.request.Request(url, headers=base)
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


def load_api_key():
    env = os.environ.get("MP_API_KEY")
    if env and env.strip():
        return env.strip()
    if os.path.exists(KEY_FILE):
        with open(KEY_FILE, "r", encoding="utf-8") as f:
            return f.read().strip()
    return None


# ---------------------------------------------------------------------------
# Materials Project
# ---------------------------------------------------------------------------
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


def manual_cif(spec):
    """对 MP/COD 都取不到的目标相，按文献晶胞参数构造 CIF。
    spec = dict(lattice=..., species=[...], coords=[[x,y,z],...], name=..., symprec=0.1)。
    lattice: ('hex', a, c) / ('cubic', a) / ('tet', a, c) / ('ortho', a,b,c) / ('mono', a,b,c,beta)"""
    try:
        from pymatgen.core import Structure, Lattice
        from pymatgen.io.cif import CifWriter
    except ImportError:
        return None
    lt = spec["lattice"]
    if lt[0] == "hex":
        lat = Lattice.hexagonal(lt[1], lt[2])
    elif lt[0] == "cubic":
        lat = Lattice.cubic(lt[1])
    elif lt[0] == "tet":
        lat = Lattice.tetragonal(lt[1], lt[2])
    elif lt[0] == "ortho":
        lat = Lattice.orthorhombic(lt[1], lt[2], lt[3])
    elif lt[0] == "mono":
        lat = Lattice.monoclinic(lt[1], lt[2], lt[3], lt[4])
    else:
        return None
    s = Structure(lat, spec["species"], spec["coords"])
    cw = CifWriter(s, symprec=spec.get("symprec", 0.1))
    return str(cw)


# 文献晶胞参数手动构造的难点相（MP/COD 均无理想化结构）
MANUAL = {
    "CsV3Sb5": dict(
        lattice=("hex", 5.4918, 9.3125),
        species=["Cs", "V", "V", "V", "Sb", "Sb", "Sb", "Sb", "Sb"],
        coords=[[0, 0, 0], [0.5, 0, 0.5], [0, 0.5, 0.5], [0.5, 0.5, 0.5],
                [1/3, 2/3, 0.5], [2/3, 1/3, 0.5], [0.5, 0, 0], [0, 0.5, 0], [0.5, 0.5, 0]],
        symprec=0.1),  # P6/mmm, Ortiz et al. 2020
    "iceX": dict(
        lattice=("cubic", 3.34),
        species=["O", "H"],
        coords=[[0, 0, 0], [0.5, 0.5, 0.5]],
        symprec=0.1),  # Pn-3m, 氢对称化超高压冰X
}


# ---------------------------------------------------------------------------
# COD / IZA
# ---------------------------------------------------------------------------
def cod_get_cif(codid):
    url = "%s/%s.cif" % (COD_BASE, codid)
    data, _ = http_get(url)
    return data.decode("utf-8", "replace")


def iza_get_cif(izaid):
    url = "%s?ID=%s" % (IZA_BASE, izaid)
    data, _ = http_get(url)
    return data.decode("utf-8", "replace")


# ---------------------------------------------------------------------------
# CIF 校验 / 信息提取
# ---------------------------------------------------------------------------
def valid_cif(text):
    if not text:
        return False
    s = text.strip()
    # MP/pymatgen 的 CIF 头部带注释行(# generated using pymatgen)再接 data_，
    # 故不能要求 strip() 直接以 data_ 开头；只要含 data_ 行且含晶胞参数即可。
    return re.search(r"(?m)^data_\S", s) is not None and "_cell_length_a" in text


def cif_formula_sum(text):
    m = re.search(r"_chemical_formula_sum\s+'?([^'\n]+?)'?\s*$", text, re.MULTILINE)
    return m.group(1).strip() if m else ""


def cif_sg(text):
    m = re.search(r"_symmetry_Int_Tables_number\s+(\d+)", text)
    return m.group(1) if m else ""


# ---------------------------------------------------------------------------
# 路径
# ---------------------------------------------------------------------------
def out_path(item):
    return os.path.join(CIF_ROOT, item["folder"], item["file"])


# ---------------------------------------------------------------------------
# search 模式
# ---------------------------------------------------------------------------
def run_search(api_key):
    print("=" * 90)
    print("MP 候选核对（仅打印，不下载）")
    print("=" * 90)
    for i, item in enumerate(MANIFEST, 1):
        if item["source"] != "mp":
            continue
        k = item["key"]
        print("\n[%2d] %s  %s  (sg约束=%s, 已知mpid=%s) %s" % (
            i, item["cn"], item["formula"], k.get("sg"), k.get("mpid"), item.get("note", "")))
        try:
            cands = mp_search(k["formula"], k.get("sg"), api_key, limit=12)
        except Exception as e:
            print("   搜索出错: %s" % e)
            continue
        if not cands:
            # 退一步：不带 sg 重搜
            try:
                cands = mp_search(k["formula"], None, api_key, limit=12)
                print("   (sg=%s 无结果，已用无约束重搜)" % k.get("sg"))
            except Exception as e:
                print("   重搜出错: %s" % e)
                continue
        for c in cands[:12]:
            sym = c.get("symmetry") or {}
            ehull = c.get("energy_above_hull")
            ehull = ("%.3f" % ehull) if ehull is not None else "n/a"
            mark = "*" if c.get("is_stable") else " "
            print("   %s %-11s %-12s sg=%-4s %-12s ehull=%s nsites=%s" % (
                mark, c.get("material_id"), c.get("formula_pretty"),
                sym.get("number"), sym.get("symbol"), ehull, c.get("nsites")))


# ---------------------------------------------------------------------------
# 下载模式
# ---------------------------------------------------------------------------
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


def run_download(api_key):
    results = []
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

        status, chosen_id, note = "FAIL", "", item.get("note", "")
        try:
            if item["source"] == "mp":
                mpid, cand = resolve_mp(item, api_key)
                if not mpid:
                    raise RuntimeError(item.get("_err", "no mpid"))
                time.sleep(0.1)
                text, how = mp_get_cif(mpid, api_key)
                chosen_id = mpid + " (%s)" % how
            elif item["source"] == "cod":
                if not item["key"]:
                    raise RuntimeError("COD无此条目,需人工补")
                text = cod_get_cif(item["key"])
                chosen_id = "cod:%s" % item["key"]
            elif item["source"] == "iza":
                text = iza_get_cif(item["key"])
                chosen_id = "iza:%s" % item["key"]
            elif item["source"] == "manual":
                text = manual_cif(MANUAL[item["key"]])
                chosen_id = "manual:%s" % item["key"]
            else:
                raise RuntimeError("unknown source")

            if not valid_cif(text):
                raise RuntimeError("invalid CIF")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(text)
            status = "OK"
            print("[%2d] OK    %s -> %s" % (i, item["cn"], os.path.relpath(path, ROOT)))
        except Exception as e:
            note = (note + " | " if note else "") + str(e)
            print("[%2d] FAIL  %s : %s" % (i, item["cn"], e))
        time.sleep(0.1)
        results.append(make_row(i, item, status, chosen_id, path if status == "OK" else "", note))

    write_report(results)
    ok = sum(1 for r in results if r["status"] == "OK")
    print("\n完成: %d/%d OK, %d FAIL" % (ok, len(results), len(results) - ok))


def make_row(i, item, status, chosen_id, path, note=""):
    fsum = ""
    sgnum = ""
    if status == "OK" and path:
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                t = f.read()
            fsum = cif_formula_sum(t)
            sgnum = cif_sg(t)
        except Exception:
            pass
    return dict(
        i=i, cn=item["cn"], formula=item["formula"], source=item["source"],
        id=chosen_id, status=status, fsum=fsum, sgnum=sgnum,
        rel=os.path.relpath(path, ROOT).replace("\\", "/") if path else "",
        note=note or item.get("note", ""),
    )


def write_report(results):
    lines = []
    lines.append("# CIF 下载报告\n")
    ok = sum(1 for r in results if r["status"] == "OK")
    lines.append("总项数: %d，成功: %d，失败: %d\n" % (len(results), ok, len(results) - ok))
    lines.append("| # | 中文名 | 化学式 | 源 | 选定ID | 状态 | CIF_formula_sum | sg# | 路径 | 备注 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        lines.append("| %d | %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
            r["i"], r["cn"], r["formula"], r["source"], r["id"], r["status"],
            r["fsum"], r["sgnum"], r["rel"], r["note"].replace("|", "/")))
    fails = [r for r in results if r["status"] != "OK"]
    if fails:
        lines.append("\n## 失败项（需处理）\n")
        for r in fails:
            lines.append("- #%d %s (%s): %s" % (r["i"], r["cn"], r["formula"], r["note"]))
    with open(os.path.join(ROOT, "report.md"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--search", action="store_true", help="仅打印 MP 候选供核对")
    args = ap.parse_args()
    api_key = load_api_key()
    if args.search and not api_key:
        print("警告: 未找到 API key（MP_API_KEY 环境变量或 mp_api_key.txt），MP 搜索将失败")
    if args.search:
        if not api_key:
            sys.exit("缺少 API key，无法搜索")
        run_search(api_key)
    else:
        run_download(api_key)


if __name__ == "__main__":
    main()
