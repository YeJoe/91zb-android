# 91zb-web — 91直播 房间列表网页版

本地运行的直播间列表 + 播放器。目录已按用途分类，双击 `start.bat` 即可。

## 目录结构

```
91zb-web/
├─ web/                前端静态资源（由服务器直接托管）
│  ├─ index.html       页面（内嵌房间数据与播放器逻辑）
│  ├─ jessibuca.js     WASM 播放器内核
│  ├─ decoder.js       WASM 解码胶水
│  ├─ decoder.wasm     解码器
│  └─ mpegts.js        FLV/TS 播放备用内核
├─ server/
│  └─ serve.py         本地 HTTP 服务：静态托管 + /img 封面代理 + /proxy 流代理 + HLS 转码
├─ tools/
│  ├─ run.py           一键启动：校验令牌 → 抓房间 → 生成网页 → 起服务 → 开浏览器
│  ├─ build_web.py     由 data/rooms_all.json 生成 web/index.html
│  ├─ jj_rooms.py      按分类抓取在线房间列表
│  ├─ jj_client.py     接口加密/签名客户端（jj_rooms 依赖）
│  └─ setup_autostart.py  设置/取消开机自启
├─ data/
│  ├─ rooms_all.json   房间数据（最新抓取结果）
│  └─ token.txt        接口令牌（authorization）
├─ _imgcache/          封面图缓存（自动生成，可安全删除）
├─ _logs/              运行日志（自动生成，可安全删除）
├─ start.bat           双击启动
└─ autostart.bat       设置开机自启
```

## 启动

1. 双击 `start.bat`
2. 首次运行若无令牌，按提示粘贴 `authorization`（会写入 `data/token.txt`）
3. 服务就绪后自动打开 `http://127.0.0.1:8899/`

命令行等价写法：

```bat
python tools/run.py                 :: 完整流程 + 自动开浏览器
python tools/run.py --no-open       :: 起服务但不自动开浏览器
python tools/run.py --serve-only    :: 用现有数据只起服务（不抓取/不重建）
python tools/run.py --port 9000     :: 换端口
```

## 开机自启

- 设置：双击 `autostart.bat`
- 取消：`python tools/setup_autostart.py remove`

## 说明

- 依赖 Python 3（仅标准库，无需 pip 安装）。
- 可见封面经 `server/serve.py` 的 `/img` 代理下发（图床文件头带 1 字节防盗链前缀，浏览器直接引用会解码失败，必须由代理剥离）。
- 可选 ffmpeg：放到 `_tools/ffmpeg/ffmpeg.exe` 可启用 FLV→HLS 转码（不放开源/系统 PATH 中的也行）。
