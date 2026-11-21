#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFW 词库体检。

读 tools/forbidden_nsfw.txt（唯一禁词来源）与 tools/allow_sfw.txt，
检查 assets 下的 tag_catalog_sfw.json 与 story_catalog_sfw.json。

设计取舍：
- 禁词表不写进本脚本。脚本里再抄一份正则，必然与运行时那份漂移，
  最后就是「脚本说 0 错、App 还在抽」。
- 白名单按【完整词条】放行，不按词。按词放行等于在禁词表上打洞。
- 出口只有一个：有 error 就非 0，构建脚本据此拦。

用法：
    python tools/check_sfw.py [--assets app/src/main/assets] [--quiet]
"""

import argparse
import json
import os
import re
import sys

# 人数与取景是枚举，不是可扩充的池，天然到不了 12 条。
SMALL_POOL_OK = {"people", "camera", "age"}
MIN_POOL = 12

# StoryGenerator 模板替换表里真实存在的占位符。表外的占位符不会被替换，
# 会以字面量留在提示词里——{light} 就是典型，写进去等于给模型一句乱码。
ALLOWED_PLACEHOLDERS = {
    "time", "cast", "female", "look_f", "look_m", "place_b", "place",
    "outfit_b", "outfit", "socks", "shoes", "state", "outfit_mid", "chore",
    "male", "escalate", "pose", "prop", "acts", "detail", "world", "light",
}

MULTI_MAN = re.compile(r"两男|三男|多男|几个男")
LATIN_RUN = re.compile(r"[A-Za-z]{3,}")
# 短英文 token 是品牌/版型名的一部分，不算「英文裸词」。
LATIN_OK = {"logo", "T恤", "V领", "Tshirt"}


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.allowed_hits = 0
        self.checked = 0

    def error(self, where, msg):
        self.errors.append(f"[错误] {where}: {msg}")

    def warn(self, where, msg):
        self.warnings.append(f"[警告] {where}: {msg}")


def read_lines(path):
    out = []
    with open(path, encoding="utf-8") as handle:
        for raw in handle:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            out.append(line)
    return out


def load_json(path, report):
    if not os.path.exists(path):
        report.error(os.path.basename(path), "文件不存在")
        return None
    with open(path, encoding="utf-8") as handle:
        try:
            return json.load(handle)
        except Exception as exc:  # noqa: BLE001 - 报位置比抛栈有用
            report.error(os.path.basename(path), f"JSON 解析失败：{exc}")
            return None


def check_words(words, where, forbidden, allow, report):
    """逐条过禁词；命中后看整条是否在白名单里。"""
    for word in words:
        report.checked += 1
        hit = next((bad for bad in forbidden if bad in word), None)
        if hit is None:
            continue
        if word in allow:
            report.allowed_hits += 1
            continue
        report.error(where, f"命中禁词「{hit}」：{word}")


def check_tag_library(data, report, forbidden, allow):
    if data is None:
        return
    meta = data.get("meta") or {}
    if meta.get("profile") != "sfw":
        report.error("tag.meta", f"profile 应为 sfw，实际 {meta.get('profile')!r}")
    if meta.get("rating") != "general":
        report.error("tag.meta", f"rating 应为 general，实际 {meta.get('rating')!r}")

    categories = data.get("categories") or []
    if not categories:
        report.error("tag", "categories 为空")
        return

    ids = [c.get("id") for c in categories]
    duplicated = {i for i in ids if ids.count(i) > 1}
    if duplicated:
        report.error("tag.categories", f"分类 id 重复：{sorted(duplicated)}")
    known = set(ids)

    seen_word = {}
    for category in categories:
        cid = category.get("id", "?")
        label = category.get("label", "")
        if not label:
            report.error(f"tag.{cid}", "缺少 label")
        for slot in category.get("slots") or []:
            sid = slot.get("id", "?")
            pool = slot.get("pool") or []
            where = f"tag.{cid}.{sid}"
            if not pool:
                report.error(where, "池为空")
                continue
            if len(pool) < MIN_POOL and cid not in SMALL_POOL_OK:
                report.error(where, f"只有 {len(pool)} 条，低于 {MIN_POOL}（重抽会明显重复）")
            if len(set(pool)) != len(pool):
                dup = sorted({w for w in pool if pool.count(w) > 1})
                report.error(where, f"同一池内重复：{dup}")
            check_words(pool, where, forbidden, allow, report)

            for word in pool:
                if MULTI_MAN.search(word):
                    report.error(where, f"多人结构里出现多男：{word}")
                if LATIN_RUN.search(word) and word not in LATIN_OK:
                    report.warn(where, f"词条含英文裸词：{word}")

                # 跨分类重复：同形词落在两个分类，会在同一句里写两遍。
                previous = seen_word.get(word)
                if previous and previous != cid:
                    report.error(where, f"跨分类重复「{word}」（已在 {previous}）")
                else:
                    seen_word[word] = cid

    all_words = {w for c in categories for s in (c.get("slots") or []) for w in (s.get("pool") or [])}

    # skipCategories 的元素是 {whenAny, skip, skipWords}，不是分类 id 列表。
    rules = data.get("rules") or {}
    garment_words = {
        w
        for c in categories if c.get("id") == "garment"
        for s in (c.get("slots") or []) for w in (s.get("pool") or [])
    }
    covered = set()
    for rule in rules.get("skipCategories") or []:
        if not isinstance(rule, dict):
            report.error("tag.rules.skipCategories", f"规则应为对象，实际 {type(rule).__name__}")
            continue
        for cid in rule.get("skip") or []:
            # 「分类.格」只关掉一格（异族不叠身份），整段分类名才关掉整个分类。
            base, _, slot = cid.partition(".")
            if base not in known:
                report.error("tag.rules.skipCategories", f"skip 引用了不存在的分类 {cid}")
            elif slot:
                slots = {
                    s.get("id")
                    for c in categories if c.get("id") == base
                    for s in (c.get("slots") or [])
                }
                if slot not in slots:
                    report.error("tag.rules.skipCategories", f"skip 引用了不存在的格 {cid}")
        triggered = set(rule.get("whenAny") or [])
        covered |= triggered
        for word in triggered:
            if word not in all_words:
                report.error("tag.rules.skipCategories", f"whenAny 里的「{word}」不在任何池里")

    # 连身装与上衣/下衣必须互斥，否则会抽出「连衣裙 + 白衬衫」这种穿不上的组合。
    if garment_words:
        for rule in rules.get("skipCategories") or []:
            if not isinstance(rule, dict):
                continue
            if {"top", "bottom"} <= set(rule.get("skip") or []):
                covered |= set(rule.get("whenAny") or [])
        missing = sorted(garment_words - covered)
        if missing:
            report.error(
                "tag.rules.skipCategories",
                f"连身装没有配「跳过上衣+下衣」的规则：{missing[:3]}",
            )

    constraints = data.get("constraints") or {}
    counts = constraints.get("peopleCount") or {}
    for word, count in counts.items():
        if not isinstance(count, int) or count < 1:
            report.error("tag.constraints.peopleCount", f"{word} 的人数不是正整数：{count}")
        elif count > 3:
            report.error("tag.constraints.peopleCount", f"{word} 人数 {count}，SFW 上限为 3")

    hand_cost = constraints.get("handCost") or {}
    all_words = {w for c in categories for s in (c.get("slots") or []) for w in (s.get("pool") or [])}
    for word in hand_cost:
        if word not in all_words:
            report.error("tag.constraints.handCost", f"「{word}」不在任何池里")

    tiers = meta.get("promptTiers") or {}
    for index, tier in tiers.items():
        for cid in tier:
            if cid not in known:
                report.error("tag.meta.promptTiers", f"第 {index} 层引用了不存在的分类 {cid}")

    # 强绑定着装必须在池里配套场景，否则泳装会出现在办公室。
    BOUND = {"泳装": ("海边", "泳池", "沙滩", "游艇", "浴室"), "比基尼": ("海边", "泳池", "沙滩", "游艇")}
    scene_words = " ".join(
        w for c in categories if c.get("id") in ("scene", "place")
        for s in (c.get("slots") or []) for w in (s.get("pool") or [])
    )
    for binding, scenes in BOUND.items():
        if any(binding in w for w in all_words) and not any(s in scene_words for s in scenes):
            report.error("tag.outfit", f"有「{binding}」却没有对应场景词（需要{'/'.join(scenes)}之一）")


def check_story_library(data, report, forbidden, allow):
    if data is None:
        return
    if data.get("profile") != "sfw":
        report.error("story", f"profile 应为 sfw，实际 {data.get('profile')!r}")
    if data.get("rating") != "general":
        report.error("story", f"rating 应为 general，实际 {data.get('rating')!r}")

    casts = data.get("casts") or {}
    pools = data.get("pools") or {}
    templates = data.get("templates") or {}
    if not casts:
        report.error("story.casts", "为空")
    if not pools:
        report.error("story.pools", "为空")
    if not templates:
        report.error("story.templates", "为空")

    for name, words in pools.items():
        plain = [w if isinstance(w, str) else (w.get("text") or "") for w in words]
        where = f"story.{name}"
        if len(plain) < MIN_POOL and name not in SMALL_POOL_OK:
            report.error(where, f"只有 {len(plain)} 条，低于 {MIN_POOL}")
        check_words([w for w in plain if w], where, forbidden, allow, report)

    for cast, lines in templates.items():
        if cast not in casts:
            report.error("story.templates", f"{cast} 没有对应的 cast")
        for line in lines:
            used = set(re.findall(r"\{(\w+)\}", line))
            unknown = used - ALLOWED_PLACEHOLDERS
            if unknown:
                report.error("story.templates", f"{cast} 用了模板不支持的占位符 {sorted(unknown)}")
            if "place" not in used:
                report.warn("story.templates", f"{cast} 的句子没有地点")
            static = re.sub(r"\{\w+\}", "", line)
            check_words([static], f"story.templates.{cast}", forbidden, allow, report)


def sample_render(data, forbidden, rounds=500, seed=7):
    """按真实出词顺序随机拼 500 组，逐组过禁词。

    全量并集干净不等于「这一次抽出来干净」——并集是静态的，拼接是动态的：
    单看两条都合规，拼在一起才撞出问题。这一步就是补那个缺口。
    """
    import random

    rng = random.Random(seed)
    tiers = (data.get("meta") or {}).get("promptTiers") or {}
    cats = {c.get("id"): c for c in data.get("categories") or []}
    bad = []
    for _ in range(rounds):
        parts = []
        for index in sorted(tiers, key=lambda k: int(k)):
            ids = [cid for cid in tiers[index] if cid in cats]
            pool = [w for cid in ids for s in (cats[cid].get("slots") or []) for w in (s.get("pool") or [])]
            if pool:
                parts.append(rng.choice(pool))
        line = "，".join(parts)
        hit = next((word for word in forbidden if word in line), None)
        if hit is not None:
            bad.append(f"「{hit}」→ {line}")
    return bad


def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", default=os.path.join(root, "app", "src", "main", "assets"))
    parser.add_argument("--tools", default=os.path.join(root, "tools"))
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    report = Report()
    forbidden = read_lines(os.path.join(args.tools, "forbidden_nsfw.txt"))
    allow = set(read_lines(os.path.join(args.tools, "allow_sfw.txt")))
    if not forbidden:
        report.error("forbidden_nsfw.txt", "禁词表为空，这项检查会变成摆设")

    tag_data = load_json(os.path.join(args.assets, "tag_catalog_sfw.json"), report)
    check_tag_library(tag_data, report, forbidden, allow)
    if tag_data is not None:
        for line in sample_render(tag_data, forbidden):
            report.error("渲染抽样", line)
    check_story_library(
        load_json(os.path.join(args.assets, "story_catalog_sfw.json"), report),
        report, forbidden, allow,
    )

    if report.checked and report.allowed_hits > report.checked * 0.03:
        report.warn(
            "allow_sfw.txt",
            f"放行 {report.allowed_hits} 条，超过总词条的 3%，白名单该复核了",
        )

    if not args.quiet:
        for line in report.errors:
            print(line)
        for line in report.warnings:
            print(line)
        print(f"检查 {report.checked} 条词，错误 {len(report.errors)}，警告 {len(report.warnings)}")

    return 1 if report.errors else 0


if __name__ == "__main__":
    sys.exit(main())
