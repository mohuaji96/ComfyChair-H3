import io, json

data = json.loads(io.open("app/src/main/assets/tag_catalog.json", encoding="utf-8").read())
cats = {c["id"]: c for c in data["categories"]}
top = next(s for s in cats["top"]["slots"] if s["id"] == "type")["pool"]

KEY = ("裙", "袍", "婚纱", "礼服", "华服", "宫装", "襦裙", "古装", "汉服", "和服",
       "浴衣", "泳衣", "练功服", "体操服", "大袖衫")
EXTRA = {
    "女仆装", "兔女郎装", "护士服", "空姐制服", "乘务员制服", "修女服", "京剧戏服",
    "忍者装", "海盗装", "赛车服", "消防员制服", "救生员制服", "民国学生装",
    "职业套装", "教师套装", "秘书套装", "银行职员套装", "前台套装",
    "酒店礼宾制服", "导游制服", "餐厅服务员制服", "厨师服", "烘焙师制服", "药房制服",
    "乳胶紧身衣", "绷带装", "吊带睡衣",
}
EXCEPT = {
    "仙侠侠女劲装", "剑修劲装", "暗卫夜行衣", "凡间侠女短打", "狐族少主劲装",
    "雷灵仙姬劲装", "将军女甲胄",
}

candidates = [w for w in top if any(k in w for k in KEY) or w in EXTRA]
picked = [w for w in candidates if w not in EXCEPT]
rest = [w for w in top if w not in picked]

print("一件式候选 %d 条：" % len(picked))
for w in picked:
    print("  " + w)
print()
print("留给上衣、可与下衣并存的 %d 条：" % len(rest))
print("  " + "|".join(rest))
