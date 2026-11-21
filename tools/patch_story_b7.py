#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B7：修剧情组合抽词暴露的句式与数据问题。

剧情库没有 skipCategories 这类规则机制（StoryLibrary 只读 casts/pools/templates），
所以这一批只能用删词与改名解决；真正的跨分项配对（chore×place、world×place）
需要给 StoryLibrary 加规则字段，留到下一版。

幂等：DROP 重复执行无副作用，RENAME 第二次跑时旧词已不存在。
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "story_catalog_sfw.json")

# 只对上下装成立的状态词；穿连身装时必然矛盾（P13）
DROP = {
    "outfit_state": ["下摆塞进裤腰", "裤脚卷起一折", "衬衫下摆未收"],
}

# 与「身穿X」直接打架的状态词（P14）：身上穿着一件，又说外套敞开
DROP["outfit_state"] += ["外套敞开", "外套半脱搭在肩上"]

# 强绑定单一场景的日常动作（P15）：草地上铺床、候车棚拉晾衣绳
DROP["chore"] = ["把床铺平", "把晾衣绳拉紧", "把被子折到床尾", "把枕头拍两下"]

# 改名：去掉自带主语、自带鞋款、自带时段，避免与模板/其他分项重复
RENAME = {
    # build 自带「身材/身形/体态」主语 → 与模板「一位身材{build}的」重复（P16）
    "build": {
        "身材修长": "修长匀称",
        "身量高挑": "高挑",
        "体态挺拔": "挺拔",
        "身形轻盈": "轻盈",
    },
    # bust 里的姿态词交给 pose（P17）
    "bust": {},
    # place 自带时段 → 与句首时间打架（P18）
    "place": {
        "傍晚的地铁站台": "地铁站台",
        "清晨的厨房": "厨房",
        "清晨的公交站": "公交站",
        "午后的晒台": "晒台",
        "清晨的烘焙间": "烘焙间",
    },
    # outfit 自带鞋款 → 与 shoes 分项重复（P7）
    "outfit": {
        "针织连衣裙配短靴": "针织连衣裙",
        "驼色针织裙配长靴": "驼色针织裙",
        "黑色针织连衣裙配长靴": "黑色针织连衣裙",
        "米色两件套配平底鞋": "米色两件套",
        "浅灰长款毛衣配长靴": "浅灰长款毛衣",
        "深蓝色连衣裙配白鞋": "深蓝色连衣裙",
    },
    # 强时段光线 → 与 time 分项冲突（P6）
    "light": {
        "晨光从窗棂斜射进来": "光从窗棂斜射进来",
        "烛光在墙上晃": "暖黄的光在墙上晃",
        "月光铺在地板上": "冷调的光铺在地板上",
        "路灯的暖光从头顶落下": "头顶落下一片暖光",
        "夕阳照在后颈上": "橙色的光落在后颈",
        "屏幕的冷光": "冷白的光打在侧脸",
    },
    # 偏女性的状态（P11）
    "man_state": {"衣角被风掀起": "外套下摆被风掀起"},
}

# bust 里混进的姿态词，直接删（池共 16 条，删 4 条仍达标）
DROP["bust"] = ["坐姿端正", "站姿挺拔", "腰背挺直", "肩膀放松"]


def main():
    with open(TARGET, encoding="utf-8") as handle:
        data = json.load(handle)

    pools = data["pools"]

    for name, words in DROP.items():
        if name not in pools:
            continue
        pools[name] = [w["text"] if isinstance(w, dict) else w for w in pools[name]
                       if (w.get("text") if isinstance(w, dict) else w) not in words]

    for name, mapping in RENAME.items():
        if name not in pools or not mapping:
            continue
        for word in pools[name]:
            if isinstance(word, dict):
                word["text"] = mapping.get(word["text"], word["text"])
            else:
                pools[name][pools[name].index(word)] = mapping.get(word, word)

    # 删掉裤装与外套类状态后掉到 11 条，补中性状态（不指名穿着什么）
    ADD = {"outfit_state": ["衣料被风吹起", "袖口微湿"]}
    for name, words in ADD.items():
        for word in words:
            if word not in pools[name]:
                pools[name].append(word)

    # 改名后可能出现同池重复，出问题比原样更糟，直接拦。
    for name, words in pools.items():
        texts = [w["text"] if isinstance(w, dict) else w for w in words]
        dupes = {t for t in texts if texts.count(t) > 1}
        if dupes:
            raise SystemExit(f"{name} 改名后出现重复：{sorted(dupes)}")

    data["version"] = "0.9.8.8382-sfw-b7"

    with open(TARGET, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    total = sum(len(v) for v in pools.values())
    print(f"池 {len(pools)} 个，词条 {total} 条")
    for name in ("outfit_state", "bust", "chore", "light", "place"):
        print(f"  {name}: {len(pools[name])}")


if __name__ == "__main__":
    main()
