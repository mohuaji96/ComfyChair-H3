import io, json

PATH = "app/src/main/assets/tag_catalog.json"
data = json.loads(io.open(PATH, encoding="utf-8").read())
cats = {c["id"]: c for c in data["categories"]}


def slot(cid, sid):
    return next(s for s in cats[cid]["slots"] if s["id"] == sid)


top = slot("top", "type")["pool"]
state = slot("outfitState", "state")["pool"]
detail = slot("garment", "detail")["pool"]

KEY = ("裙", "袍", "婚纱", "礼服", "华服", "宫装", "襦裙", "古装", "汉服", "和服",
       "浴衣", "泳衣", "练功服", "体操服", "大袖衫")
EXTRA = {
    "女仆装", "兔女郎装", "护士服", "空姐制服", "乘务员制服", "修女服", "京剧戏服",
    "忍者装", "海盗装", "赛车服", "消防员制服", "救生员制服", "民国学生装",
    "职业套装", "教师套装", "秘书套装", "银行职员套装", "前台套装",
    "酒店礼宾制服", "导游制服", "餐厅服务员制服", "厨师服", "烘焙师制服", "药房制服",
    "乳胶紧身衣", "绷带装", "吊带睡衣",
    "秀禾服", "龙凤褂", "宗门弟子服", "古风侠客服", "小师妹古风套装", "琴师古风套装",
    "蛊女套装", "仙门大师姐套装", "红莲妖姬套装", "望月灵姬套装", "苗疆巫女服",
    "山神侍女装", "雪灵仙子白衣", "玄水鲛人纱衣", "忘川引渡女官服", "镇灵使制服",
}
EXCEPT = {
    "围裙", "裸体围裙", "咖啡店店员围裙", "保育员围裙",
    "仙侠侠女劲装", "剑修劲装", "暗卫夜行衣", "凡间侠女短打", "狐族少主劲装",
    "雷灵仙姬劲装", "将军女甲胄",
}
one_piece = [w for w in top if (any(k in w for k in KEY) or w in EXTRA) and w not in EXCEPT]
assert len(one_piece) == len(set(one_piece))
print("onePiece", len(one_piece))

DROP_STATE = [
    "自己提内裤", "自己撩起衣摆", "自己把领口拉开", "拉下肩带", "双手高举伸展",
    "镜前穿衣肩带滑落", "对着小镜子涂口红", "坐在温泉边", "慢慢拉上吊袜带",
    "淋浴剪影", "系紧束腰", "镜前调整吊袜带", "醒来被床单缠住", "从泳池爬出",
    "慢慢脱下丝袜", "完全裸体", "上身裸露", "刚从浴室出来只围一条毛巾",
    "浴巾勉强裹身", "湿衣贴身", "被雨淋湿", "白色衬衫被汗水湿透变成半",
    "敞开的睡袍下露出内衣", "拉链半开的裤子", "衬衫掀起露出腰腹和下胸",
    "湿透的白衬衫变得透明", "衣裳半褪，露出半边肩膀", "衣裳半褪，胸口露了出来",
    "透视装",
    # 旗袍开衩是旗袍的一格细节，下衣已经有高开衩裙；离开旗袍读不成话。
    "旗袍开衩",
]
RENAME = {
    "衣襟松散敞着，没穿内衣的丰满胸部大片裸露在外": "衣襟敞着，里面没穿，胸口半露",
    "衣扣崩散敞开，沉甸甸的巨乳从敞开的衣襟间挤出半边弧度": "衣扣被胸撑开",
    "衣襟大敞，敞开的衣衫下未着寸缕，露出白皙饱满的双乳": "衣襟大敞，双乳外露",
    "衣裳被扯开一半，挂在臂弯": "衣裳扯开，挂在臂弯",
    "衣裳滑到肩下，随时会掉": "衣裳滑到肩下",
    "只挂着一点衣料，什么都遮不住": "只剩一点衣料",
    "只穿衬裙坐在梳妆台前化妆": "只穿衬裙",
    "薄上衣内真空": "薄上衣里没穿内衣",
}
CROSS_TO_STATE = ["衣冠不整", "湿透贴身", "衣服半透明"]
ADD_DETAIL = ["衣料被撑得发紧", "领口被撑得合不拢", "纽扣绷得扣不上",
              "胸口把衬衫撑开一道缝", "罩杯塞不住"]

new_state = []
for w in state:
    if w in DROP_STATE:
        continue
    new_state.append(RENAME.get(w, w))
new_state += [w for w in CROSS_TO_STATE if w not in new_state]
slot("outfitState", "state")["pool"] = new_state
print("outfitState", len(state), "->", len(new_state))

new_detail = [w for w in detail if w not in CROSS_TO_STATE]
new_detail += [w for w in ADD_DETAIL if w not in new_detail]
slot("garment", "detail")["pool"] = new_detail
print("garment", len(detail), "->", len(new_detail))

slot("bottom", "type")["pool"] = [w for w in slot("bottom", "type")["pool"] if w != "旗袍开衩"]
top.append("透视装")

DROP_ALL = set(DROP_STATE) - {"透视装"}


def clean(value):
    if isinstance(value, str):
        return None if value in DROP_ALL else RENAME.get(value, value)
    if isinstance(value, list):
        out = [clean(v) for v in value]
        return [v for v in out if v is not None]
    if isinstance(value, dict):
        out = {}
        for key, item in value.items():
            if key in DROP_ALL:
                continue
            cleaned = clean(item)
            if cleaned is None or cleaned == []:
                continue
            out[RENAME.get(key, key)] = cleaned
        return out
    return value


data["constraints"] = clean(data["constraints"])
data["constraints"]["onePiece"] = one_piece
data["constraints"]["onePieceBottom"] = ["连体衣", "西装套裙"]

for rule in data["rules"].get("skipCategories", []):
    for field in ("whenAny", "skipWords"):
        values = rule.get(field)
        if isinstance(values, list):
            rule[field] = [RENAME.get(v, v) for v in values if v not in DROP_ALL]

for key in ("uiOrder", "defaultLocked", "defaultIgnored"):
    values = data["meta"].get(key)
    if isinstance(values, list):
        data["meta"][key] = [RENAME.get(v, v) for v in values if v not in DROP_ALL]
for key, values in list((data["meta"].get("commonFirst") or {}).items()):
    data["meta"]["commonFirst"][key] = [RENAME.get(v, v) for v in values if v not in DROP_ALL]

data["meta"]["version"] = "0.9.8.8382.532-lib"
io.open(PATH, "w", encoding="utf-8", newline="\n").write(
    json.dumps(data, ensure_ascii=False, indent=2) + "\n"
)
print("written")
