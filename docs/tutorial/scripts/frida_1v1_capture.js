// 1v1 直播「地址」抓取脚本（Agora RTC 互动直播）
// 说明：本 App 的 1v1 / 直播间均走 Agora Interactive Live Streaming，
// 没有 .flv/.m3u8 这种可直接播放的 URL。"直播地址" = appId + channelName + token + uid，
// 需用 Agora SDK 以 audience/broadcaster 身份 joinChannel 才能看到画面。
//
// 抓取策略：
//   1) hook Dart C API Dart_StringToCString / Dart_StringToUTF8 —— 插件把 token/channel/appId
//      转成 C 字符串时必然经过，onLeave 读 C 字符串即可（最稳，不依赖 Dart 对象布局）。
//   2) hook RtcEngineImpl::joinChannel(0xc119d4) —— 进频道瞬间打印 uid，并在前后日志里对应出
//      token 与 channelName。
//   3) hook 关键 gating 函数，暴露「看不了」的卡点：
//      requestInvite1V1Check(0xed58cc) / pay1v1(0xc35d0c) / getAgoraToken(0xc1087c) /
//      getAgoraAppId(0xc251b4)
//
// 运行：frida -U -p <pid> -l frida_1v1_capture.js   （或 -f <pkg> --no-pause）
// 然后进一个 1v1 房间，日志里搜 token/channel/006 / appId / joinChannel。

const LIBAPP = 'libapp.so';

// blutter 偏移（指令段内偏移，需加 RX 段基址）
const OFF_JOIN_CHANNEL   = 0xc119d4; // RtcEngineImpl::joinChannel
const OFF_GET_AGORA_APPID= 0xc251b4; // ParamManager::getAgoraAppId
const OFF_GET_AGORA_TOKEN= 0xc1087c; // AnchorRequest::getAgoraToken  (/voiceChat/getAgoraToken)
const OFF_INVITE_1V1     = 0xed58cc; // RequestRepositoryHome::requestInvite1V1Check
const OFF_PAY_1V1        = 0xc35d0c; // AudienceRequest::pay1v1

function getCodeBase() {
    const m = Process.findModuleByName(LIBAPP);
    if (!m) return null;
    const ranges = m.enumerateRanges('r-x');
    let best = null;
    for (const r of ranges) if (!best || r.size > best.size) best = r;
    return best ? best.base : m.base;
}

// 尝试直接读 Dart String（OneByteString 内联布局兜底，失败不影响主路径）
function readDartString(ptr) {
    try {
        if (ptr.isNull()) return null;
        let len = ptr.add(8).readUS32();      // _length 字段（Smi）
        if (len & 1) len = len >> 1;
        if (len < 0 || len > 8192) return null;
        const bytes = ptr.add(0x10).readByteArray(len); // OneByteString 数据在 header+len+hash 之后
        if (!bytes) return null;
        return Array.from(new Uint8Array(bytes)).map(b => String.fromCharCode(b)).join('');
    } catch (e) { return null; }
}

let strIdx = 0;
function hookStringConv() {
    const names = ['Dart_StringToCString', 'Dart_StringToUTF8'];
    for (const n of names) {
        const addr = Module.findExportByName(null, n);
        if (!addr) { console.log('[-] ' + n + ' not found'); continue; }
        Interceptor.attach(addr, {
            onEnter(args) { this.out = args[1]; },
            onLeave() {
                try {
                    if (this.out.isNull()) return;
                    const cstr = this.out.readPointer();
                    if (cstr.isNull()) return;
                    const s = cstr.readCString();
                    if (!s) return;
                    // 过滤：只打印像地址/凭据/关键 API 的字符串，避免刷屏
                    const interesting =
                        /http/i.test(s) ||
                        /agora/i.test(s) ||
                        /channel/i.test(s) ||
                        /token/i.test(s) ||
                        /1v1|invite|pay|uid|live/i.test(s) ||
                        /^[A-Za-z0-9+/=]{24,}$/.test(s); // token-like (base64/jwt)
                    if (s.length >= 12 && interesting) {
                        strIdx++;
                        console.log('[' + strIdx + '][Str:' + n + '] ' + s);
                    }
                } catch (e) {}
            }
        });
        console.log('[+] hooked ' + n + ' @ ' + addr);
    }
}

function hookByOffset(name, off, onArgs) {
    const base = getCodeBase();
    if (!base) { console.log('[-] ' + name + ': libapp.so 未加载'); return; }
    const addr = base.add(off);
    try {
        Interceptor.attach(addr, {
            onEnter(args) {
                try { onArgs(args); } catch (e) { console.log('[' + name + '] arg read err: ' + e); }
            }
        });
        console.log('[+] hooked ' + name + ' @ ' + addr);
    } catch (e) {
        console.log('[-] ' + name + ' hook failed: ' + e);
    }
}

function start() {
    const a = Process.findModuleByName(LIBAPP);
    if (!a) { setTimeout(start, 200); return; }

    hookStringConv();

    // joinChannel：打印 uid（arg4, Smi），token/channel 见前后 Str 日志
    hookByOffset('joinChannel', OFF_JOIN_CHANNEL, (args) => {
        let uid = args[4];
        let u = uid.toInt();
        if ((u & 1) === 1) u = u >> 1; // Smi
        const tok = readDartString(args[1]);
        const ch = readDartString(args[2]);
        console.log('========== [joinChannel] uid=' + u +
            ' token=' + (tok ? tok.slice(0, 48) + '...' : args[1]) +
            ' channel=' + (ch ? ch : args[2]) + ' ==========');
    });

    hookByOffset('getAgoraAppId', OFF_GET_AGORA_APPID, () => {
        console.log('[flow] getAgoraAppId() called (appId 将在随后的 Str 日志出现)');
    });
    hookByOffset('getAgoraToken', OFF_GET_AGORA_TOKEN, () => {
        console.log('[flow] getAgoraToken() -> /voiceChat/getAgoraToken 请求发出');
    });
    hookByOffset('requestInvite1V1Check', OFF_INVITE_1V1, () => {
        console.log('[flow] requestInvite1V1Check() 1v1 邀请资格校验');
    });
    hookByOffset('pay1v1', OFF_PAY_1V1, () => {
        console.log('[flow] pay1v1() 1v1 扣费（若失败/余额不足则无法观看）');
    });

    console.log('[*] frida_1v1_capture ready. 进入 1v1 房间后查看 joinChannel / token / channel / appId 日志');
}

start();
