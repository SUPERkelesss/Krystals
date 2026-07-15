import spglib
import json
import fractions
import os

# Resolve the Hall mapping relative to this script so it can be run from any cwd.
_HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(_HERE, 'spacegroup_hall_mapping.json')) as f:
    mapping = json.load(f)

symbols = ['P1','P-1','P2','P21','C2','Pm','Pc','Cm','Cc','P2/m','P21/m','C2/m','P2/c','P21/c','C2/c','P222','P2221','P21212','P212121','C2221','C222','F222','I222','I212121','Pmm2','Pmc21','Pcc2','Pma2','Pca21','Pnc2','Pmn21','Pba2','Pna21','Pnn2','Cmm2','Cmc21','Ccc2','Amm2','Aem2','Ama2','Aea2','Fmm2','Fdd2','Imm2','Iba2','Ima2','Pmmm','Pnnn','Pccm','Pban','Pmma','Pnna','Pmna','Pcca','Pbam','Pccn','Pbcm','Pnnm','Pmmn','Pbcn','Pbca','Pnma','Cmcm','Cmce','Cmmm','Cccm','Cmme','Ccce','Fmmm','Fddd','Immm','Ibam','Ibca','Imma','P4','P41','P42','P43','I4','I41','P-4','I-4','P4/m','P42/m','P4/n','P42/n','I4/m','I41/a','P422','P4212','P4122','P41212','P4222','P42212','P4322','P43212','I422','I4122','P4mm','P4bm','P42cm','P42nm','P4cc','P4nc','P42mc','P42bc','I4mm','I4cm','I41md','I41cd','P-42m','P-42c','P-421m','P-421c','P-4m2','P-4c2','P-4b2','P-4n2','I-4m2','I-4c2','I-42m','I-42d','P4/mmm','P4/mcc','P4/nbm','P4/nnc','P4/mbm','P4/mnc','P4/nmm','P4/ncc','P42/mmc','P42/mcm','P42/nbc','P42/nnm','P42/mbc','P42/mnm','P42/nmc','P42/ncm','I4/mmm','I4/mcm','I41/amd','I41/acd','P3','P31','P32','R3','P-3','R-3','P312','P321','P3112','P3121','P3212','P3221','R32','P3m1','P31m','P3c1','P31c','R3m','R3c','P-31m','P-31c','P-3m1','P-3c1','R-3m','R-3c','P6','P61','P65','P62','P64','P63','P-6','P6/m','P63/m','P622','P6122','P6522','P6222','P6422','P6322','P6mm','P6cc','P63cm','P63mc','P-6m2','P-6c2','P-62m','P-62c','P6/mmm','P6/mcc','P63/mcm','P63/mmc','P23','F23','I23','P213','I213','Pm-3','Pn-3','Fm-3','Fd-3','Im-3','Pa-3','Ia-3','P432','P4232','F432','F4132','I432','P4332','P4132','I4132','P-43m','F-43m','I-43m','P-43n','F-43c','I-43d','Pm-3m','Pn-3n','Pm-3n','Pn-3m','Fm-3m','Fm-3c','Fd-3m','Fd-3c','Im-3m','Ia-3d']

def frac_str(x):
    f = fractions.Fraction(x).limit_denominator(12)
    if f.denominator == 1:
        return str(f.numerator)
    else:
        return f'{f.numerator}/{f.denominator}'

def rotation_to_expr(rot, trans):
    terms = []
    for i in range(3):
        parts = []
        if rot[i][0] == 1:
            parts.append('x')
        elif rot[i][0] == -1:
            parts.append('-x')
        if rot[i][1] == 1:
            parts.append('+y')
        elif rot[i][1] == -1:
            parts.append('-y')
        if rot[i][2] == 1:
            parts.append('+z')
        elif rot[i][2] == -1:
            parts.append('-z')
        t = frac_str(trans[i])
        if t != '0':
            if t.startswith('-'):
                parts.append(t)
            else:
                parts.append('+' + t)
        expr = ''.join(parts)
        if expr.startswith('+'):
            expr = expr[1:]
        if expr == '':
            expr = '0'
        terms.append(expr)
    return ','.join(terms)

parts = []
parts.append('package com.krystals.core')
parts.append('')
parts.append('data class SpaceGroupInfo(val number: Int, val symbol: String, val crystalSystem: String, val pointGroup: String)')
parts.append('')
parts.append('object SpaceGroupCatalog {')
parts.append('    private val symbols = """' + '|'.join(symbols) + '""".split(\'|\')')
parts.append('')
parts.append('    val all: List<SpaceGroupInfo> = symbols.mapIndexed { index, symbol ->')
parts.append('        val number = index + 1')
parts.append('        SpaceGroupInfo(number, symbol, crystalSystem(number), pointGroup(number))')
parts.append('    }')
parts.append('')
parts.append('    fun find(name: String): SpaceGroupInfo? {')
parts.append('        val normalized = name.replace(" ", "").replace("_", "").lowercase()')
parts.append('        return all.firstOrNull { it.symbol.replace("_", "").lowercase() == normalized }')
parts.append('    }')
parts.append('')
parts.append('    private val operationsTable: Map<Int, List<String>> = mapOf(')
for idx, s in enumerate(symbols):
    num = idx + 1
    hall = mapping[s]
    ops = spglib.get_symmetry_from_database(hall)
    op_strs = [rotation_to_expr(r, t) for r, t in zip(ops['rotations'], ops['translations'])]
    op_list = ', '.join(f'"{o}"' for o in op_strs)
    parts.append(f'        {num} to listOf({op_list}),')
parts.append('    )')
parts.append('')
parts.append('    fun operations(name: String): List<SymmetryOperation> {')
parts.append('        val number = find(name)?.number ?: return listOf(SymmetryOperation.IDENTITY)')
parts.append('        return operationsTable[number]?.map { SymmetryOperation.parse(it) }')
parts.append('            ?: listOf(SymmetryOperation.IDENTITY)')
parts.append('    }')
parts.append('')
parts.append('    private fun crystalSystem(n: Int) = when (n) {')
parts.append('        1, 2 -> "Triclinic"')
parts.append('        in 3..15 -> "Monoclinic"')
parts.append('        in 16..74 -> "Orthorhombic"')
parts.append('        in 75..142 -> "Tetragonal"')
parts.append('        in 143..167 -> "Trigonal"')
parts.append('        in 168..194 -> "Hexagonal"')
parts.append('        else -> "Cubic"')
parts.append('    }')
parts.append('')
parts.append('    private fun pointGroup(n: Int) = when (n) {')
parts.append('        1 -> "1"; 2 -> "-1"; in 3..5 -> "2"; in 6..9 -> "m"; in 10..15 -> "2/m"')
parts.append('        in 16..24 -> "222"; in 25..46 -> "mm2"; in 47..74 -> "mmm"')
parts.append('        in 75..80 -> "4"; in 81..82 -> "-4"; in 83..88 -> "4/m"; in 89..98 -> "422"')
parts.append('        in 99..110 -> "4mm"; in 111..122 -> "-42m"; in 123..142 -> "4/mmm"')
parts.append('        in 143..146 -> "3"; in 147..148 -> "-3"; in 149..155 -> "32"; in 156..161 -> "3m"')
parts.append('        in 162..167 -> "-3m"; in 168..173 -> "6"; 174 -> "-6"; in 175..176 -> "6/m"')
parts.append('        in 177..182 -> "622"; in 183..186 -> "6mm"; in 187..190 -> "-6m2"; in 191..194 -> "6/mmm"')
parts.append('        in 195..199 -> "23"; in 200..206 -> "m-3"; in 207..214 -> "432"; in 215..220 -> "-43m"')
parts.append('        else -> "m-3m"')
parts.append('    }')
parts.append('}')

content = '\n'.join(parts)
# Write into the source tree (repo root is two levels up from scripts/dev).
_repo_root = os.path.dirname(os.path.dirname(os.path.dirname(_HERE)))
out_path = os.path.join(_repo_root, 'crystal-core', 'src', 'main', 'kotlin', 'com', 'krystals', 'core', 'SpaceGroupCatalog.kt')
with open(out_path, 'w', encoding='utf-8') as f:
    f.write(content)

print(f'Wrote {out_path} ({len(content)} chars, {len(symbols)} space groups)')
