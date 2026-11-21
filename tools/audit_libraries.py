#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""随机组合审计：把四个词库各随机抽 N 组，检查机械冲突。

check_sfw.py 只看静态结构（禁词、池下限、跨池重复），看不出「抽出来这句话有没有毛病」。
这个脚本模拟真实出词顺序，把组合层面的冲突挑出来。

它查不出审美与语感，只查能被规则判定的矛盾：
  同句重复 / 性别错配 / 人数错配 / 服装矛盾 / 场景错配 /
  时间与光线 / 季节与天气 / 手部超预算 / 池下限

用法：
    python tools/audit_libraries.py [--rounds 500]
"""

import argparse
import collections
import json
import os
import random

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

TAG_LIBS = {
    "tag_nsfw": ("tag_catalog.json", "mature"),
    "tag_sfw": ("tag_catalog_sfw.json", "general"),
}
STORY_LIBS = {
    "story_nsfw": ("story_catalog.json", "mature"),
    "story_sfw": ("story_catalog_sfw.json", "general"),
}

# 时间与光线：句首时段与光线词不能打架
DAY_PARTS = {
    "清晨": ["晨光", "早晨", "清晨"],
    "上午": ["上午", "晨光"],
    "正午": ["正午", "顶光"],
    "午后": ["午后", "斜阳"],
    "下午": ["下午", "午后"],
    "黄昏": ["黄昏", "夕阳", "日落"],
    "夜": ["夜", "月", "灯", "烛", "霓虹"],
}
LIGHT_FITS = {
    "夕阳": ["黄昏", "傍晚", "日落", "午后"],
    "月光": ["夜"],
    "路灯": ["夜", "傍晚", "黄昏"],
    "烛光": ["夜", "黄昏"],
    "霓虹": ["夜", "傍晚"],
    "屏幕": ["夜", "夜里"],
    "晨光": ["清晨", "早晨", "上午"],
    "顶光": ["正午"],
}

# 季节与天气
SEASON_CONFLICT = {
    "暮春": ["雪", "霜", "闷热"],
    "早春": ["雪", "闷热"],
    "盛夏": ["雪", "霜", "初雪"],
    "深秋": ["雪", "梅雨"],
    "初冬": ["闷热", "梅雨"],
    "隆冬": ["闷热", "梅雨"],
    "秋分": ["雪", "梅雨"],
}

PANTS_STATE = ("裤腰", "裤脚", "衬衫下摆")
DUO_HINTS = ("一起", "并肩", "互相", "十指相扣", "手挽", "共撑", "背靠背", "递过", "替对方", "从背后抱")
COLOR_WORDS = {"黑色","白色","灰色","粉色","红色","橙色","蓝色","紫色","金色","银色","棕色",
               "米色","浅粉","玫红","酒红","浅蓝","深蓝","绿色","军绿","藏蓝","杏色","焦糖色","雾霾蓝","薄荷绿","奶油色","雾粉","雾灰","雾蓝","燕麦色","奶茶色","透明","半透明"}


def text_of(word):
    return word["text"] if isinstance(word, dict) else word


def pool_of(category):
    return [text_of(w) for s in category.get("slots") or [] for w in (s.get("pool") or [])]


def load(name):
    with open(os.path.join(ASSETS, name), encoding="utf-8") as handle:
        return json.load(handle)


def sample_tag(data, rng):
    cats = {c["id"]: c for c in data["categories"]}
    tiers = (data.get("meta") or {}).get("promptTiers") or {}
    rules = (data.get("rules") or {}).get("skipCategories") or []
    constraints = data.get("constraints") or {}
    male_people = constraints.get("malePeople") or []
    hand_cost = constraints.get("handCost") or {}
    hand_cap = constraints.get("handCap") or 2
    people_map = constraints.get("people") or {}
    people_count = constraints.get("peopleCount") or {}

    picks = {}
    # 人数最先抽：who 门控要读它。不先定人数就抽，等于自己造出一堆引擎不会出的组合。
    for cid in ("people", "person"):
        if cid in cats and pool_of(cats[cid]):
            picks[cid] = rng.choice(pool_of(cats[cid]))

    people = picks.get("people", "")
    has_male = ("男" in people) or (people in male_people)
    head_count = people_count.get(people, 0)

    for tier in sorted(tiers, key=lambda k: int(k)):
        for cid in tiers[tier]:
            if cid in picks:
                continue
            category = cats.get(cid)
            if not category:
                continue
            # 引擎的男性门控：没有男性时整类跳过。脚本不模拟它，就会把
            # 「单人女孩 + maleBody」当成冲突报出来——那是误报。
            if category.get("who") == "male" and not has_male:
                continue
            pool = pool_of(category)
            if not pool:
                continue
            # 人数门控：词条标了 people 就按当前人数筛。性行为词标 2，
            # 单人组合里就不该出现「互相磨蹭」这种。
            if head_count:
                pool = [w for w in pool if people_map.get(w) in (None, head_count)] or pool
            picks[cid] = rng.choice(pool)

    # 规则跳过：whenAny 命中任一已选值
    skipped = set()
    for rule in rules:
        if any(w in picks.values() for w in rule.get("whenAny") or []):
            skipped.update(rule.get("skip") or [])

    people = picks.get("people", "")
    has_male = ("男" in people) or (people in male_people)

    issues = []
    if people == "单人男性":
        female_only = ("makeup", "garment", "hips", "waist", "bust", "featureHead", "breasts", "bra", "panties", "pussy")
        for cid in female_only:
            if cid in picks and cid not in skipped:
                issues.append(("性别错配", f"单人男性 + {cid}={picks[cid]}"))
    # 男性分类出现在没有男性的组合里
    if not has_male:
        for cid in ("maleBody", "maleFeature", "penis"):
            if cid in picks and cid not in skipped:
                issues.append(("男性分类", f"{people or '无人'} + {cid}={picks[cid]}"))
    # 双人互动出现在单人
    if people in ("单人女性", "单人男性"):
        for cid, word in picks.items():
            if any(h in word for h in DUO_HINTS) and "在一起" not in word and cid not in skipped:
                issues.append(("人数错配", f"{people} + {word}"))
    # 服装矛盾
    garment = picks.get("garment", "")
    state = picks.get("outfitState", "")
    if garment and state and state not in skipped and any(k in state for k in PANTS_STATE):
        issues.append(("服装矛盾", f"{garment} + {state}"))
    # 手部预算：引擎会削掉超出的词，这里照做，只报「削完仍然超」的情况。
    used = sum(hand_cost.get(w, 0) for w in picks.values())
    if used > hand_cap:
        for cid in reversed([k for k in picks if hand_cost.get(picks[k], 0) > 0]):
            if used <= hand_cap:
                break
            used -= hand_cost.get(picks[cid], 0)
            picks.pop(cid, None)
    if used > hand_cap:
        issues.append(("手部超预算", f"{used}>{hand_cap}"))
    # 同句重复
    # 颜色词在各分类各出现一次是设计（头发/上衣/袜子各有「黑色」），不是重复
    dupes = [w for w, n in collections.Counter(picks.values()).items()
             if n > 1 and w not in COLOR_WORDS]
    if dupes:
        issues.append(("同句重复", "、".join(dupes)))
    return picks, issues


def sample_story(data, rng, cast_key=None):
    casts = data.get("casts") or {}
    templates = data.get("templates") or {}
    pools = data.get("pools") or {}
    cast = cast_key or rng.choice(list(templates.keys()))
    lines = templates.get(cast) or []
    line = rng.choice(lines)

    picks = {}

    def choose(field, groups=None):
        pool = [w for w in (pools.get(field) or []) if text_of(w)]
        allowed = []
        for w in pool:
            if isinstance(w, dict):
                if w.get("casts") and cast not in w["casts"]:
                    continue
                # 场景分组：引擎会按 place 的组过滤，脚本不模拟就会误报
                if groups and w.get("groups") and not (set(w["groups"]) & groups):
                    continue
            allowed.append(text_of(w))
        return rng.choice(allowed) if allowed else None

    # 先定场景：其余分项要按它归属的组过滤
    for field in ("world", "time", "place"):
        value = choose(field)
        if value:
            picks[field] = value

    place_entry = next(
        (w for w in (pools.get("place") or []) if text_of(w) == picks.get("place")), None
    )
    groups = set(place_entry["groups"]) if isinstance(place_entry, dict) and place_entry.get("groups") else set()

    for field in ("chore", "pose", "detail", "light", "outfit", "outfit_state", "socks",
                  "shoes", "ethnicity", "hair", "build", "bust", "skin", "accent", "style",
                  "man_look", "man_outfit", "man_state", "prop", "outfit_mid", "escalate"):
        value = choose(field, groups)
        if value:
            picks[field] = value

    issues = []
    place = picks.get("place", "")
    # 场景与动作：只报最明显的室内/户外错配
    indoor_chore = ("铺床", "叠被", "枕头", "床单", "被角", "碗碟", "擦镜", "洗衣")
    outdoor_place = ("海边", "沙滩", "草地", "田埂", "防波堤", "码头", "山道", "公园", "街")
    if any(k in place for k in outdoor_place):
        chore = picks.get("chore", "")
        if any(k in chore for k in indoor_chore):
            issues.append(("场景错配", f"{place} + {chore}"))

    # 服装矛盾
    outfit = picks.get("outfit", "")
    state = picks.get("outfit_state", "")
    if any(k in outfit for k in ("连衣裙", "长裙", "连体")) and any(k in state for k in PANTS_STATE):
        issues.append(("服装矛盾", f"{outfit} + {state}"))

    # 时间与光线
    time = picks.get("time", "")
    light = picks.get("light", "")
    if time and light:
        for word, fits in LIGHT_FITS.items():
            if word in light and not any(f in time for f in fits):
                issues.append(("时间光线", f"{time} + {light}"))
                break

    # 季节与天气
    season = picks.get("season", "")
    weather = picks.get("weather", "")
    if season and weather:
        for word, bad in SEASON_CONFLICT.items():
            if word in season and any(b in weather for b in bad):
                issues.append(("季节天气", f"{season} + {weather}"))
                break

    # 同句重复：同一句里出现两次同一个词
    body = line
    for field, word in picks.items():
        body = body.replace("{" + field + "}", word)
    dupes = [w for w, n in collections.Counter(picks.values()).items() if n > 1]
    if dupes:
        issues.append(("同句重复", "、".join(dupes)))

    return picks, issues, body


def pad(vec, width=0):
    return vec


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rounds", type=int, default=500)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    summary = []

    for name, (filename, rating) in TAG_LIBS.items():
        data = load(filename)
        hits = collections.Counter()
        examples = {}
        for _ in range(args.rounds):
            picks, issues = sample_tag(data, rng)
            for kind, detail in issues:
                hits[kind] += 1
                examples.setdefault(kind, detail)
        summary.append((name, args.rounds, hits, examples))

    for name, (filename, rating) in STORY_LIBS.items():
        data = load(filename)
        hits = collections.Counter()
        examples = {}
        for _ in range(args.rounds):
            _, issues, _ = sample_story(data, rng)
            for kind, detail in issues:
                hits[kind] += 1
                examples.setdefault(kind, detail)
        summary.append((name, args.rounds, hits, examples))

    print("=" * 68)
    for name, rounds, hits, examples in summary:
        total = sum(hits.values())
        print(f"{name}  抽 {rounds} 组，命中 {total} 处")
        if not hits:
            print("   无冲突")
        for kind, count in hits.most_common():
            print(f"   {kind:<8} {count:>4} 次   例：{examples[kind][:90]}")
        print("-" * 68)


if __name__ == "__main__":
    main()
