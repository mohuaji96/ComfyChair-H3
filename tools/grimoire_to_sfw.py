#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 grimoire 的可用户词压成词库能用的短词，并分派到 SFW 分类。

grimoire 的中文是英文式描述句（「辽阔的海洋映衬戏剧性日落天空」），
词库要的是可画的名词短语（「辽阔海面与日落天空」）。纯规则压不干净，
所以这里做两件事：规则先去掉评价词与修饰从句，再用人工映射表修重点条目。

产出 tools/candidates_sfw_ready.json，不直接改词库。
"""

import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF = os.path.join(ROOT, "tools", "candidates_grimoire.json")
OUT = os.path.join(ROOT, "tools", "candidates_sfw_ready.json")

GRIMOIRE = r"C:\ascii-build\refs\_______-___comfyui-super-grimoire-1.0.83\comfyui-super-grimoire-1.0.83\data\tags.json"

# 评价词与空转词：画不进画面，先删
FILLER = (
    "漂亮的", "美丽的", "优雅的", "酷炫的", "可爱的", "神秘的", "舒适的", "温暖的",
    "干净的", "精致的", "柔和的", "轻轻地", "缓缓地", "完美地", "非常", "十分",
)
TAIL_CUT = ("，", ",", "。", ".", "；", ";")

# 重点条目的手工改写：规则做不到语义级压缩
MANUAL = {
    "辽阔的海洋映衬戏剧性日落天空": "辽阔海面与日落天空",
    "雪山巍峨耸立于清澈蓝天之下": "雪山与清澈蓝天",
    "宁静的湖泊如镜面倒映周围的松林": "镜面般的湖泊与松林",
    "金色麦田在夏日微风中轻轻摇曳": "夏日微风中的金色麦田",
    "茂密竹林，阳光从叶片间洒落": "阳光洒落的茂密竹林",
    "薰衣草花田延伸至地平线，紫色波浪": "延伸到地平线的薰衣草田",
    "盛开的樱花公园，粉色花瓣飘落": "樱花飘落的公园",
    "秋日的森林，金黄与红叶缓缓飘落": "落叶纷飞的秋日森林",
    "冬日雪境，一切覆盖在纯净白色中": "覆雪的冬日原野",
    "热带海滩，碧蓝水晶海水配白沙滩": "碧海白沙的热带海滩",
    "神秘的深洞，洞壁生满发光水晶": "洞壁生满发光水晶的深洞",
    "巨大的瀑布从悬崖倾泻而下坠入雾潭": "悬崖瀑布与雾气水潭",
    "日式校服，水手领配百褶裙": "水手领上衣配百褶裙",
    "干净白衬衫配蓝色百褶裙，学院风": "白衬衫配蓝百褶裙",
    "宽松的超大卫衣，舒适休闲": "宽松超大号卫衣",
    "休闲T恤和牛仔裤，轻松的日常风格": "白T恤配牛仔裤",
    "漂亮的碎花夏季连衣裙，轻盈飘逸": "碎花夏季连衣裙",
    "经典风衣套在优雅装束外": "经典双排扣风衣",
    "针织毛衣，秋日温暖舒适": "秋日粗针织毛衣",
    "带兜帽的斗篷，神秘中世纪感": "带兜帽的中世纪斗篷",
    "运动套装，现代休闲运动装": "现代运动套装",
    "牛仔夹克，酷炫前卫的街头风格": "街头风牛仔夹克",
    "背心配短裤，准备迎接夏日": "背心配短裤",
    "可爱图案的睡衣，舒适就寝氛围": "印花睡衣",
}

# 子类 -> 目标分类（None 表示丢弃）
ROUTE = {
    "日常物品": "propObject",
    "静态姿态": "pose",
    "动态动作": "activity",
    "互动姿态": "interaction",
    "手部姿态": "handAction",
    "表情": "expression",
    "面部细节": "featureHead",
    "脸型": "featureHead",
    "妆容": "makeup",
    "日常服装": "top",
    "特殊服饰": "garment",
    "鞋袜": "socks",
    "配饰装饰": "headwear",
    "自然风景": "scene",
    "城市建筑": "scene",
    "室内场景": "scene",
}

# 这些子类整块不要
DROP_SUBS = {"武器", "奇幻物品", "战甲武装", "动漫画风", "游戏画风"}


def assign(sub, text):
    """鞋袜、配饰、天空天气、眼睛这几类要按词再细分一次，否则会一股脑进错桶。"""
    if sub == "鞋袜":
        if "袜" in text:
            return "socks"
        if any(k in text for k in ("鞋", "靴")):
            return "shoes"
        return "socks"
    if sub == "配饰装饰":
        if any(k in text for k in ("项链", "耳环", "耳钉", "戒指", "手链", "手镯", "胸针", "吊坠")):
            return "jewelry"
        return "headwear"
    if sub == "天空天气":
        if any(k in text for k in ("光", "阳光", "月光", "霓虹", "烛", "灯", "暮", "晨", "夕阳")):
            return "light"
        return "weather"
    if sub == "眼睛":
        return "eyeColor" if ("眼眸" in text or "瞳" in text) else "featureHead"
    return ROUTE.get(sub)


def compress(text):
    s = text.strip()
    if s in MANUAL:
        return MANUAL[s]
    for t in TAIL_CUT:
        if t in s:
            s = s.split(t)[0]
            break
    for f in FILLER:
        s = s.replace(f, "")
    s = re.sub(r"\s+", "", s)
    return s[:14]


def load_grimoire():
    with open(GRIMOIRE, encoding="utf-8") as handle:
        return json.load(handle)


def main():
    data = load_grimoire()
    ready = {}

    for category in data["categories"]:
        for sub in category["subcategories"]:
            name = sub["name"]
            if name in DROP_SUBS:
                continue
            target = assign(name, "")
            if target is None:
                continue
            for tag in sub.get("tags") or []:
                word = compress(tag.get("zh", ""))
                if not (2 <= len(word) <= 14):
                    continue
                slot = assign(name, tag.get("zh", ""))
                if slot is None:
                    continue
                ready.setdefault(slot, []).append({"text": word, "src": name})

    # 去重：桶内 + 跨桶
    seen = {}
    for target, items in ready.items():
        unique = []
        for item in items:
            key = item["text"]
            if key in seen:
                continue
            seen[key] = target
            unique.append(item)
        ready[target] = unique

    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(ready, handle, ensure_ascii=False, indent=1)

    total = sum(len(v) for v in ready.values())
    print(f"分派完成，{len(ready)} 个分类，合计 {total} 条")
    for target, items in sorted(ready.items(), key=lambda kv: -len(kv[1])):
        print(f"  {target}: {len(items)}")
        print("     " + "、".join(x["text"] for x in items[:6]))


if __name__ == "__main__":
    main()
