#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""B8：用剧情库现成的 groups / casts 机制做场景与性别配对。

不新增规则层——StoryGenerator 已经有完整的过滤链：
  world.groups 与词条 groups 配对（place）
  place 的组决定其余分项的 group，词条 groups 不含该组就排除
  casts 限定适用的人数结构
  三级回退（strict → loose → 最宽松）保证标错不会把池抽空

所以这里只做数据标注，风险比改引擎低得多。

分组只用三个粗标签：in（室内）/ out（户外）/ water（水边）。
太细的标签反而会因为标错把词全部过滤掉。
"""

import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "story_catalog_sfw.json")

# 只限定「性别明确」的种族。天使、吸血鬼、精灵可男可女，不限定。
RACE_CASTS = {
    "魅魔": ["mf", "mff", "ff"],
    "雪女": ["mf", "mff", "ff"],
}

# 场景归类：先看这些关键词，命中的按表走，其余保持不限定（放行一切）。
PLACE_RULES = [
    (("泳池", "沙滩", "海边", "码头", "防波堤", "湖", "河", "船"), ["out", "water"]),
    (("街", "路", "巷", "桥", "天台", "屋顶", "公园", "林", "庭", "田", "山", "崖", "站台", "站", "市场", "巷口"), ["out"]),
    (("厨房", "卧室", "书房", "客厅", "玄关", "浴室", "走廊", "楼梯", "阳台", "床", "沙发"), ["in", "home"]),
    (("咖啡", "书店", "面包", "甜品", "酒馆", "茶室", "唱片", "便利店", "理发", "裁缝", "陶艺", "画室", "琴房", "食堂", "教室", "实验室"), ["in", "shop"]),
    (("办公", "会议", "茶水间", "工位", "复印", "电梯", "商场的扶梯", "试衣间", "后台", "化妆间", "更衣室", "健身房", "体育馆", "医院", "诊所", "药房"), ["in"]),
    (("美术馆", "剧院", "音乐厅", "图书馆", "电影院的最后一排", "舞台", "乐池", "寺", "禅院", "温泉", "和室", "榻榻米", "竹林的入口", "古城墙"), ["in"]),
]

# 只在室内成立的家务/办公动作；户外场地不该出现。
CHORE_INDOOR = (
    "铺床", "叠被", "枕头", "碗碟", "倒牛奶", "切菜", "面包切片", "冲咖啡", "煮", "水壶",
    "擦窗", "擦镜", "抹布", "抽屉", "冰箱", "衣柜", "挂上衣架", "叠好", "叠成", "铺好桌布",
    "点蜡烛", "落地灯", "台灯", "暖气", "地毯", "靠垫", "洗衣机", "晾衣绳", "拖鞋", "浴巾",
    "床单", "被角", "枕头拍", "熨", "打字", "复印", "打卡", "开会", "白板",
)

CHORE_OUTDOOR = (
    "接雨", "抖落外套上的雨", "搬上推车", "推上坡", "自行车打气", "把车推", "收伞",
    "把伞", "靠在墙边", "晾衣绳拉紧", "把夹子", "扫成一小堆",
)

# 台灯、屏幕、烛火只在室内；阳光、霓虹、路灯、月光属户外。
LIGHT_INDOOR = ("台灯", "屏幕", "窗棂", "烛光", "烛火", "暖黄的光", "窗光", "走廊尽头", "吊灯", "落地灯")
LIGHT_OUTDOOR = ("晨光", "正午", "午后", "夕阳", "霓虹", "路灯", "月光", "逆光", "碎光", "水面的反光", "头顶落下一片暖光")


def words_of(pool):
    return [w if isinstance(w, dict) else {"text": w} for w in pool]


def tag(pool, assign):
    for word in pool:
        groups = assign(word["text"])
        if groups:
            word["groups"] = groups


def main():
    with open(TARGET, encoding="utf-8") as handle:
        data = json.load(handle)

    pools = {name: words_of(v) for name, v in data["pools"].items()}

    # 1) 种族性别：魅魔、雪女只在含女性的结构里出现
    for word in pools["ethnicity"]:
        casts = RACE_CASTS.get(word["text"])
        if casts:
            word["casts"] = casts

    # 2) 场景分类
    def place_groups(text):
        for keys, groups in PLACE_RULES:
            if any(k in text for k in keys):
                return groups
        return []

    tag(pools["place"], place_groups)

    # 3) 日常动作：把强绑定室内的挑出来，其余放行
    def chore_groups(text):
        if any(k in text for k in CHORE_OUTDOOR):
            return ["out"]
        if any(k in text for k in CHORE_INDOOR):
            return ["in"]
        return []

    tag(pools["chore"], chore_groups)

    # 4) 光线：室内光源与户外光源分开
    def light_groups(text):
        if any(k in text for k in LIGHT_INDOOR):
            return ["in"]
        if any(k in text for k in LIGHT_OUTDOOR):
            return ["out"]
        return []

    tag(pools["light"], light_groups)

    # 5) 题材：把 world 归到室内/户外，place 若与之不交会被排除
    WORLD_GROUPS = {
        "和风町屋": ["in"], "改造厂房": ["in"], "老式公寓": ["in"],
        "沙丘绿洲": ["out"], "雨林边缘": ["out"], "海边渔村": ["out", "water"],
        "山间木屋": ["out"], "热带海岛": ["out", "water"], "雪国村落": ["out"],
        "现代都市": ["in", "out"], "欧陆小镇": ["in", "out"],
        "江南水乡": ["out", "water"], "北欧小镇": ["in", "out"],
        "地中海小镇": ["in", "out"], "复古街区": ["out"], "近未来都市": ["in", "out"],
    }
    for word in pools["world"]:
        groups = WORLD_GROUPS.get(word["text"])
        if groups:
            word["groups"] = groups

    # 写回时保持字符串池的原样：没有分组的词仍写成纯字符串，别让文件膨胀
    for name, words in pools.items():
        data["pools"][name] = [
            w["text"] if set(w) == {"text"} else w for w in words
        ]

    data["version"] = "0.9.8.8382-sfw-b8"

    with open(TARGET, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    tagged = {n: sum(1 for w in v if isinstance(w, dict)) for n, v in data["pools"].items()}
    print("已标注：", {k: v for k, v in tagged.items() if v})


if __name__ == "__main__":
    main()
