# -*- coding: utf-8 -*-
"""
一键流程：检查令牌 -> 刷新房间数据 -> 生成网页 -> 启动服务 -> 提示访问地址

    python run.py                 完整流程（默认不自动开浏览器，手动访问提示的网址）
    python run.py --open          服务就绪后自动打开浏览器
    python run.py --serve-only    跳过抓取与生成，直接用现有数据起服务
    python run.py --port 9000     换端口

把所有逻辑放 Python 里，避免 cmd 处理中文/括号/引号时把批处理解析崩掉。
"""
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.request

# 不生成 __pycache__，保持目录干净（子进程通过环境变量继承）
sys.dont_write_bytecode = True
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"

HERE = os.path.dirname(os.path.abspath(__file__))    # tools/
PROJECT_ROOT = os.path.dirname(HERE)                 # 91zb-web/
WEB_DIR = os.path.join(PROJECT_ROOT, "web")          # 前端
DATA_DIR = os.path.join(PROJECT_ROOT, "data")        # 数据
sys.path.insert(0, HERE)
os.chdir(HERE)

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
    sys.stderr.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
except Exception:
    pass

PY = sys.executable
TOKEN_FILE = os.path.join(DATA_DIR, "token.txt")
DEFAULT_PORT = 8899

# 指定浏览器：按顺序找第一个存在的；都不存在则用系统默认
# 均为便携版，启动时 cwd 必须设为其所在目录，否则找不到内核 dll
BROWSERS = [
    r"D:\Apps\CentBrowserPortable\chrome.exe",
    r"D:\Apps\portable_x64_release_150_6_7_2\catsxp.exe",
]


def load_token():
    from jj_rooms import load_token as _lt
    return _lt(TOKEN_FILE)


def save_token(t):
    with open(TOKEN_FILE, "w", encoding="ascii", newline="") as f:
        f.write(t.strip())


def run(args):
    return subprocess.run([PY] + args, cwd=HERE).returncode


def port_busy(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        s.bind(("0.0.0.0", port))
        return False
    except OSError:
        return True
    finally:
        s.close()


def pick_port(port):
    """端口被占用时顺延，最多试 10 个。"""
    for p in range(port, port + 10):
        if not port_busy(p):
            return p
    return None


def open_browser(url):
    cands = []
    if "--browser" in sys.argv:
        try:
            cands.append(sys.argv[sys.argv.index("--browser") + 1])
        except Exception:
            pass
    cands += [b for b in BROWSERS if os.path.isfile(b)]

    for exe in cands:
        if not os.path.isfile(exe):
            continue
        try:
            # 便携版必须从自身目录启动，否则找不到内核 dll / User Data
            p = subprocess.Popen([exe, url], cwd=os.path.dirname(exe), close_fds=True)
            time.sleep(1.5)
            if p.poll() is None:
                print(f"  已用 {os.path.basename(exe)} 打开：{url}")
                return
            print(f"  [提示] {os.path.basename(exe)} 立即退出（exit={p.returncode}），换下一个")
        except Exception as e:
            print(f"  [提示] 启动 {os.path.basename(exe)} 失败（{e}），换下一个")

    try:
        os.startfile(url)          # Windows：交给默认浏览器
        print(f"  已用默认浏览器打开：{url}")
    except Exception as e:
        print(f"  [提示] 自动打开失败（{e}），请手动访问：{url}")


def wait_and_open(port, timeout=30):
    """等服务真正监听后再打开，避免打开到「无法连接」。"""
    url = f"http://127.0.0.1:{port}/"
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=2) as r:
                if r.status == 200:
                    open_browser(url)
                    return
        except Exception:
            time.sleep(0.4)
    print(f"  [提示] 服务在 {timeout}s 内未就绪，请手动访问：{url}")


def serve(port, do_open):
    proc = subprocess.Popen([PY, os.path.join(PROJECT_ROOT, "server", "serve.py"), str(port)], cwd=PROJECT_ROOT)
    if do_open:
        threading.Thread(target=wait_and_open, args=(port,), daemon=True).start()
    try:
        return proc.wait()
    except KeyboardInterrupt:
        print("\n正在停止服务 ...")
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        return 0


def main():
    args = sys.argv[1:]
    do_open = "--open" in args
    serve_only = "--serve-only" in args
    port = DEFAULT_PORT
    if "--port" in args:
        try:
            port = int(args[args.index("--port") + 1])
        except Exception:
            print("--port 参数无效，使用默认端口")
            port = DEFAULT_PORT

    print("=" * 60)
    print("  91直播 房间列表 · 一键启动")
    print("=" * 60)

    if not serve_only:
        tok = load_token()
        if not tok:
            print("\n未找到有效令牌。")
            print("请粘贴 authorization（很长的一串十六进制）后回车：")
            try:
                tok = input("> ").strip()
            except (EOFError, KeyboardInterrupt):
                tok = ""
            if not tok:
                print("[错误] 未输入令牌，退出。")
                return 1
            save_token(tok)
            print("已写入 token.txt")

        print("\n[1/3] 按类别拉取房间列表 ...")
        if run(["jj_rooms.py", "--token", tok, "--out", os.path.join(DATA_DIR, "rooms_all.json"), "--quiet"]) != 0:
            print("[失败] 拉取出错，令牌可能已过期。")
            print("       请删除 token.txt 后重试并粘贴新令牌。")
            return 1

        print("\n[2/3] 生成网页 ...")
        if run(["build_web.py", os.path.join(DATA_DIR, "rooms_all.json"), WEB_DIR]) != 0:
            print("[失败] 生成网页出错")
            return 1
    else:
        if not os.path.isfile(os.path.join(WEB_DIR, "index.html")):
            print("[错误] 没有 web/index.html，请先正常跑一次（不要加 --serve-only）")
            return 1
        print("\n[跳过] 使用现有 rooms_all.json / web/index.html")

    if not os.path.isfile(os.path.join(WEB_DIR, "index.html")):
        print("[错误] 缺少 web/index.html")
        return 1

    use_port = pick_port(port)
    if use_port is None:
        print(f"[错误] 端口 {port}~{port + 9} 全部被占用，请关闭占用程序或改用 --port")
        return 1
    if use_port != port:
        print(f"\n[提示] 端口 {port} 被占用，改用 {use_port}")

    print(f"\n[3/3] 启动本地服务器（端口 {use_port}）...")
    if do_open:
        print("  服务就绪后会自动打开浏览器；关闭本窗口即停止")
    else:
        print(f"  请手动访问 http://127.0.0.1:{use_port}/ ；关闭本窗口即停止")
    print()

    return serve(use_port, do_open)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n已停止")
        sys.exit(0)
