#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 B2（场景与镜头）与 B3（动作与神情）的 24 个分类并进 SFW 词库。

手写 JSON 的代价是少一个逗号就整库加载失败，而这类错误在 App 里只表现为「词库没加载出来」。
所以词条写在 Python 里，结构交给 json.dump。

幂等：按分类 id 覆盖，重复运行不会出现两份同名分类。
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "tag_catalog_sfw.json")

# 分类 id -> (label, group, tier, who, slotId, slotLabel, pool)
NEW = {
    # ---- 身体补完（B2 顺带补齐规划里的保留分类）----
    "featureHead": ("五官", "body", 2, "female", "part", "五官", [
        "杏眼", "桃花眼", "挺直鼻梁", "饱满唇形", "下颌线柔和",
        "脸型小巧", "眉形自然", "双眼皮", "卧蚕明显", "锁骨明显",
        "耳廓小巧", "笑起来有酒窝",
    ]),
    "eyeColor": ("眼睛", "body", 2, "female", "tone", "瞳色", [
        "深棕瞳", "浅褐瞳", "琥珀瞳", "灰蓝瞳", "深黑瞳",
        "浅灰瞳", "灰绿瞳", "榛色瞳", "猫眼瞳", "异色瞳",
        "浅蓝瞳", "深褐近黑瞳",
    ]),
    "waist": ("腰腹", "body", 2, "female", "shape", "腰腹", [
        "腰身纤细", "腰线柔和", "小腹平坦", "腰侧有线条", "腰窝明显",
        "腰身圆润", "腰际收紧", "腰部线条利落", "腰肢柔软", "腰间系着细带",
        "腹部线条紧实", "腰背挺直",
    ]),
    "hips": ("胯部", "body", 2, "female", "shape", "胯部", [
        "髋部线条流畅", "胯窄而直", "臀线圆润", "髋骨清晰", "身形水蛇腰",
        "背部到胯部一路收窄", "胯部比例匀称", "腰胯过渡自然", "坐姿下线条柔和",
        "站姿下轮廓清晰", "胯部紧实", "整体线条连贯",
    ]),
    "legs": ("腿", "body", 2, "female", "shape", "腿", [
        "腿型笔直", "小腿线条利落", "大腿匀称", "腿部比例匀称", "膝盖轮廓清晰",
        "脚踝纤细", "小腿有肌肉线条", "双腿并拢站立", "腿部线条柔和",
        "穿着长裤时腿部线条分明", "露出脚踝", "双腿自然交叠",
    ]),

    # ---- B2 场景与镜头 ----
    "weather": ("天气", "scene", 4, "scene", "state", "天气", [
        "晴朗无云", "薄云遮日", "细雨", "雨后初晴", "阴天",
        "小雨夹雪", "大雾", "微风", "起风的午后", "初雪",
        "梅雨", "干燥的晴冬", "海边的潮湿空气", "山间的凉意",
        "闷热的夏夜", "清冽的早晨",
    ]),
    "season": ("季节", "scene", 4, "scene", "time", "季节", [
        "早春", "暮春", "盛夏", "初秋", "深秋",
        "初冬", "隆冬", "立夏前后", "秋分时节", "梅雨季",
        "夏末初秋", "冬末的晴天",
    ]),
    "light": ("光线", "scene", 4, "scene", "kind", "光线", [
        "晨光斜射", "正午顶光", "午后斜阳", "黄昏侧逆光", "霓虹侧光",
        "台灯暖光", "阴天散射光", "烛光", "窗棂投下的光斑", "路灯下的暖光",
        "月光", "逆光轮廓光", "柔和的窗光", "夕阳照在后颈",
        "穿过树叶的碎光", "水面的反光", "走廊尽头的一束光", "屏幕的冷光",
    ]),
    "propObject": ("物件", "scene", 4, "scene", "item", "物件", [
        "手冲咖啡壶", "帆布托特包", "单反相机", "硬壳笔记本", "玻璃花瓶",
        "木质托盘", "一摞旧书", "胶片相机", "藤编篮", "折叠毛毯",
        "白瓷马克杯", "长柄雨伞", "旧自行车", "野餐布", "黑胶唱片",
        "木吉他", "吊灯", "留声机", "绿植", "纸箱",
    ]),
    "pet": ("宠物", "scene", 4, "scene", "kind", "宠物", [
        "橘猫", "白猫", "黑猫", "布偶猫", "柴犬",
        "金毛", "边境牧羊犬", "拉布拉多", "白色的兔子", "鹦鹉",
        "仓鼠", "小柯基",
    ]),
    "composition": ("构图", "scene", 5, "scene", "kind", "构图", [
        "居中构图", "三分法", "对角线构图", "大面积留白", "前景遮挡",
        "框中框", "对称构图", "低角度仰拍", "高角度俯拍", "中景平视",
        "特写填满画面", "横幅宽画幅", "竖屏全身", "群像排布",
    ]),
    "lens": ("焦段", "scene", 5, "scene", "kind", "焦段", [
        "广角 24mm", "35mm 人文视角", "50mm 标准视角", "85mm 人像视角",
        "135mm 长焦", "微距镜头", "鱼眼镜头", "景深很浅",
        "深景深", "背景虚化", "长焦压缩空间", "广角的轻微畸变",
    ]),
    "film": ("质感", "scene", 5, "scene", "kind", "质感", [
        "颗粒细腻", "胶片颗粒", "柯达色调", "富士胶片色", "高光柔化",
        "暗部提亮", "轻微褪色", "压暗四角", "扫描质感", "印刷网纹",
        "柔焦", "双层曝光",
    ]),
    "grade": ("调色", "scene", 5, "scene", "kind", "调色", [
        "暖调", "冷调", "青橙调", "低饱和", "高对比",
        "柔和灰调", "奶油色调", "莫兰迪色", "胶片褪色", "暗部偏青",
        "高光偏暖", "低对比灰调", "复古棕调", "清透亮调",
    ]),
    "mood": ("氛围", "scene", 5, "scene", "kind", "氛围", [
        "安静", "慵懒", "明亮", "克制", "潮湿",
        "清冷", "暖意", "静谧", "松弛", "微醺",
        "清爽", "沉静", "轻盈", "随性自在",
    ]),

    # ---- B3 动作与神情 ----
    "expression": ("表情", "act", 3, "female", "kind", "表情", [
        "微笑", "轻轻笑", "抿嘴笑", "认真", "平静",
        "惊讶", "略困", "害羞", "无奈", "若有所思",
        "温柔", "挑了下眉", "忍住笑", "放松", "专注",
        "微怔", "眼睛弯起", "面无表情",
    ]),
    "gazeDir": ("视线", "act", 3, "female", "kind", "视线", [
        "看向镜头", "看向窗外", "低头看手", "视线偏向一侧", "闭眼",
        "抬眼看向远处", "垂眼", "回头看", "看向画面外", "目光落在桌面",
        "抬眼看向对方", "视线下垂", "平视前方", "侧目",
    ]),
    "headPose": ("头部", "act", 3, "female", "kind", "头部朝向", [
        "微微歪头", "抬头", "低头", "侧脸", "回头",
        "下巴微抬", "头部正对镜头", "半侧脸", "仰头", "头靠在椅背",
        "下巴抵在手背", "侧头倾听",
    ]),
    "pose": ("姿势", "act", 3, "female", "kind", "姿势", [
        "站立", "倚墙站着", "坐在窗台", "坐在椅子上", "盘腿坐",
        "侧身站立", "双手插兜", "抱臂站着", "半蹲", "单膝跪地",
        "蹲着", "仰起上身", "转身回看", "靠在栏杆", "坐在台阶上",
        "踮脚", "伸腰", "靠门框", "双手背在身后", "坐在沙发扶手上",
    ]),
    "activity": ("动作", "act", 3, "female", "kind", "动作", [
        "走路", "小跑", "跑步", "骑行", "上下楼梯",
        "整理衣领", "翻书", "写东西", "冲咖啡", "浇花",
        "晾衣服", "收伞", "系鞋带", "搬东西", "看手机",
        "等人", "拍照", "逗猫",
    ]),
    "handAction": ("手部", "act", 3, "female", "kind", "手部动作", [
        "手扶栏杆", "手握咖啡杯", "手撑下巴", "手拨头发", "双手叠放",
        "手指点着纸面", "单手插兜", "手搭在椅背", "手指搭在唇边", "握着伞柄",
        "手按在书页上", "手碰着窗玻璃", "指尖碰杯沿", "双手捧着杯子",
    ]),
    "interaction": ("互动", "act", 3, "female", "kind", "双人互动", [
        "并肩走过", "共撑一把伞", "递过一杯咖啡", "十指相扣", "从背后轻轻抱住",
        "背靠背看书", "同看一部手机", "替对方拨开额发", "一起搬起纸箱", "并肩坐在台阶上",
        "互相整理衣领", "一起扶着栏杆", "把外套披到对方肩上", "面对面站着说话",
        "一起抬头看天", "手挽手", "相隔一步对视", "并肩骑车",
        "一起蹲下看猫", "互相递东西",
    ]),
}

# 只加词、不新分类的几处补完
EXTEND = {
    "scene": [
        "便利店门口", "老式公寓的楼梯间", "天台晾衣绳旁", "海边防波堤",
        "雨后的玻璃花房", "书店二楼", "咖啡烘焙工坊", "清晨的公交站",
    ],
    "camera": ["正面半身带手", "侧面全身", "背影全身", "微仰半身"],
    "style": ["电影感焦外", "日式街拍", "自然纪实", "暗调人像", "明亮生活感", "复古胶片", "阴天柔调"],
    "people": ["三人同行"],
}

UI_ORDER = [
    "people", "race", "person", "age", "maleFeature",
    "featureHead", "eyeColor", "bodyType", "hair", "skin", "waist", "hips", "legs",
    "top", "bottom", "garment", "outfitState", "socks", "shoes", "headwear", "jewelry", "makeup",
    "expression", "gazeDir", "headPose", "pose", "activity", "handAction", "interaction",
    "scene", "weather", "season", "light", "propObject", "pet",
    "composition", "lens", "camera", "film", "grade", "mood", "style",
]

TIERS = {
    "0": ["people", "race", "person", "age", "maleFeature"],
    "1": ["featureHead", "eyeColor", "bodyType", "hair", "skin", "waist", "hips", "legs"],
    "2": ["top", "bottom", "garment", "outfitState", "socks", "shoes", "headwear", "jewelry", "makeup"],
    "3": ["expression", "gazeDir", "headPose", "pose", "activity", "handAction", "interaction"],
    "4": ["scene", "weather", "season", "light", "propObject", "pet"],
    "5": ["composition", "lens", "camera", "film", "grade", "mood", "style"],
}

GROUPS = [
    {"id": "person", "label": "人物"},
    {"id": "body", "label": "身体"},
    {"id": "wear", "label": "穿着"},
    {"id": "act", "label": "动作与神情"},
    {"id": "scene", "label": "场景与画面"},
]


def main():
    with open(TARGET, encoding="utf-8") as handle:
        data = json.load(handle)

    by_id = {c["id"]: c for c in data["categories"]}

    for cid, (label, group, tier, who, slot_id, slot_label, pool) in NEW.items():
        by_id[cid] = {
            "id": cid,
            "label": label,
            "group": group,
            "tier": tier,
            "who": who,
            "slots": [{"id": slot_id, "label": slot_label, "pool": pool}],
        }

    # 上一轮已经写进词库、这一轮改名的词要显式清掉：脚本是幂等的，不清就留着一份旧词，
    # 下次体检报「跨分类重复」，而错误提示指向的却是新词。
    REMOVE = {"style": ["阴天散射光"], "handAction": ["两手交叠"]}
    for cid, words in REMOVE.items():
        for slot in by_id[cid]["slots"]:
            slot["pool"] = [w for w in slot["pool"] if w not in words]

    for cid, extra in EXTEND.items():
        category = by_id[cid]
        for slot in category["slots"]:
            for word in extra:
                if word not in slot["pool"]:
                    slot["pool"].append(word)

    missing = [cid for cid in UI_ORDER if cid not in by_id]
    if missing:
        raise SystemExit(f"uiOrder 里的分类不存在：{missing}")

    data["categories"] = [by_id[cid] for cid in UI_ORDER]

    # 三人同行要有人数映射，否则引擎按 1 人处理，画面只会画一个人。
    data["constraints"]["peopleCount"]["三人同行"] = 3

    meta = data["meta"]
    meta["version"] = "0.9.8.8382-sfw-b3"
    meta["promptTiers"] = TIERS
    meta["uiOrder"] = UI_ORDER
    meta["groups"] = GROUPS

    with open(TARGET, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    total = sum(len(s["pool"]) for c in data["categories"] for s in c["slots"])
    print(f"分类 {len(data['categories'])} 个，词条 {total} 条 -> {os.path.basename(TARGET)}")


if __name__ == "__main__":
    main()
