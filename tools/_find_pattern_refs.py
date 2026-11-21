import io, json

data = json.load(io.open("app/src/main/assets/tag_catalog.json", encoding="utf-8"))
req = data["constraints"]["requires"]
for key in ["乳头隔衣透出", "隔着内裤磨", "湿痕透过内裤", "隔着衣服揉胸", "被丝袜包住的腿线"]:
    print(key, "->", json.dumps(req[key], ensure_ascii=False))
print()
print("skipRules 32:", json.dumps(data["rules"]["skipCategories"][32], ensure_ascii=False)[:400])
print("skipRules 33:", json.dumps(data["rules"]["skipCategories"][33], ensure_ascii=False)[:400])
print()
print("cat7:", data["categories"][7]["id"], [(s["id"], s["label"]) for s in data["categories"][7]["slots"]])
print("cat8:", data["categories"][8]["id"], [(s["id"], s["label"]) for s in data["categories"][8]["slots"]])
print("cat9:", data["categories"][9]["id"], [(s["id"], s["label"], s["pool"]) for s in data["categories"][9]["slots"]])
print("cat25:", data["categories"][25]["id"], [(s["id"], s["label"]) for s in data["categories"][25]["slots"]])
