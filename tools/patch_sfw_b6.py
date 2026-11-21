#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B6：修真机抽词暴露的规则缺口。

只改数据，不动引擎。原因写在每条旁边——引擎的 who 门控只有男性一侧
（maleCategoryBlocked），补女性那一侧要连带 race/hair/top/bottom 一起屏蔽，
单人男性会变成空画面；用规则按人数精准跳过反而更准。

幂等：规则按 skip 目标去重，词按集合删除。
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "tag_catalog_sfw.json")

# 只对「上下装」成立的状态词，穿连身装时必然矛盾，直接删掉比加反向规则干净。
DROP_WORDS = {
    "outfitState": ["下摆塞进裤腰", "裤脚卷起一折", "衬衫下摆未收"],
    # 焦段混进取景、景别混进构图，会抽出「鱼眼 + 长焦压缩」「竖屏全身 + 高位俯拍半身」。
    "camera": ["长焦压缩背景", "广角环境人像"],
    "composition": ["中景平视", "竖屏全身", "横幅宽画幅"],
    # 与季节强绑的天气词：无论怎么排都会撞上「暮春下雪」「隆冬梅雨」。
    "weather": ["小雨夹雪", "初雪", "梅雨", "闷热的夏夜", "干燥的晴冬"],
    # 室内宠物出现在海边防波堤上，比没有宠物更出戏。
    "pet": ["白色的兔子", "仓鼠", "鹦鹉"],
    "propObject": ["留声机", "黑胶唱片"],
}

# 手部预算。引擎有 handCap=2 的削除逻辑，但 handCost 是空表时它从没生效过。
HAND_COST = {
    "搬东西": 2, "晾衣服": 2, "骑行": 2, "收伞": 2, "拍照": 2, "双手捧着杯子": 2,
    "上下楼梯": 1, "走路": 0, "小跑": 0, "跑步": 0, "浇花": 1,
    "手握咖啡杯": 1, "手扶栏杆": 1, "手撑下巴": 1, "手拨头发": 1, "双手叠放": 0,
    "手指点着纸面": 1, "单手插兜": 1, "手搭在椅背": 1, "手指搭在唇边": 1,
    "握着伞柄": 1, "手按在书页上": 1, "手碰着窗玻璃": 1, "指尖碰杯沿": 1,
}

RULES = [
    # 单人还抽出「把外套披到对方肩上」「十指相扣」——互动词必须有人数门控。
    {"whenAny": ["单人女性", "单人男性"], "skip": ["interaction"]},
    # 单人男性不该有妆容、连身裙、女性向的腰胯与五官描述。
    # top / bottom / legs / race / hair 保留：池里有中性词，屏蔽掉画面就空了。
    {
        "whenAny": ["单人男性"],
        "skip": ["makeup", "garment", "hips", "waist", "featureHead"],
    },
    # 帽子盖住整个头时发型没有意义。
    {
        "whenAny": ["丝巾包头", "白色渔夫帽", "深色鸭舌帽", "棒球帽", "米色针织帽", "毛线贝雷帽", "浅色草帽"],
        "skip": ["hair"],
    },
]


def main():
    with open(TARGET, encoding="utf-8") as handle:
        data = json.load(handle)

    by_id = {c["id"]: c for c in data["categories"]}

    for cid, words in DROP_WORDS.items():
        for slot in by_id[cid]["slots"]:
            slot["pool"] = [w for w in slot["pool"] if w not in words]

    # 删掉不合场景的词之后，几个池掉到 12 条以下。补中性词，别补回刚删的那类。
    ADD_WORDS = {
        "weather": ["晴朗", "薄雾"],
        "pet": ["灰色短毛猫", "橘白猫", "中华田园犬"],
        "composition": ["中心对称"],
    }
    for cid, words in ADD_WORDS.items():
        for slot in by_id[cid]["slots"]:
            for word in words:
                if word not in slot["pool"]:
                    slot["pool"].append(word)

    constraints = data["constraints"]
    male = constraints.setdefault("malePeople", [])
    if "单人男性" not in male:
        male.append("单人男性")

    constraints["handCost"] = dict(HAND_COST)
    constraints["handCap"] = 2

    rules = data["rules"]
    existing = rules.setdefault("skipCategories", [])
    for rule in RULES:
        # 按 whenAny 认同一条规则并整体替换：只按 skip 集合去重的话，改过 skip 的旧规则
        # 会留在原地，被删掉的分类 id 就一直挂在规则里报错。
        # 比的是 whenAny 的**首词**相等，不是「包含」：两条规则的触发词有交集时，
        # 用包含判断会把前一条整条删掉（互动规则就这么被单人男性规则吃掉过一次）。
        first = rule["whenAny"][0]
        existing[:] = [r for r in existing if (r.get("whenAny") or [None])[0] != first]
        existing.append(rule)

    data["meta"]["version"] = "0.9.8.8382-sfw-b6"

    with open(TARGET, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    total = sum(len(s["pool"]) for c in data["categories"] for s in c["slots"])
    print(f"分类 {len(data['categories'])} 个，词条 {total} 条，规则 {len(existing)} 条")


if __name__ == "__main__":
    main()
