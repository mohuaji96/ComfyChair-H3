import io, json
p = "app/src/main/assets/tag_catalog.json"
d = json.load(io.open(p, encoding="utf-8"))
for r in d["rules"]["skipCategories"]:
    if r.get("skip") == ["pussy"]:
        r["from"] = ["bottom.type", "panties.type"]
        print(r["from"])
json.dump(d, io.open(p, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=2)
