# -*- coding: utf-8 -*-
"""
把「启动网页.bat」注册为开机自启（登录后最小化运行）。

用法：双击 设置开机自启.bat，或 python setup_autostart.py
取消：python setup_autostart.py remove
"""
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))    # tools/
PROJECT_ROOT = os.path.dirname(HERE)                 # 91zb-web/
TARGET = os.path.join(PROJECT_ROOT, "start.bat")
STARTUP = os.path.join(os.environ.get("APPDATA", ""),
                       r"Microsoft\Windows\Start Menu\Programs\Startup")
LNK = os.path.join(STARTUP, "JJLive.lnk")


def make():
    if not os.path.isfile(TARGET):
        print(f"[错误] 找不到 {TARGET}")
        return 1
    if not os.path.isdir(STARTUP):
        print(f"[错误] 找不到启动目录：{STARTUP}")
        return 1
    ps = (
        "$w=New-Object -ComObject WScript.Shell;"
        f"$s=$w.CreateShortcut('{LNK}');"
        f"$s.TargetPath='{TARGET}';"
        f"$s.WorkingDirectory='{PROJECT_ROOT}';"
        "$s.WindowStyle=7;"
        "$s.Description='JJLive web player';"
        "$s.Save()"
    )
    rc = os.system(f'powershell -NoProfile -Command "{ps}"')
    if rc == 0 and os.path.isfile(LNK):
        print("[完成] 已设置为开机自启（登录后自动最小化启动）")
        print(f"       快捷方式：{LNK}")
        return 0
    print("[失败] 创建快捷方式失败")
    return 1


def remove():
    if os.path.isfile(LNK):
        os.remove(LNK)
        print("[完成] 已取消开机自启")
    else:
        print("未发现自启项，无需操作")
    return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "remove":
        sys.exit(remove())
    sys.exit(make())
