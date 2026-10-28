"""libkiwi.so 静态画像（idalib 版，实时日志，便于定位卡点）。

    set IDADIR=D:\\Apps\\IDA Pro 9.3
    python tools/idalib_kiwi.py libs/arm64-v8a_libkiwi.so

产出：tools/kiwi_report.txt
"""
import os, sys, io, time, traceback

IDAPRO_DIR = r"D:\Apps\IDA Pro 9.3"
os.environ.setdefault('IDADIR', IDAPRO_DIR)
for d in (IDAPRO_DIR, os.path.join(IDAPRO_DIR, 'python')):
    if d not in sys.path:
        sys.path.insert(0, d)


def log(*a):
    print(*a, flush=True)


PATH = sys.argv[1] if len(sys.argv) > 1 else os.path.join('libs', 'arm64-v8a_libkiwi.so')
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'kiwi_report.txt')

KEYWORDS = [
    'aes', 'AES', 'rc4', 'RC4', 'md5', 'MD5', 'sha1', 'sha256', 'hmac',
    'base64', 'Base64', 'gzip', 'zlib', 'inflate', 'deflate', 'compress',
    'encrypt', 'decrypt', 'cipher', 'Cipher', 'pkcs', 'PKCS',
    'tunnel', 'Tunnel', 'kiwi', 'Kiwi', 'KIWI', 'websocket', 'WebSocket',
    'Upgrade', 'chunked', 'proxy', 'Proxy',
    'token', 'Token', 'authorization', 'Authorization', 'Bearer',
    'sign', 'Sign', 'secret', 'Secret', 'appid', 'appId', 'nonce',
    'Java_', 'JNI',
    'frida', 'ptrace', 'magisk', 'xposed', 'emulator',
    'protobuf', 'proto', 'ssl', 'SSL', 'cert',
]


def main():
    log('[1] import idapro')
    import idapro

    log('[2] open_database:', PATH)
    t0 = time.time()
    rc = idapro.open_database(os.path.abspath(PATH), True)
    log('[3] open_database rc=%s  耗时 %.1fs' % (rc, time.time() - t0))
    if rc != 0:
        log('!! open_database 返回非 0, 退出码', rc)
        return

    import idaapi, idautils, ida_funcs, ida_segment
    log('[4] ida_* 模块就绪')

    buf = io.StringIO()
    w = buf.write
    try:
        ver = '.'.join(str(x) for x in idapro.get_library_version())
    except Exception:
        ver = '?'
    w('=' * 78 + '\n')
    w('libkiwi.so 静态画像（idalib %s）\n' % ver)
    w('文件: %s\n' % PATH)
    w('=' * 78 + '\n')
    w('处理器: %s\n' % idaapi.get_idp_name())
    try:
        w('64 位: %s\n' % bool(idaapi.inf_is_64bit()))
    except Exception:
        pass
    w('函数总数: %d\n' % ida_funcs.get_func_qty())

    w('\n--- 段 ---\n')
    for i in range(idaapi.get_segm_qty()):
        s = idaapi.getnseg(i)
        if not s:
            continue
        nm = ida_segment.get_segm_name(s)
        perm = ''.join(c for c, bit in (('r', idaapi.SEGPERM_READ),
                                        ('w', idaapi.SEGPERM_WRITE),
                                        ('x', idaapi.SEGPERM_EXEC))
                       if s.perm & bit)
        w('  %-14s %016x-%016x %s\n' % (nm, s.start_ea, s.end_ea, perm))

    log('[5] 枚举导出')
    entries = []
    for it in idautils.Entries():
        try:
            _idx, _ord, ea, nm = it
        except Exception:
            continue
        if nm is None:
            nm = ida_funcs.get_func_name(ea) if False else ''
        entries.append((ea, nm or ''))
    jni = [(ea, nm) for ea, nm in entries if nm.startswith('Java_')]
    w('\n--- 导出 %d 个，其中 JNI(Java_*) %d 个 ---\n' % (len(entries), len(jni)))
    for ea, nm in sorted(jni):
        w('  %016x  %s\n' % (ea, nm))
    w('\n  其他导出（前 100）:\n')
    for ea, nm in sorted([(ea, nm) for ea, nm in entries
                          if not nm.startswith('Java_')])[:100]:
        w('  %016x  %s\n' % (ea, nm))

    log('[6] 遍历字符串')
    hits = {}
    total = 0
    for s in idautils.Strings():
        total += 1
        try:
            txt = str(s)
        except Exception:
            continue
        for kw in KEYWORDS:
            if kw in txt:
                hits.setdefault(kw, []).append((s.ea, txt))
                break
    log('[7] 字符串 %d 条，命中 %d 类' % (total, len(hits)))
    w('\n--- 关键字符串（字符串总数 %d）---\n' % total)
    for kw in KEYWORDS:
        lst = hits.get(kw)
        if not lst:
            continue
        w('\n### %r  命中 %d\n' % (kw, len(lst)))
        for ea, txt in lst[:18]:
            fn = ida_funcs.get_func(ea)
            fa = '%016x' % fn.start_ea if fn else '----------------'
            fnm = ida_funcs.get_func_name(fn.start_ea) if fn else '?'
            w('  %016x  in %s %s\n' % (ea, fa, fnm))
            w('      "%s"\n' % txt.replace('\n', '\\n')[:120])

    log('[8] 统计大函数')
    sizes = []
    for ea in idautils.Functions():
        fn = ida_funcs.get_func(ea)
        if fn:
            sizes.append((fn.end_ea - fn.start_ea, ea, ida_funcs.get_func_name(ea)))
    sizes.sort(reverse=True)
    w('\n--- 最大的 40 个函数 ---\n')
    for sz, ea, nm in sizes[:40]:
        nref = len(list(idautils.CodeRefsTo(ea, 0)))
        w('  size=%7d xrefs=%3d  %016x  %s\n' % (sz, nref, ea, nm))

    w('\n--- 完成 ---\n')
    with open(OUT, 'w', encoding='utf-8') as f:
        f.write(buf.getvalue())
    log('[9] 报告已写入', OUT)
    idapro.close_database(save=False)


try:
    main()
except Exception:
    log('!! 异常:')
    traceback.print_exc()
    raise
