# -*- coding: utf-8 -*-
"""四个词库 + 引擎约束的静态体检。

check_sfw.py 只覆盖 SFW 两库，NSFW 库和引擎层从来没有检查过，于是下面这些问题
都是靠用户装机后肉眼发现的：

  - 锁定一男一女后头部姿态 300 次全是「下巴抵在肩上」（人数独占）
  - 168 条 requires=["男"] 永远不成立，一条都抽不到（死依赖）
  - 「他的脸」被 rest 兜底和 maleSentence 各渲染一次（覆盖缺口）
  - 配饰里的眼镜与独立眼镜池重复（跨分类重复，但没检 NSFW）
  - 删了 maleOutfitState 分类，代码里的常量还留着（代码 ↔ 数据漂移）

本脚本把每一类都变成可自动检出的规则。纯静态，不跑 Gradle，秒级返回。

用法:
    python tools/check_libraries.py
    python tools/check_libraries.py --strict   # 警告也当失败
"""

import argparse
import io
import json
import os
import random
import re
import sys

# ---------------------------------------------------------------------------
# 引擎里与「人数」强相关的分类。人数会改变画面构成，所以精确人数优先对它们成立；
# 其余分类（头姿、表情）不该被单条 people=2 独占。
# 必须和 TagComposerEngine.PEOPLE_SENSITIVE 保持一致。
# ---------------------------------------------------------------------------
PEOPLE_SENSITIVE = {"activity", "sexAct", "pose"}

# 人数固定为 1 的枚举型分类，池子小是正常的。
SMALL_POOL_OK = {
    "people", "people.count", "camera", "grade", "mood",
    "sceneTrace", "maleFace", "maleFace.state",
}
MIN_POOL = 3

# 这些是「谁渲染的」而不是「一个分类」的常量，不参与分类校验。
NON_CATEGORY_CONSTANTS = {"SEPARATOR", "BODY_BLOCK_CAP", "JOIN_NIPPLE", "PEOPLE_SENSITIVE"}


class Report:
    def __init__(self):
        self.errors = []
        self.warnings = []
        self.infos = []
        self.checked = 0

    def error(self, where, msg):
        self.errors.append("[错误] {}: {}".format(where, msg))

    def warn(self, where, msg):
        self.warnings.append("[警告] {}: {}".format(where, msg))

    def info(self, where, msg):
        self.infos.append("[提示] {}: {}".format(where, msg))


def load_json(path, report):
    if not os.path.exists(path):
        report.error(os.path.basename(path), "文件不存在：{}".format(path))
        return None
    try:
        with io.open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except ValueError as exc:
        report.error(os.path.basename(path), "JSON 解析失败：{}".format(exc))
        return None


def collect(data):
    """返回 (分类全集, {词: [(分类, 槽)]}, {(分类, 槽): 池})。"""
    categories = data.get("categories") or []
    ids = [c.get("id") for c in categories]
    where = {}
    pools = {}
    for category in categories:
        cid = category.get("id")
        for slot in category.get("slots") or []:
            sid = slot.get("id")
            pool = slot.get("pool") or []
            pools[(cid, sid)] = pool
            for word in pool:
                where.setdefault(word, []).append((cid, sid))
    return ids, where, pools


# ---------------------------------------------------------------------------
# 结构
# ---------------------------------------------------------------------------
def check_structure(data, report, kind):
    if data is None:
        return
    categories = data.get("categories") or []
    if not categories:
        report.error("{}".format(kind), "categories 为空")
        return

    ids = [c.get("id") for c in categories]
    duplicated = {i for i in ids if ids.count(i) > 1}
    if duplicated:
        report.error("{}.categories".format(kind), "分类 id 重复：{}".format(sorted(duplicated)))

    # SFW 库的两条身份断言：profile 和 rating 一起决定运行时加载哪套。
    if kind.endswith("_sfw"):
        meta = data.get("meta") or {}
        if meta.get("profile") != "sfw":
            report.error("{}.meta".format(kind),
                         "profile 应为 sfw，实际 {!r}".format(meta.get("profile")))
        if meta.get("rating") != "general":
            report.error("{}.meta".format(kind),
                         "rating 应为 general，实际 {!r}".format(meta.get("rating")))

    for category in categories:
        cid = category.get("id", "?")
        if not category.get("label"):
            report.error("{}.{}".format(kind, cid), "缺少 label")
        for slot in category.get("slots") or []:
            sid = slot.get("id", "?")
            pool = slot.get("pool") or []
            spot = "{}.{}.{}".format(kind, cid, sid)
            if not pool:
                report.error(spot, "池为空")
                continue
            report.checked += len(pool)
            if len(pool) < MIN_POOL and "{}.{}".format(cid, sid) not in SMALL_POOL_OK and cid not in SMALL_POOL_OK:
                report.error(spot, "只有 {} 条，低于 {}（重抽会明显重复）".format(len(pool), MIN_POOL))
            if len(set(pool)) != len(pool):
                dup = sorted({w for w in pool if pool.count(w) > 1})
                report.error(spot, "同一池内重复：{}".format(dup))
            for word in pool:
                if not word or not word.strip():
                    report.error(spot, "有空词条")
                elif word != word.strip():
                    report.warn(spot, "词条首尾有空白：{!r}".format(word))


DECORATIVE_SLOT = re.compile(r"color|pattern|style|装饰|纹样", re.I)


def check_cross_category(data, report, kind):
    """同库跨分类同形词。

    同一个词落在两个分类，两处都会渲染，句子里就写两遍——配饰的「戴细框眼镜」
    和独立眼镜池的「细框眼镜」就是这一类。

    颜色和图案槽豁免：每个衣物分类都有自己的 color 槽（top.color / bottom.color /
    bra.color），同一种「浅蓝」各留一份是设计，引擎按分类分别取色。
    """
    if data is None:
        return
    _, where, _ = collect(data)
    for word, spots in sorted(where.items()):
        cids = sorted({cid for cid, _ in spots})
        if len(cids) <= 1:
            continue
        # 所有出现位置都在修饰槽里 → 是颜色/图案轴，不是重复渲染。
        if all(DECORATIVE_SLOT.search(sid or "") for _, sid in spots):
            continue
        report.error(
            "{}.duplicate".format(kind),
            "跨分类重复「{}」：{}".format(
                word, " / ".join("{}.{}".format(c, s) for c, s in spots)),
        )


# ---------------------------------------------------------------------------
# 引用完整性：constraints / rules / meta 里点名的词必须真实存在
# ---------------------------------------------------------------------------
def check_references(data, report, kind):
    if data is None:
        return
    ids, where, pools = collect(data)
    known = set(ids)
    words = set(where)
    constraints = data.get("constraints") or {}
    slots_by_category = {}
    for cid, sid in pools:
        slots_by_category.setdefault(cid, set()).add(sid)

    # 词的键值表：键必须是真实词。
    for field in ("handCost", "mouthCost", "headCost", "requires", "peopleCount",
                  "weight", "excludes", "synonyms", "maleOnly", "malePeople",
                  "allowedPostures", "headPlacements", "postureHints", "regions",
                  "eras", "skinByRegion", "pos"):
        table = constraints.get(field)
        if not isinstance(table, dict):
            continue
        for key in table:
            if key in known and field in ("allowedPostures",):
                continue
            if key not in words and key not in known:
                report.error(
                    "{}.constraints.{}".format(kind, field),
                    "「{}」不在任何池里".format(key),
                )

    # 地域 -> 肤色的对照表：键是地域词，值是肤色词，两边都得真实存在。
    for region, tones in (constraints.get("skinByRegion") or {}).items():
        for tone in tones:
            if tone not in words and tone not in known:
                report.error(
                    "{}.constraints.skinByRegion".format(kind),
                    "「{}」的肤色「{}」不在任何池里".format(region, tone),
                )

    # 配饰、道具里混进动作短语：这类词接上「戴着」「拿着」就是病句。
    ACCESSORY_SLOTS = {"headwear": ("item", "eyewear"), "jewelry": ("item",), "toys": ("item",)}
    pos_table = constraints.get("pos") or {}
    for cid, slot_ids in ACCESSORY_SLOTS.items():
        for c in (data.get("categories") or []):
            if c.get("id") != cid:
                continue
            for slot in c.get("slots") or []:
                if slot.get("id") not in slot_ids:
                    continue
                for word in slot.get("pool") or []:
                    if word in pos_table or "的" in word:
                        continue
                    if any(ch in word for ch in ("在", "上去", "下来")):
                        report.error(
                            "{}.pos.{}".format(kind, cid),
                            "「{}」像动作短语，接上戴/拿会读成病句，改成名词短语或标词性".format(word),
                        )

    # 简单列表型。
    for field in ("maleOnly", "soloPose", "exactPeople", "frameHidesCategories",
                  "selfColoured", "selfPatterned"):
        value = constraints.get(field)
        if isinstance(value, list):
            for word in value:
                if word not in words and word not in known:
                    report.error(
                        "{}.constraints.{}".format(kind, field),
                        "「{}」不在任何池里".format(word),
                    )

    # 自带颜色的款式靠词面颜色判断不了，但登记了却一个颜色字都没有，多半是误登记。
    colour_chars = "白黑红蓝绿黄紫粉灰棕米杏青金驼橙褐"
    for word in constraints.get("selfColoured") or []:
        if word in words and not any(ch in word for ch in colour_chars):
            report.error(
                "{}.constraints.selfColoured".format(kind),
                "「{}」词面没有颜色字，确认是自带颜色还是误登记".format(word),
            )

    # 自带图案的款式：词面得真带图案字，否则是误登记。
    pattern_chars = "豹纹格纹波点碎花印花千鸟格条纹"
    for word in constraints.get("selfPatterned") or []:
        if word in words and not any(ch in word for ch in ("豹纹", "格纹", "波点", "碎花", "印花", "千鸟格", "条纹")):
            report.error(
                "{}.constraints.selfPatterned".format(kind),
                "「{}」词面没有图案字，确认是自带图案还是误登记".format(word),
            )

    # placeSupports / peopleByCategory 的键是分类 id。
    people_by_category = constraints.get("peopleByCategory") or {}
    for cid, table in people_by_category.items():
        if cid not in known:
            report.error("{}.constraints.peopleByCategory".format(kind),
                         "分类 {} 不存在".format(cid))
            continue
        for word in table:
            if word not in words:
                report.error("{}.constraints.peopleByCategory".format(kind),
                             "「{}」不在任何池里".format(word))

    # placeSupports 的键是场景词（浴缸、温泉），值才是能力标签（water），两级都不是分类 id。
    for scene in (constraints.get("placeSupports") or {}):
        if scene not in words:
            report.error("{}.constraints.placeSupports".format(kind),
                         "「{}」不在任何场景池里".format(scene))

    # rules 的 whenAny / skipWords / skip
    rules = data.get("rules") or {}
    for rule in rules.get("skipCategories") or []:
        if not isinstance(rule, dict):
            report.error("{}.rules".format(kind), "规则应为对象")
            continue
        for cid in rule.get("skip") or []:
            # 「分类.格」只关掉一格（异族不叠身份），整段分类名则关掉整个分类。
            base, _, slot = cid.partition(".")
            if base not in known:
                report.error("{}.rules.skip".format(kind), "引用了不存在的分类 {}".format(cid))
            elif slot and slot not in slots_by_category.get(base, set()):
                report.error("{}.rules.skip".format(kind), "引用了不存在的格 {}".format(cid))
        for word in (rule.get("whenAny") or []):
            if word not in words:
                report.error("{}.rules.whenAny".format(kind), "「{}」不在任何池里".format(word))
        for word in (rule.get("skipWords") or []):
            if word not in words:
                report.error("{}.rules.skipWords".format(kind), "「{}」不在任何池里".format(word))

    # 种族两截短语：种族名要能插进句子里，「特征 + 年龄国籍气质女 + 名词」。
    # 缺一段就退化成光秃秃一个「精灵」，所以非人类的每个种类都必须两边都有。
    lead = constraints.get("raceLead") or {}
    name = constraints.get("raceName") or {}
    kinds = set((pools.get(("race", "kind")) or []))
    origins = set((pools.get(("person", "origin")) or []))
    for word in sorted(set(lead) | set(name)):
        if word not in kinds:
            report.error("{}.constraints.raceLead".format(kind),
                         "「{}」不是种类池里的词".format(word))
    # 半边有半边没有，说明改名或删词时漏了一处。
    for word in sorted(set(lead) ^ set(name)):
        report.error("{}.constraints.raceLead".format(kind),
                     "「{}」只有前置或只有名词，两截对不上".format(word))

    # 制服归属：词要真实存在，归属方要是身份池里的职业。
    for word, owners in sorted((constraints.get("uniformOwner") or {}).items()):
        if word not in words:
            report.error("{}.constraints.uniformOwner".format(kind),
                         "「{}」不在任何池里".format(word))
        for owner in owners:
            if owner not in origins:
                report.error("{}.constraints.uniformOwner".format(kind),
                             "「{}」归属的「{}」不在身份池里".format(word, owner))

    # 一件式服装：抽到婚纱/长袍就不再抽下衣。名单漏登记一件，就会拼出
    # 「幽冥圣女黑袍 + 迷你裙」这类两套下装打架的句子，所以这里盯住新词。
    one_piece = set(constraints.get("onePiece") or [])
    one_piece_bottom = set(constraints.get("onePieceBottom") or [])
    top_pool = {w for (cid, sid), pool in pools.items() if cid == "top" and sid == "type" for w in pool}
    bottom_pool = {w for (cid, sid), pool in pools.items() if cid == "bottom" and sid == "type" for w in pool}
    for word in sorted(one_piece - top_pool):
        report.error("{}.constraints.onePiece".format(kind),
                     "「{}」不在上衣款式池里".format(word))
    for word in sorted(one_piece_bottom - bottom_pool):
        report.error("{}.constraints.onePieceBottom".format(kind),
                     "「{}」不在下衣款式池里".format(word))
    # 围裙是系在身前的半件，不是连身装。
    apron = {"围裙", "裸体围裙", "咖啡店店员围裙", "保育员围裙"}
    for word in sorted(top_pool - one_piece - apron):
        if any(k in word for k in ("裙", "袍", "礼服", "婚纱", "古装", "汉服", "和服", "浴衣", "泳衣")):
            report.warn("{}.constraints.onePiece".format(kind),
                        "「{}」看着是一件式，却没登记进 onePiece，抽到后会再抽下衣".format(word))

    # 自身姿势与姿势白名单互斥：两条规则互为反面，该词永远配不上任何姿势。
    # 蹲在他身上标了 kneel、白名单只放 sit，就是这种情况——姿势全被其中一条拦光。
    posture = constraints.get("posture") or {}
    allowed = constraints.get("allowedPostures") or {}
    for word, allow in allowed.items():
        own = posture.get(word)
        if isinstance(own, str) and isinstance(allow, list) and own not in allow:
            report.error(
                "{}.posture".format(kind),
                "「{}」自身姿势是 {}，白名单只有 {}，两者互斥，该词永远配不上姿势".format(
                    word, own, allow),
            )

        # 裸体词必须挂上「跳过衣物」规则。只认「全裸」而漏掉「完全裸体」，
    # 就会画出「完全裸体，穿着条纹西装外套和侧开衩裙」这种图。
    outfit_pool = {w for (cid, _s), pool in pools.items() if cid == "outfitState" for w in pool}
    covered_by_skip = set()
    for rule in (data.get("rules") or {}).get("skipCategories") or []:
        if isinstance(rule, dict):
            covered_by_skip |= set(rule.get("whenAny") or [])
    for word in sorted(outfit_pool):
        if ("裸" in word or word.startswith("只穿")) and word not in covered_by_skip:
            report.warn(
                "{}.skipCoverage".format(kind),
                "「{}」没有对应的跳过衣物规则，可能和上装下装同时出现".format(word),
            )

# 锚定表：动作族决定「选了某个性行为之后，着装/表情/镜头往哪里收窄」。
    # 名单里写一个不存在的词，收窄后可能只剩一项——插入类动作永远只出全裸就是这么来的。
    anchors = data.get("anchors") or {}
    anchor_fields = {"express": "expression", "gaze": "gazeDir", "fluids": "fluids",
                     "camera": "camera", "outfitState": "outfitState"}
    sex_acts = set(pools.get(("sexAct", None), [])) | {
        w for (cid, _sid), pool in pools.items() if cid == "sexAct" for w in pool
    }
    for family, profile in (anchors.get("families") or {}).items():
        if not isinstance(profile, dict):
            continue
        for field, cid in anchor_fields.items():
            words = profile.get(field) or []
            valid = {w for (c, _s), pool in pools.items() if c == cid for w in pool}
            for word in words:
                if word not in valid:
                    report.error(
                        "{}.anchors.{}.{}".format(kind, family, field),
                        "「{}」不在 {} 池里，收窄后该选项永不出场".format(word, cid),
                    )
    for act in (anchors.get("of") or {}):
        if act not in sex_acts:
            report.error("{}.anchors.of".format(kind),
                         "「{}」不是真实的性行为词".format(act))
    for act in (anchors.get("addOn") or []):
        if act not in sex_acts:
            report.error("{}.anchors.addOn".format(kind),
                         "「{}」不是真实的性行为词".format(act))

    # meta 里的分类引用
    meta = data.get("meta") or {}
    for cid in meta.get("uiOrder") or []:
        if cid not in known:
            report.error("{}.meta.uiOrder".format(kind), "分类 {} 不存在".format(cid))
    for index, tier in (meta.get("promptTiers") or {}).items():
        for cid in tier:
            if cid not in known:
                report.error("{}.meta.promptTiers".format(kind),
                             "第 {} 层引用了不存在的分类 {}".format(index, cid))
    for key in (meta.get("commonFirst") or {}):
        cid = key.split(".")[0]
        if cid not in known:
            report.error("{}.meta.commonFirst".format(kind), "分类 {} 不存在".format(cid))


# ---------------------------------------------------------------------------
# 可达性：这是 check_sfw.py 完全没有的维度，也是最贵的两类 bug
# ---------------------------------------------------------------------------
def names(word, need):
    """必须与 RequirementFiller.names 完全一致：尾部匹配，不是包含匹配。"""
    return word == need or word.endswith(need)


def need_word(entry):
    return entry.split(":", 1)[1] if ":" in entry else entry


def need_category(entry):
    return entry.split(":", 1)[0] if ":" in entry else None


def check_dead_requirements(data, report, kind):
    """requires 里的依赖如果没有一个词能满足，这个词就永远抽不到。

    之前的 168 条 requires=["男"] 就是这么死的：people 池里没有以「男」结尾的词，
    而 names 是尾部匹配，「一男一女」不算。词在库里、菜单里，却一次都不会出现。
    """
    if data is None:
        return
    ids, where, pools = collect(data)
    constraints = data.get("constraints") or {}
    requires = constraints.get("requires") or {}
    if not isinstance(requires, dict):
        return

    # 预先按分类建索引，供 needCategory 限定作用域。
    by_category = {}
    for (cid, _sid), pool in pools.items():
        by_category.setdefault(cid, []).extend(pool)

    dead = []
    for word, needs in requires.items():
        if not isinstance(needs, list) or not needs:
            report.error("{}.constraints.requires".format(kind),
                         "「{}」的依赖为空列表".format(word))
            continue
        # requirementsMet 是 any 语义：只要一个依赖能被满足，这个词就可达。
        reachable = False
        for entry in needs:
            scope = need_category(entry)
            target = need_word(entry)
            candidates = by_category.get(scope, []) if scope else [w for ws in by_category.values() for w in ws]
            if any(names(w, target) for w in candidates):
                reachable = True
                break
        if not reachable:
            dead.append((word, needs))

    for word, needs in dead:
        report.error(
            "{}.dead.requires".format(kind),
            "「{}」的依赖 {} 一条都满足不了，这个词永远抽不到".format(word, needs),
        )


def check_people_domination(data, report, kind):
    """某人数只有一条词时，该场景下整个槽会被它独占。

    headPose 24 条里只有「下巴抵在肩上」标了 people=2，锁定一男一女后 300 次
    抽取 300 次都是它。只要出现「某人数恰好 1 条」，就值得复核。
    """
    if data is None:
        return
    ids, where, pools = collect(data)
    constraints = data.get("constraints") or {}

    # 词 → 人数（peopleByCategory 优先，其次 peopleCount，再次 people）
    per_word = {}
    for cid, table in (constraints.get("peopleByCategory") or {}).items():
        if isinstance(table, dict):
            for word, count in table.items():
                per_word[word] = count
    for field in ("peopleCount", "people"):
        table = constraints.get(field)
        if isinstance(table, dict):
            for word, count in table.items():
                per_word.setdefault(word, count)

    for (cid, sid), pool in pools.items():
        # people 是人数枚举，天生每个人数一条，独占是它的语义，不是 bug。
        if cid == "people":
            continue
        counts = {}
        for word in pool:
            value = per_word.get(word)
            if isinstance(value, int):
                counts.setdefault(value, []).append(word)
        # 只看 2 人及以上：1 人是默认值，独占没有意义。
        for crowd, members in counts.items():
            if crowd < 2:
                continue
            if len(members) == 1:
                label = "{}.{}.people{}".format(kind, cid, crowd)
                if cid in PEOPLE_SENSITIVE:
                    # 敏感分类按精确人数收窄，只有一条就是真的会一直出它。
                    report.error(
                        label,
                        "「{}」是 {} 人场景唯一的词，该分类按人数收窄，会一直出它".format(
                            members[0], crowd),
                    )
                else:
                    # 非敏感分类不按人数收窄（引擎 PEOPLE_SENSITIVE 白名单），所以只是隐患。
                    report.info(
                        label,
                        "「{}」是 {} 人场景唯一的词。该分类不在 PEOPLE_SENSITIVE 里，"
                        "不受精确人数收窄影响；若以后加进白名单会立刻变成独占".format(
                            members[0], crowd),
                    )


# 人类是唯一允许带职业身份的种类。其余种类自带角、翼、尾巴，再叠一个职业就是两套设定
# 打架：小恶魔 + 医生，模型只能画一半。
HUMAN_KINDS = {"人类"}


def check_race_gate(data, report, kind, engine):
    """异族不该再叠一个职业身份，也不该再配一个地域。

    「是不是异族」以引擎里的异族描写表为准：凡是由引擎渲染出角、翼、尾巴、鳞片的种类，
    就不能再挂一个护士或医生。规则靠「分类.格」只关掉身份与地域两格，气质和年龄感
    仍然保留。同时保证人类没被误关——误关的话职业这一格永远抽不到词。
    """
    if data is None:
        return
    _, where, pools = collect(data)
    kinds = pools.get(("race", "kind"), [])
    if not kinds:
        return
    fantasy = (engine or {}).get("fantasy_races") or set()
    # 只有身份格必须关：地域现在对异族也开放，「二十八岁韩国可爱女妖精」是通的。
    targets_here = {t for t in ("person.origin",)
                    if t.split(".", 1)[1] in {sid for cid, sid in pools if cid == t.split(".", 1)[0]}}
    if not targets_here:
        return
    gated = {}
    for rule in (data.get("rules") or {}).get("skipCategories") or []:
        if not isinstance(rule, dict):
            continue
        for target in rule.get("skip") or []:
            if target in targets_here:
                gated.setdefault(target, set()).update(rule.get("whenAny") or [])
    # 引擎会画角画翼的种类，必须能拼成一句完整的话；缺一段就只剩一个光秃秃的种族名。
    constraints = data.get("constraints") or {}
    lead = constraints.get("raceLead") or {}
    name = constraints.get("raceName") or {}
    for race in sorted(k for k in kinds if k in fantasy):
        if race not in lead or race not in name:
            report.error("{}.raceLead".format(kind),
                         "「{}」缺前置或名词，渲染时会只剩一个种族名".format(race))

    for target in sorted(targets_here):
        label = "{}.raceGate.{}".format(kind, target)
        covered = gated.get(target, set())
        missing = [k for k in kinds if k in fantasy and k not in covered]
        if missing:
            report.error(label, "异族没被关住：{}".format("、".join(missing)))
        for human in sorted(HUMAN_KINDS & covered):
            report.error(label, "「{}」也被关掉了，人类就再也抽不到词".format(human))
    # 名单里写了词库里没有的种类 = 改名后漏改，规则静默失效。
    for target, triggers in sorted(gated.items()):
        for word in sorted(triggers - set(kinds)):
            report.warn("{}.raceGate.{}".format(kind, target),
                        "「{}」不在种类池里，这条规则对它没有作用".format(word))


def check_story_casts(data, report, kind):
    """剧情动作要按人数分家。

    一条动作同时挂在「一男一女」和「两个女孩」上，就会写出「两个女孩…他把阴茎插进去」。
    只有标了 common 的连接动作（接吻、换姿势）允许共用。
    """
    if data is None:
        return
    pool = (data.get("pools") or {}).get("act") or []
    shared = []
    leak = []
    for word in pool:
        if not isinstance(word, dict):
            continue
        casts = word.get("casts") or []
        text = word.get("text") or ""
        if len(casts) > 1 and not word.get("common"):
            shared.append(text)
        if "ff" in casts and ("他" in text or "阴茎" in text):
            leak.append(text)
    if shared:
        report.error("{}.story.act".format(kind),
                     "动作挂了多个人数：{}".format(shared[:3]))
    if leak:
        report.error("{}.story.act".format(kind),
                     "两个女孩的动作里出现了男人：{}".format(leak[:3]))


def check_partner_mirror(data, report, kind):
    """第三人动作两格必须同池。

    act 给一男两女的那位旁观者，act2 给一男三女的第二位。两格各自存一份词表，
    改一处忘另一处，就会出现「一男三女少一半词」或者某条永远抽不到。
    """
    if data is None:
        return
    for category in data.get("categories") or []:
        if category.get("id") != "partnerAct":
            continue
        pools = {slot.get("id"): slot.get("pool") or []
                 for slot in category.get("slots") or []}
        act = pools.get("act")
        act2 = pools.get("act2")
        if act is None or act2 is None:
            report.error("{}.partnerAct".format(kind),
                         "第三人动作缺一格：act / act2 都要有")
            continue
        if act != act2:
            only_act = [w for w in act if w not in act2]
            only_act2 = [w for w in act2 if w not in act]
            report.error("{}.partnerAct".format(kind),
                         "两格词表不一致：只在 act {}，只在 act2 {}".format(
                             only_act[:3], only_act2[:3]))


# ---------------------------------------------------------------------------
# 代码 ↔ 数据一致性
# ---------------------------------------------------------------------------
def read_text(path):
    if not os.path.exists(path):
        return None
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def parse_engine(engine_path, report):
    """从 TagComposerEngine.kt 抽出「代码里点名的分类」。

    判定口径不是「逐条校验常量」——那会把 FACE_ALL="all" 这类枚举值当成分类。
    只认真正参与渲染的结构：段落表、take()、maleSentence 的 rest 排除列表。
    """
    source = read_text(engine_path)
    if source is None:
        report.error("engine", "找不到 {}".format(engine_path))
        return None

    constants = dict(re.findall(r'const val (\w+)\s*=\s*"([^"]*)"', source))

    sections = {}
    for name in ("SECTION_HEAD", "SECTION_HERO", "SECTION_ACT_PRE",
                 "SECTION_ACT_MAIN", "SECTION_TAIL"):
        match = re.search(r'{}\s*=\s*(?:listOf\((.*?)\)|emptyList)'.format(name), source, re.S)
        if not match:
            continue
        sections[name] = re.findall(r'"([^"]+)"', match.group(1) or "")

    # take(X) 只认全大写常量名：take(avoid) / take(2) 是 List 上的同名方法，不是分类。
    taken = {name for name in re.findall(r'take\((\w+)\)', source)
             if re.fullmatch(r"[A-Z][A-Z0-9_]*", name)}
    taken_words = set(re.findall(r'take\("([^"]+)"\)', source))
    # rest 兜底里 `it != MALE_BODY` 这类排除，说明该分类已由专用句子渲染。
    rest_excluded = set(re.findall(r'it\s*!=\s*(\w+)', source))

    def resolve(names):
        """只解析全大写常量名。`it != null` / `take(avoid)` 这类局部变量一律不认。"""
        out = set()
        for name in names:
            if not re.fullmatch(r"[A-Z][A-Z0-9_]*", name):
                continue
            if name in constants:
                out.add(constants[name])
        return out

    # 被显式消费的：段落表 + take。这些词不会落到 rest 兜底。
    consumed = set()
    for members in sections.values():
        consumed |= set(members)
    consumed |= resolve(taken)
    consumed |= taken_words

    # rest 里显式排掉的（`it != MALE_BODY`）同样不会重复。
    safe_rest = resolve(rest_excluded)

    referenced = consumed | safe_rest
    rendered_by_male = resolve({"MALE_BODY", "MALE_HAND", "PENIS", "MALE_FACE",
                                "MALE_OUTFIT_STATE"})

    # PEOPLE_SENSITIVE 在两个地方各存一份，必须一致。
    match = re.search(r'PEOPLE_SENSITIVE\s*=\s*setOf\((.*?)\)', source, re.S)
    engine_sensitive = set(re.findall(r'"([^"]+)"', match.group(1))) if match else set()

    # 异族描写表：引擎给这些种类渲染角、翼、尾巴。它们不能再挂一个职业身份。
    match = re.search(r'FANTASY_RACE_PHRASES\s*=\s*mapOf\((.*?)\n\s*\)', source, re.S)
    fantasy_races = set(re.findall(r'"([^"]+)"\s+to\s+"', match.group(1))) if match else set()

    return {
        "referenced": referenced,
        "consumed": consumed,
        "rendered_by_male": rendered_by_male,
        "safe_rest": safe_rest,
        "sensitive": engine_sensitive,
        "fantasy_races": fantasy_races,
        "source": source,
    }


def check_engine_against_data(engine, data, report, kind):
    if engine is None or data is None:
        return
    ids = [c.get("id") for c in (data.get("categories") or [])]
    known = set(ids)
    referenced = engine["referenced"]

    # 1) 代码点名了、数据里没有 → 数据删干净了代码没跟上。
    for cid in sorted(referenced - known):
        report.error(
            "{}.engine.dangling".format(kind),
            "引擎引用了分类 {}，数据里不存在（删分类时漏了代码）".format(cid),
        )

    # 2) 数据有、代码没点名 → 走 rest 兜底，不是错，但要看是不是漏写段落。
    rest = sorted(known - referenced)
    if rest:
        report.info(
            "{}.engine.rest".format(kind),
            "{} 个分类走 rest 兜底：{}".format(len(rest), ", ".join(rest)),
        )

    # 3) 由 maleSentence 独占渲染、却既没 take 也没进 rest 排除列表 → 会被兜底再拼一遍。
    #    maleFace 走的正是 take(MALE_FACE) 这条路。
    for cid in sorted(engine["rendered_by_male"] & known):
        if cid in engine["consumed"] or cid in engine["safe_rest"]:
            continue
        report.error(
            "{}.engine.cover".format(kind),
            "分类 {} 由 maleSentence 渲染，但既没 take 也不在 rest 排除列表里，"
            "会被兜底重复拼进句子".format(cid),
        )

    # 4) preferPeople 的精确人数收窄必须有白名单守卫。
    #    没有守卫时，任何分类只要有一条 people=N 的词，N 人场景就会被它独占——
    #    头部姿态 300 次全出「下巴抵在肩上」就是这么来的。
    if "preferPeople" in engine["source"]:
        guarded = bool(re.search(r"categoryId\s*!in\s+PEOPLE_SENSITIVE", engine["source"]))
        if not guarded:
            report.error(
                "{}.engine.preferPeople".format(kind),
                "preferPeople 按精确人数收窄，但没有 categoryId !in PEOPLE_SENSITIVE 守卫；"
                "任何分类的 people=N 单词都会独占该人数场景",
            )
        # 数据侧：非敏感分类里出现 people=N 的唯一词，就是潜在的独占点。
        for cid in sorted(PEOPLE_SENSITIVE):
            if cid not in known:
                report.error(
                    "{}.engine.PEOPLE_SENSITIVE".format(kind),
                    "PEOPLE_SENSITIVE 里的 {} 不是真实分类".format(cid),
                )
        # 脚本里的副本与 Kotlin 里的必须一致，否则守卫判定会漂移。
        if engine["sensitive"] != PEOPLE_SENSITIVE:
            report.error(
                "{}.engine.sensitive".format(kind),
                "PEOPLE_SENSITIVE 不一致：脚本 {} vs 引擎 {}".format(
                    sorted(PEOPLE_SENSITIVE), sorted(engine["sensitive"])),
            )


# ---------------------------------------------------------------------------
# story 库：结构完全不同（pools / templates），不能套 tag 的那套检查
# ---------------------------------------------------------------------------
# StoryGenerator 里真正会被替换的占位符。表外的占位符会以字面量留在提示词里。
STORY_PLACEHOLDERS = {
    "time", "cast", "female", "look_f", "look_m", "place_b", "place", "light",
    "outfit_b", "outfit", "socks", "shoes", "state", "outfit_mid", "chore", "male",
    "escalate", "pose", "prop", "acts", "mark", "world", "detail",
    "partner", "mood",
    # 八段模板引入：她、她穿着、女二、女二穿着、天气，都由 formatHer / formatWear 现算。
    "her", "wear", "her2", "wear2", "weather",
}
# 不是池名的占位符（生成器现算）。
STORY_SPECIAL = {"cast", "female", "male", "acts", "detail", "world", "look_f",
                 "look_m", "place_b", "light", "outfit_b", "state",
                 "her", "wear", "her2", "wear2", "weather"}

# 参与衣物状态机的池：这些池里出现裸字符串，wears/eras 就丢了。
WARDROBE_POOLS = {"outfit", "outfit_mid", "outfit_state", "socks", "shoes",
                  "man_outfit", "man_state"}
# 男方专属池：与女方池同词只提示，不报错（两个角色的属性）。
MALE_POOLS = {"man_look", "man_outfit", "man_state"}


def check_story_library(data, report, kind):
    if data is None:
        return
    pools = data.get("pools") or {}
    if not pools:
        report.error("{}.pools".format(kind), "pools 为空")
        return
    templates = data.get("templates") or {}
    if not templates:
        report.error("{}.templates".format(kind), "templates 为空")

    seen = {}
    for name, items in pools.items():
        spot = "{}.pool.{}".format(kind, name)
        if not items:
            report.error(spot, "池为空")
            continue
        texts = []
        # readPool 对裸字符串是兼容的（StoryWord(entry)，wears 为空 = 不改变穿着）。
        # 整池全裸是风格选择；str 与 dict 混用才是改漏了——那才是真事故。
        kinds = {type(item).__name__ for item in items}
        if kinds == {"str"}:
            if name in WARDROBE_POOLS:
                report.warn(spot, "整池为裸字符串，wears/eras 全为空；该池参与换装，需确认无恙")
        elif "str" in kinds:
            strays = [i for i, x in enumerate(items) if isinstance(x, str)]
            # 混用只有在参与换装的池里才是事故；地点、光线这类词本来就没有 wears。
            if name in WARDROBE_POOLS:
                report.error(
                    spot,
                    "第 {} 项是裸字符串，其余是对象；同一池混用会让这些词丢掉 wears/eras".format(
                        strays[:5]),
                )
            else:
                report.warn(
                    spot,
                    "第 {} 项是裸字符串，其余是对象（该池不参与换装，影响有限）".format(strays[:5]),
                )

        for index, item in enumerate(items):
            if isinstance(item, str):
                if not item:
                    report.error(spot, "第 {} 项为空字符串".format(index))
                    continue
                texts.append(item)
                report.checked += 1
                continue
            text = item.get("text")
            if not text:
                report.error(spot, "第 {} 项缺少 text".format(index))
                continue
            texts.append(text)
            report.checked += 1

        duplicated = sorted({t for t in texts if texts.count(t) > 1})
        if duplicated:
            report.error(spot, "同一池内重复：{}".format(duplicated[:3]))

        for text in texts:
            previous = seen.get(text)
            if previous and previous != name:
                # 男方池和女方池是两个人的属性，同词不代表同句重复渲染。
                if {previous, name} & MALE_POOLS and {previous, name} - MALE_POOLS:
                    mine = previous if previous in MALE_POOLS else name
                    hers = name if mine == previous else previous
                    report.info(
                        "{}.duplicate".format(kind),
                        "「{}」同时出现在男方池 {} 和女方池 {}".format(text, mine, hers),
                    )
                else:
                    report.error("{}.duplicate".format(kind),
                                 "跨池重复「{}」：{} / {}".format(text, previous, name))
            else:
                seen[text] = name

    all_text = set(seen)
    # requires 的判定是 scene.present.containsAll —— present 里放的是 props ∪ wears 标签，
    # 不是词条的 text。「床」写在 props 里，所以 requires=["床"] 合法。
    all_props, all_wears = set(), set()
    for items in pools.values():
        for item in items:
            if isinstance(item, dict):
                all_props |= set(item.get("props") or [])
                all_wears |= set(item.get("wears") or [])

    # 模板占位符：表外的会以字面量留在提示词里。
    for cast, entries in templates.items():
        for entry in entries:
            if isinstance(entry, dict):
                entry = entry.get("text", "")
            for token in re.findall(r"\{(\w+)\}", entry or ""):
                if token not in STORY_PLACEHOLDERS:
                    report.error(
                        "{}.template.{}".format(kind, cast),
                        "占位符 {{{}}} 不在 StoryGenerator 替换表里，会以字面量留在提示词".format(token),
                    )
                elif token not in STORY_SPECIAL and token not in pools:
                    report.error(
                        "{}.template.{}".format(kind, cast),
                        "占位符 {{{}}} 没有对应的池".format(token),
                    )

    # 动作的「可用姿势」引用的是姿势池的 key，写错了动作就永远配不上那个姿势。
    pose_keys = {w.get("key") for w in (pools.get("pose") or [])
                 if isinstance(w, dict) and w.get("key")}
    if pose_keys:
        used_keys = set()
        for item in pools.get("act") or []:
            if not isinstance(item, dict):
                continue
            for key in item.get("poses") or []:
                used_keys.add(key)
                if key not in pose_keys:
                    report.error(
                        "{}.pool.act".format(kind),
                        "「{}」的可用姿势「{}」在姿势池里不存在".format(
                            item.get("text", "?"), key),
                    )
        # 姿势没人能配：抽到它时动作会被整批剔除。
        # poses 为空是通配（任何姿势都能配），只要有这样一条动作，就不存在配不上的姿势。
        wildcard = any(
            isinstance(item, dict) and not (item.get("poses") or [])
            for item in (pools.get("act") or [])
        )
        orphan = sorted(pose_keys - used_keys)
        if orphan and not wildcard:
            report.warn(
                "{}.pool.pose".format(kind),
                "{} 个姿势没有任何动作能配（抽到它们时动作会空）：{}".format(
                    len(orphan), "、".join(orphan)),
            )

    # requires 查 props∪wears。
    # requiresWorn 分两套语义：male_state 比对的是 man_outfit 的 text
    # （`word.requiresWorn.contains(manOutfit)`），其余池才走 wearsAny 查 wears 标签。
    man_outfit_texts = {
        w.get("text") for w in (pools.get("man_outfit") or []) if isinstance(w, dict)
    }
    targets = {"requires": all_props | all_wears,
               "requiresWorn": all_wears,
               "keep": all_wears}
    for name, items in pools.items():
        if name == "man_state":
            targets = {"requires": all_props | all_wears,
                       "requiresWorn": man_outfit_texts,
                       "keep": all_wears}
        for item in items:
            if not isinstance(item, dict):
                continue
            for field, valid in targets.items():
                value = item.get(field)
                if not isinstance(value, list):
                    continue
                for need in value:
                    if need == "*":
                        continue
                    if need not in valid:
                        report.error(
                            "{}.pool.{}".format(kind, name),
                            "「{}」的 {} 引用了不存在的标签「{}」".format(
                                item.get("text", "?"), field, need),
                        )


# ---------------------------------------------------------------------------
# 动作标注覆盖率：词进了池子却没打约束表，引擎会按默认值放行，句子就串了
# ---------------------------------------------------------------------------
TAG_ACTION_SLOTS = {"sexAct": ("act",), "partnerAct": ("act", "act2")}


def tag_pool(data, cat_id, slot_id):
    for category in (data.get("categories") or []):
        if category.get("id") != cat_id:
            continue
        for slot in (category.get("slots") or []):
            if slot.get("id") == slot_id:
                return slot.get("pool") or []
    return []


def check_action_annotations(data, report, kind):
    cst = data.get("constraints") or {}
    people = cst.get("people") or {}
    hand_cost = cst.get("handCost") or {}
    by_cat = cst.get("peopleByCategory") or {}
    partner_people = by_cat.get("partnerAct") or {}

    for cat_id, slots in TAG_ACTION_SLOTS.items():
        for slot_id in slots:
            words = tag_pool(data, cat_id, slot_id)
            missing = [w for w in words if w not in people and w not in partner_people]
            if missing:
                report.error(
                    "{}.annot.{}".format(kind, cat_id),
                    "{} 条动作没标人数，引擎按 1 人放行（会串进单人句）：{}".format(
                        len(missing), "、".join(missing[:4])),
                )

    hands = tag_pool(data, "handAction", "hand")
    no_cost = [w for w in hands if w not in hand_cost]
    if no_cost:
        report.error(
            "{}.annot.handCost".format(kind),
            "{} 条手部动作没标 handCost，手部预算对它们不生效（能同时占第三只手）：{}".format(
                len(no_cost), "、".join(no_cost[:4])),
        )


# 与 TagComposerEngine.FULL_DEPTH_MARKS 保持一致；改引擎时这里要跟着改。
FULL_DEPTH_MARKS = ["坐下去", "顶到底", "一次进到底", "全根", "坐入", "吞到底", "整根", "坐到最深处"]
JUNCTION_WORDS = ["穴口", "结合处", "交合的地方", "根部"]


# 「抽出来只留龟头…又整根推回去」是两次动作的先后，不是「整根没入」的静态状态。
PROCESS_VERBS = ["抽出", "拔出来", "推回去", "顶回去", "退到", "拔出"]


def check_self_contradiction(data, report, kind):
    """一句话里既写「整根没入」又写「穴口张着」，引擎会判定交合处看不见而剔掉它。"""
    bad = [
        w for w in tag_pool(data, "sexAct", "act")
        if any(m in w for m in FULL_DEPTH_MARKS)
        and any(j in w for j in JUNCTION_WORDS)
        and not any(v in w for v in PROCESS_VERBS)
    ]
    if bad:
        report.warn(
            "{}.selfLock".format(kind),
            "{} 条动作同时写了「整根没入」和「交合处」，会被自己的封锁规则剔掉：{}".format(
                len(bad), "、".join(bad[:4])),
        )


def check_posture_reach(data, report, kind):
    """动作声明的可用姿势，若一个都不在姿势池里，这条动作永远抽不到。"""
    cst = data.get("constraints") or {}
    allowed = cst.get("allowedPostures") or {}
    posture_of = cst.get("posture") or {}
    poses = tag_pool(data, "pose", "body")
    reachable = {posture_of[p] for p in poses if posture_of.get(p)}
    if not reachable:
        return
    dead = [w for w, allow in allowed.items() if allow and not (set(allow) & reachable)]
    if dead:
        report.error(
            "{}.posture.dead".format(kind),
            "{} 条动作的可用姿势在姿势池里不存在，永远抽不到：{}".format(
                len(dead), "、".join(dead[:4])),
        )


def check_thin_pools(report, kind, pools):
    """只有一条词的池：随机等于没有，输出恒定。"""
    thin = sorted(name for name, items in pools.items()
                  if isinstance(items, list) and len(items) == 1)
    if thin:
        report.warn(
            "{}.thin".format(kind),
            "只有一条词的池（输出恒定）：{}".format("、".join(thin)),
        )


def tag_pools_map(data):
    pools = {}
    for category in (data.get("categories") or []):
        for slot in (category.get("slots") or []):
            pools["{}.{}".format(category.get("id"), slot.get("id"))] = slot.get("pool") or []
    return pools


# ---------------------------------------------------------------------------
def main():
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", default=os.path.join(root, "app", "src", "main", "assets"))
    parser.add_argument("--engine", default=os.path.join(
        root, "app", "src", "main", "java", "sh", "hnet", "comfychair",
        "workflow", "TagComposerEngine.kt"))
    parser.add_argument("--strict", action="store_true", help="警告也当失败")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    report = Report()

    libraries = [
        ("tag_nsfw", "tag_catalog.json"),
        ("story_nsfw", "story_catalog.json"),
        ("tag_sfw", "tag_catalog_sfw.json"),
        ("story_sfw", "story_catalog_sfw.json"),
    ]

    engine = parse_engine(args.engine, report)
    tag_nsfw = None
    for kind, filename in libraries:
        data = load_json(os.path.join(args.assets, filename), report)
        if data is None:
            continue
        if kind == "tag_nsfw":
            tag_nsfw = data
        if kind.startswith("story"):
            check_story_library(data, report, kind)
            check_thin_pools(report, kind, data.get("pools") or {})
        else:
            check_structure(data, report, kind)
            check_cross_category(data, report, kind)
            check_references(data, report, kind)
            check_dead_requirements(data, report, kind)
            check_people_domination(data, report, kind)
            check_race_gate(data, report, kind, engine)
            check_partner_mirror(data, report, kind)
            check_story_casts(data, report, kind)
            check_action_annotations(data, report, kind)
            check_self_contradiction(data, report, kind)
            check_posture_reach(data, report, kind)
            check_thin_pools(report, kind, tag_pools_map(data))

    check_engine_against_data(engine, tag_nsfw, report, "tag_nsfw")

    if not args.quiet:
        for line in report.errors:
            print(line)
        for line in report.warnings:
            print(line)
        for line in report.infos:
            print(line)
        print("检查 {} 条词，错误 {}，警告 {}，提示 {}".format(
            report.checked, len(report.errors), len(report.warnings), len(report.infos)))

    if report.errors:
        return 1
    if args.strict and report.warnings:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
