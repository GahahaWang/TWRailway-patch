"""Checks that the patches were applied to a patched 火車時刻表 APK.

- Hide ads: the google_mobile_ads Flutter plugin's onMethodCall handler starts with the
  injected no-fill failure and early return for every `load*` method channel call.
- Hide review prompt: the shared_preferences getBool implementations start with the
  injected overrides for the review dialog keys.
"""
import sys
import zipfile

from androguard.core.dex import DEX
from loguru import logger

logger.remove()  # androguard logs every parsed DEX section at DEBUG level.


def load_dex_files(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        names = sorted(n for n in apk.namelist() if n.startswith("classes") and n.endswith(".dex"))
        return [DEX(apk.read(name)) for name in names]


def find_handler(dex_files):
    for dex in dex_files:
        for class_def in dex.get_classes():
            for method in class_def.get_methods():
                if method.get_name() != "onMethodCall" or not method.get_code():
                    continue
                instructions = list(method.get_code().get_bc().get_instructions())
                outputs = [i.get_output() for i in instructions]
                if any('"loadBannerAd"' in o for o in outputs) and any('"MobileAds#initialize"' in o for o in outputs):
                    return class_def.get_name(), instructions
    return None, None


def find_review_hooks(dex_files):
    hooks = []
    for dex in dex_files:
        for class_def in dex.get_classes():
            for method in class_def.get_methods():
                if not method.get_code() or method.get_descriptor().split(")")[1] != "Ljava/lang/Boolean;":
                    continue
                head = [i.get_output() for i in list(method.get_code().get_bc().get_instructions())[:12]]
                if any('"shouldShowReviewKey"' in o for o in head) and any('"rateMyApp_doNotOpenAgain"' in o for o in head):
                    hooks.append(f"{class_def.get_name()}->{method.get_name()}{method.get_descriptor()}")
    return hooks


def check_hide_review_prompt(dex_files):
    hooks = find_review_hooks(dex_files)
    for hook in hooks:
        print(f"[OK] review prompt hook in {hook}")
    if not hooks:
        print("[MISSING] review prompt hook")
        return False
    print("Hide review prompt patch is applied")
    return True


def check_hide_ads(dex_files):
    class_name, instructions = find_handler(dex_files)
    if class_name is None:
        print("[MISSING] google_mobile_ads onMethodCall handler")
        return False

    head = [f"{i.get_name()} {i.get_output()}" for i in instructions[:40]]
    print(f"Handler: {class_name}->onMethodCall")
    print("\n".join("    " + line for line in head))

    text = "\n".join(head)
    checks = {
        'const-string "load"': 'const-string' in text and '"load"' in text,
        "String.startsWith": "Ljava/lang/String;->startsWith" in text,
        "onAdFailedToLoad (no fill)": '"No fill."' in text,
        "Result.success(null)": "invoke-interface" in text,
        "return-void": "return-void" in text,
    }
    for label, ok in checks.items():
        print(f"[{'OK' if ok else 'MISSING'}] {label}")
    if not all(checks.values()):
        return False
    print("Hide ads patch is applied")
    return True


def main():
    dex_files = load_dex_files(sys.argv[1])
    results = [check_hide_ads(dex_files), check_hide_review_prompt(dex_files)]
    if not all(results):
        sys.exit("Some patches were NOT applied")


if __name__ == "__main__":
    main()
