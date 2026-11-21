import io, json
p = "app/src/main/assets/tag_catalog.json"
d = json.load(io.open(p, encoding="utf-8"))
rules = d["rules"]["skipCategories"]

EXTRA_UNLESS = ["衣冠不整", "衣装半褪", "只剩一点衣料"]

PUSSY_UNLESS = [
    "无内裤", "没穿内裤", "开档内裤", "无裆内裤",
    "被拨到一边的内裤", "内裤被拨到一边", "被拉到一边的内裤",
    "褪到膝盖的内裤", "挂在脚踝的内裤",
    "走光意外", "瞬间的走光", "内裤从腰际微微露出", "几乎看不见的内裤",
]

for r in rules:
    if r.get("skip") == ["breasts.shape", "breasts.nipple"] and r.get("from"):
        for w in EXTRA_UNLESS:
            if w not in r["unless"]:
                r["unless"].append(w)
    if r.get("skip") == ["pussy"]:
        r["from"] = ["bottom", "panties"]
        r["unless"] = PUSSY_UNLESS

json.dump(d, io.open(p, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=2)
print("ok")
