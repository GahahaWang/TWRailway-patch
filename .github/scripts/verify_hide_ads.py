"""Checks that the "Hide ads" patch was applied to a patched 火車時刻表 APK.

Finds the google_mobile_ads Flutter plugin's onMethodCall handler and verifies that it
starts with the injected early return for every `load*` method channel call.
"""
import sys
import zipfile

from androguard.core.dex import DEX
from loguru import logger

logger.remove()  # androguard logs every parsed DEX section at DEBUG level.


def find_handler(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        for name in sorted(n for n in apk.namelist() if n.startswith("classes") and n.endswith(".dex")):
            dex = DEX(apk.read(name))
            for class_def in dex.get_classes():
                for method in class_def.get_methods():
                    if method.get_name() != "onMethodCall" or not method.get_code():
                        continue
                    instructions = list(method.get_code().get_bc().get_instructions())
                    outputs = [i.get_output() for i in instructions]
                    if any('"loadBannerAd"' in o for o in outputs) and any('"MobileAds#initialize"' in o for o in outputs):
                        return class_def.get_name(), instructions
    return None, None


def main():
    class_name, instructions = find_handler(sys.argv[1])
    if class_name is None:
        sys.exit("google_mobile_ads onMethodCall handler not found")

    head = [f"{i.get_name()} {i.get_output()}" for i in instructions[:12]]
    print(f"Handler: {class_name}->onMethodCall")
    print("\n".join("    " + line for line in head))

    text = "\n".join(head)
    checks = {
        'const-string "load"': 'const-string' in text and '"load"' in text,
        "String.startsWith": "Ljava/lang/String;->startsWith" in text,
        "Result.success(null)": "invoke-interface" in text,
        "return-void": "return-void" in text,
    }
    for label, ok in checks.items():
        print(f"[{'OK' if ok else 'MISSING'}] {label}")
    if not all(checks.values()):
        sys.exit("Hide ads patch was NOT applied")
    print("Hide ads patch is applied")


if __name__ == "__main__":
    main()
