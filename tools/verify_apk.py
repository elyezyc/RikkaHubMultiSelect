#!/usr/bin/env python3
"""检查构建出来的模块 APK：入口、清单、dex 内容，以及有没有把工具链里的东西打进去。

用法： python3 tools/verify_apk.py dist/RikkaHubMultiSelect-1.0.0.apk
SPDX-License-Identifier: GPL-3.0-or-later
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEXDUMP = os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/dexdump")
AAPT2 = os.path.join(ROOT, "tools/android-sdk/build-tools/36.0.0/aapt2")

PACKAGE = "com.elyezyc.rikkahubmultiselect"
ENTRY = PACKAGE + ".HookEntry"
EXPECTED_CLASSES = [
    "HookEntry", "Host", "Suspend", "Palette", "MultiSelectDialog", "FloatingButton",
    "Conversations", "SettingsApi", "Actions", "Copy", "Uuids", "Sql", "T", "Reflect",
    "XLog", "ToastHelper", "Ui", "MainActivity",
]
FORBIDDEN_CLASSES = [
    "Lkotlin/coroutines/jvm/internal/ContinuationImpl;",
    "Lkotlin/coroutines/EmptyCoroutineContext;",
    "Lde/robv/android/xposed/XposedBridge;",
]


def descriptors(apk):
    found = set()
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            names = [n for n in archive.namelist() if re.match(r"classes\d*\.dex$", n)]
            for name in names:
                path = os.path.join(tmp, name.replace("/", "_"))
                with open(path, "wb") as handle:
                    handle.write(archive.read(name))
                out = subprocess.run([DEXDUMP, path], capture_output=True, text=True,
                                     errors="replace").stdout
                found.update(re.findall(r"^  Class descriptor\s+: '(.*)'$", out, re.M))
    return found


def manifest(apk):
    if os.path.isfile(AAPT2):
        out = subprocess.run([AAPT2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                             capture_output=True, text=True, errors="replace")
        if out.returncode == 0 and out.stdout.strip():
            return out.stdout
    with zipfile.ZipFile(apk) as archive:
        return archive.read("AndroidManifest.xml").decode("utf-16-le", "replace")


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        ROOT, "dist", "RikkaHubMultiSelect-1.0.0.apk")
    if not os.path.isfile(apk):
        raise SystemExit("找不到 APK: %s" % apk)
    if not os.path.isfile(DEXDUMP):
        raise SystemExit("找不到 dexdump：%s\n先跑 ./tools/fetch_toolchain.sh" % DEXDUMP)

    failures = []

    def check(ok, label, detail=""):
        print(("ok    " if ok else "FAIL  ") + label + (("  " + detail) if detail and not ok else ""))
        if not ok:
            failures.append(label)

    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        check("assets/xposed_init" in names, "assets/xposed_init 存在")
        if "assets/xposed_init" in names:
            init = archive.read("assets/xposed_init").decode("utf-8", "replace").strip()
            check(init == ENTRY, "xposed_init 指向 %s" % ENTRY, "实际: %r" % init)

    text = manifest(apk)
    check("xposedmodule" in text, "清单声明 xposedmodule")
    check("xposedminversion" in text, "清单声明 xposedminversion")
    check("xposeddescription" in text, "清单声明 xposeddescription")
    check("xposedscope" in text, "清单声明 xposedscope")
    check(PACKAGE in text, "清单包名是 %s" % PACKAGE)

    found = descriptors(apk)
    for simple in EXPECTED_CLASSES:
        desc = "L%s/%s;" % (PACKAGE.replace(".", "/"), simple)
        check(desc in found, "dex 里有 %s" % simple)

    for forbidden in FORBIDDEN_CLASSES:
        check(forbidden not in found, "没有打包 %s" % forbidden)

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            dex = os.path.join(tmp, "classes.dex")
            with open(dex, "wb") as handle:
                handle.write(archive.read("classes.dex"))
        raw = open(dex, "rb").read()
        check(b"<redacted>" not in raw, "dex 里没有签名口令")
        check(b"rikkahubmultiselect-key.jks" not in raw, "dex 里没有密钥库路径")

    print()
    if failures:
        raise SystemExit("产物检查未通过：%d 项" % len(failures))
    print("产物检查通过")


if __name__ == "__main__":
    main()
