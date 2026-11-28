"""Verify module identity in source and in the packaged APK."""
import argparse
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = "com.yagay.xhongguo"
ENTRY = PACKAGE + ".MainHook"
OLD = "xyz." + "kejiyu.hongguo"
app = ROOT / "app"
gradle = (app / "build.gradle").read_text()
assert re.search(r"namespace\s+['\"]" + re.escape(PACKAGE) + r"['\"]", gradle), "namespace mismatch"
assert re.search(r"applicationId\s+['\"]" + re.escape(PACKAGE) + r"['\"]", gradle), "applicationId mismatch"
manifest = (app / "src/main/AndroidManifest.xml").read_text()
assert PACKAGE + ".settings" in manifest, "Provider authority mismatch"
xposed = app / "src/main/resources/META-INF/xposed"
assert (xposed / "java_init.list").read_text().strip() == ENTRY
assert {PACKAGE, "android", "com.phoenix.read", "com.phoenix.read.oversea.gp"} <= set((xposed / "scope.list").read_text().splitlines())
assert (app / "src/main/assets/xposed_init").read_text().strip() == ENTRY
for p in (app / "src").rglob("*"):
    if p.is_file() and p.suffix in {".kt", ".java", ".xml", ".list", ".prop"}:
        assert OLD not in p.read_text(), f"Old namespace: {p}"
args = argparse.ArgumentParser()
args.add_argument("--apk", action="store_true")
if args.parse_args().apk:
    apks = list((app / "build/outputs/apk/debug").glob("*.apk"))
    assert apks, "No Debug APK"
    for apk in apks:
        with zipfile.ZipFile(apk) as z:
            assert z.read("META-INF/xposed/java_init.list").decode().strip() == ENTRY
            assert PACKAGE in z.read("META-INF/xposed/scope.list").decode().splitlines()
            assert "AndroidManifest.xml" in z.namelist()
            assert "classes.dex" in z.namelist()
print("Module identity verified")
