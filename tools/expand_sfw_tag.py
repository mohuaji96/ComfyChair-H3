#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""SFW 标签词库扩池。

只追加、不删除；已存在的词自动跳过，重复运行安全。
新词若与任何其它分类撞词会在运行时报出来——跨分类重复会让同一句里出现两遍。

人数、年龄感、焦段这类枚举分类不需要堆到 25，保持精简反而好用。
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "assets", "tag_catalog_sfw.json")

ADD = {
    "people": ["两位男性"],
    "age": ["二十五六", "三十五六"],
    "race": ["中亚面孔", "西欧面孔", "韩系面孔"],
    "person": ["编辑", "策展人", "茶艺师", "药剂师"],
    "featureHead": [
        "眼型细长", "眼尾上挑", "眼距略宽", "鼻尖小巧", "鼻翼窄",
        "唇峰明显", "唇形偏薄", "面部骨感", "面颊柔和", "眉尾淡",
    ],
    "eyeColor": ["浅茶色瞳", "琥珀棕瞳", "青灰瞳", "深青瞳", "近黑褐瞳", "灰褐瞳"],
    "bodyType": ["匀称修长", "肩背舒展", "上身偏窄", "线条柔和"],
    "hair": ["锁骨发", "耳下短发"],
    "skin": ["暖白肌", "清透肤色", "微红面颊", "柔和哑光", "偏冷调的白"],
    "waist": ["腰线内收", "腰身匀称", "腰部柔软", "腰腹平坦"],
    "hips": ["腰胯比匀称", "胯宽适中", "胯部圆润", "髋线柔和"],
    "legs": ["小腿修长", "大腿线条柔和", "膝盖并拢", "双腿微分", "脚踝露出"],
    "top": ["格纹衬衫", "驼色毛衣", "白色针织衫", "黑色衬衫", "浅蓝开衫", "米色马甲"],
    "bottom": ["黑色西装裤", "浅蓝裙裤", "米色半裙", "深灰百褶裙", "白色直筒裤", "驼色阔腿裤"],
    "garment": ["衬衫式连衣裙", "针织套装", "长款马甲裙", "收腰长裙", "亚麻背带裙"],
    "outfitState": ["衣摆垂顺", "领口整齐", "袖口翻边", "下摆被风吹起", "肩线服帖", "衣料微皱"],
    "socks": ["船型袜", "堆堆袜", "过膝袜", "棉质短袜", "浅灰长袜"],
    "shoes": ["白色乐福鞋", "棕色牛津鞋", "米色运动鞋", "黑色芭蕾鞋", "浅灰帆布鞋"],
    "headwear": ["宽檐帽", "小礼帽", "发簪", "蝴蝶结发饰", "丝绒发带", "细发链"],
    "jewelry": ["细圈耳环", "金属项链", "长链吊坠", "脚链", "素圈戒指", "细手链"],
    "makeup": ["淡棕眼影", "桃色唇", "自然睫毛", "雾面底妆", "浅棕修容"],
    "expression": ["浅笑", "失神", "挑眉", "抿唇", "眼神发亮"],
    "gazeDir": ["望向远处", "目光平和", "眼神下垂", "看向对方侧脸", "视线越过肩膀"],
    "headPose": ["头微微后仰", "脸偏向光源", "下巴收着", "头歪向一侧", "正视前方", "低垂着视线"],
    "pose": ["靠着椅背", "手扶门框", "侧坐在桌沿", "单脚点地"],
    "activity": ["擦桌子", "扫地", "擦窗", "摆餐具", "整理衣摆", "翻找抽屉"],
    "handAction": ["手指绕着杯沿", "手插在口袋里", "指尖按着桌面", "手扶帽檐", "手背贴着额头"],
    "interaction": ["一起整理书架", "并肩而立", "替对方拿着外套", "互相看着笑"],
    "scene": ["老屋的檐下", "有风的巷口", "河边的石阶", "开着的窗边"],
    "weather": ["微雨", "晴转多云", "夜雾", "薄霜", "闷热", "干爽"],
    "season": ["春分", "夏至", "霜降", "冬至"],
    "light": ["侧窗光", "顶光反射", "逆光发丝", "柔和顶光", "低角度的暖光"],
    "propObject": ["保温杯", "耳机", "相机包", "日历", "藤椅", "木箱"],
    "pet": ["三花猫", "狸花猫", "英短", "泰迪", "萨摩耶", "虎皮鹦鹉"],
    "composition": ["中心构图", "黄金分割", "前中后景分层", "窗框取景", "镜面反射", "开放构图", "紧密裁切", "仰拍留高天"],
    "lens": ["40mm", "105mm", "100mm 微距", "超广角", "移轴"],
    "camera": ["俯拍全身", "仰拍特写", "侧面中景", "过肩双人"],
    "film": ["细密噪点", "色彩偏移", "高光溢出", "暗部发灰", "底片边缘"],
    "grade": ["青绿调", "暖橘调", "冷灰调", "柔粉调"],
    "mood": ["温暖", "疏离", "安静从容", "轻快明亮"],
    "maleFeature": ["眉峰明显", "眼神清亮", "下颌线利落", "耳廓清晰", "鼻梁高挺", "肤色偏暖"],
    "style": ["日式清新", "清透自然", "街头纪实", "低调生活感", "柔光写真"],
}


def main():
    with open(TARGET, encoding="utf-8") as handle:
        data = json.load(handle)

    by_id = {c["id"]: c for c in data["categories"]}
    taken = {}
    for c in data["categories"]:
        for slot in c["slots"]:
            for word in slot["pool"]:
                taken[word] = c["id"]

    added, skipped, clashes = 0, 0, []
    for cid, words in ADD.items():
        if cid not in by_id:
            raise SystemExit(f"没有这个分类：{cid}")
        slot = by_id[cid]["slots"][0]
        for word in words:
            if word in taken:
                if taken[word] != cid:
                    clashes.append(f"{word}（已在 {taken[word]}）")
                else:
                    skipped += 1
                continue
            slot["pool"].append(word)
            taken[word] = cid
            added += 1

    if clashes:
        raise SystemExit("跨分类重复：" + "、".join(clashes))

    data["meta"]["version"] = "0.9.8.8382-sfw-exp1"

    with open(TARGET, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")

    total = sum(len(s["pool"]) for c in data["categories"] for s in c["slots"])
    print(f"新增 {added} 条，跳过已存在 {skipped} 条，现在共 {total} 条")


if __name__ == "__main__":
    main()
