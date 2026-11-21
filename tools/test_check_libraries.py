# -*- coding: utf-8 -*-
"""回归测试：把真实踩过的五个 bug 逐个注入，确认 check_libraries.py 能抓到。

每个用例都在临时副本上跑，不碰真实数据。

    python tools/test_check_libraries.py
"""

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "sh", "hnet",
                      "comfychair", "workflow", "TagComposerEngine.kt")
CHECKER = os.path.join(HERE, "check_libraries.py")


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with io.open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def run(assets, engine):
    proc = subprocess.run(
        [sys.executable, CHECKER, "--assets", assets, "--engine", engine],
        capture_output=True,
    )
    return proc.returncode, proc.stdout.decode("utf-8", "replace")


def expect(name, stdout, needle, should_appear=True):
    hit = needle in stdout
    if hit == should_appear:
        print("  PASS  {}".format(name))
        return True
    print("  FAIL  {}（期望{}出现「{}」）".format(
        name, "" if should_appear else "不", needle))
    for line in stdout.splitlines():
        if "错误" in line:
            print("        {}".format(line.strip()))
    return False


def case_baseline(assets, engine):
    """未改动的数据必须是干净的，否则回归测试本身没有意义。"""
    code, out = run(assets, engine)
    print("基准（未改动）")
    ok = expect("无死依赖", out, "dead.requires", False)
    ok &= expect("无关口守卫", out, "engine.preferPeople", False)
    ok &= expect("无覆盖缺口", out, "engine.cover", False)
    return ok


def case_dead_requires(assets, engine):
    """168 条 requires=["男"]：names 是尾部匹配，「一男一女」不以「男」结尾。"""
    path = os.path.join(assets, "tag_catalog.json")
    data = json.loads(read(path))
    data["constraints"]["requires"]["后入"] = ["男"]
    write(path, json.dumps(data, ensure_ascii=False))
    _, out = run(assets, engine)
    print("死依赖（requires=[\"男\"]）")
    return expect("抓到死依赖", out, "dead.requires")


def case_face_twice(assets, engine):
    """take(MALE_FACE) 删掉 → maleFace 会被 rest 兜底重复渲染。"""
    source = read(engine)
    write(engine, source.replace("take(MALE_FACE)", "Unit"))
    _, out = run(assets, engine)
    print("脸部重复渲染（删 take(MALE_FACE)）")
    return expect("抓到覆盖缺口", out, "engine.cover")


def case_glasses_dup(assets, engine):
    """剧情库的配饰池里塞回一条眼镜，与独立 glasses 池重复。"""
    path = os.path.join(assets, "story_catalog.json")
    data = json.loads(read(path))
    pools = data["pools"]
    twin = pools["glasses"][0]
    text = twin["text"] if isinstance(twin, dict) else twin
    pools["accent"].append({"text": text})
    write(path, json.dumps(data, ensure_ascii=False))
    _, out = run(assets, engine)
    print("跨池重复（眼镜）")
    return expect("抓到重复", out, "跨池重复「{}」".format(text))


def case_dangling_category(assets, engine):
    """数据里删掉分类，代码仍 take 它。"""
    path = os.path.join(assets, "tag_catalog.json")
    data = json.loads(read(path))
    data["categories"] = [c for c in data["categories"] if c["id"] != "headPose"]
    write(path, json.dumps(data, ensure_ascii=False))
    _, out = run(assets, engine)
    print("代码引用已删分类")
    return expect("抓到悬空引用", out, "engine.dangling")


def case_no_guard(assets, engine):
    """preferPeople 去掉白名单守卫 → 任意分类的 people=N 单词都会独占。"""
    source = read(engine)
    stripped = source.replace("if (categoryId !in PEOPLE_SENSITIVE) return fits", "")
    if stripped == source:
        print("  SKIP  preferPeople 守卫未找到，源码可能已改结构")
        return True
    write(engine, stripped)
    _, out = run(assets, engine)
    print("preferPeople 缺守卫")
    return expect("抓到缺守卫", out, "engine.preferPeople")


def case_race_gate(assets, engine):
    """把「异族不叠身份」的规则删掉 → 精灵、魅魔会重新带上护士、医生。"""
    path = os.path.join(assets, "tag_catalog.json")
    data = json.loads(read(path))
    rules = data["rules"]["skipCategories"]
    data["rules"]["skipCategories"] = [r for r in rules if "person.origin" not in (r.get("skip") or [])]
    write(path, json.dumps(data, ensure_ascii=False))
    _, out = run(assets, engine)
    print("异族叠身份（删掉 person.origin 规则）")
    return expect("抓到漏关身份", out, "raceGate.person.origin")


def main():
    cases = [
        ("baseline", case_baseline),
        ("race_gate", case_race_gate),
        ("dead_requires", case_dead_requires),
        ("face_twice", case_face_twice),
        ("glasses_dup", case_glasses_dup),
        ("dangling_category", case_dangling_category),
        ("no_guard", case_no_guard),
    ]
    results = []
    for name, case in cases:
        work = tempfile.mkdtemp(prefix="h3check_")
        assets = os.path.join(work, "assets")
        shutil.copytree(ASSETS, assets)
        engine = os.path.join(work, "TagComposerEngine.kt")
        shutil.copy2(ENGINE, engine)
        try:
            results.append(case(assets, engine))
        finally:
            shutil.rmtree(work, ignore_errors=True)

    print()
    if all(results):
        print("全部通过（{}/{}）".format(sum(results), len(results)))
        return 0
    print("失败 {}/{}".format(len(results) - sum(results), len(results)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
