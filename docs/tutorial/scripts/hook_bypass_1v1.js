console.log("[*] Hook Script - Bypass 1v1 Performance Placeholder");
console.log("[*] Package: OHTSUAXFqfMfMy.uXQLlEIsKzjhr.GPzgdVVjuRqs.rFfrnSNcNAUwRj");

function setupHooks() {
    var libapp = Process.findModuleByName('libapp.so');
    if (!libapp) {
        console.log("[!] libapp.so not found, retrying in 500ms...");
        setTimeout(setupHooks, 500);
        return;
    }
    
    var base = libapp.base;
    console.log("[*] libapp.so found at: " + base);
    
    // ==========================================
    // 1. Patch liveRoomView 函数，跳过1v1 viewType检查
    // ==========================================
    // liveRoomView (0xd68d5c) 中有两个关键分支：
    // 0xd68dac: b.ne #0xd68df0  ; 如果不是showing，跳到检查connecting
    // 0xd68dfc: b.ne #0xd68e5c  ; 如果不是connecting，跳到正常流程
    // 策略：将这两个 b.ne 改为 b (无条件跳转)，直接跳过1v1分支
    var liveRoomViewAddr = base.add(0xd68d5c);
    
    // ARM64 b 指令机器码: 0x14000000 | (offset / 4)
    // Patch 0xd68dac: b.ne -> b (跳转到0xd68df0)
    // 偏移: (0xd68df0 - 0xd68dac) / 4 = 0x44 / 4 = 0x11
    var patch1 = liveRoomViewAddr.add(0x50);  // 0xd68d5c + 0x50 = 0xd68dac
    Memory.patchCode(patch1, 4, function(code) {
        code.writeU32(0x14000011);
    });
    console.log('[Patch] 0xd68dac: b.ne -> b (skip showing check)');
    
    // Patch 0xd68dfc: b.ne -> b (跳转到0xd68e5c)
    // 偏移: (0xd68e5c - 0xd68dfc) / 4 = 0x60 / 4 = 0x18
    var patch2 = liveRoomViewAddr.add(0xa0);  // 0xd68d5c + 0xa0 = 0xd68dfc
    Memory.patchCode(patch2, 4, function(code) {
        code.writeU32(0x14000018);
    });
    console.log('[Patch] 0xd68dfc: b.ne -> b (skip connecting check)');
    
    // ==========================================
    // 2. 拦截设置viewType为showing的函数
    // ==========================================
    var setShowingViewType = base.add(0xd70fec);
    Interceptor.replace(setShowingViewType, new NativeCallback(function() {
        console.log('[Hook] setShowingViewType blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 3. 拦截设置viewType为connecting的函数
    // ==========================================
    var setConnectingViewType = base.add(0xd7107c);
    Interceptor.replace(setConnectingViewType, new NativeCallback(function() {
        console.log('[Hook] setConnectingViewType blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 4. 拦截 ShowingPlaceholderView 构造函数
    // ==========================================
    var showingPlaceholderCtorAddr = base.add(0xd74414);
    Interceptor.replace(showingPlaceholderCtorAddr, new NativeCallback(function() {
        console.log('[Hook] ShowingPlaceholderView constructor blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 5. 禁用_waitingView
    // ==========================================
    var addr2 = base.add(0xd7ed68);
    Interceptor.replace(addr2, new NativeCallback(function() {
        console.log('[Hook] _waitingView blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 6. 禁用_connectingView
    // ==========================================
    var addr3 = base.add(0xd7eb80);
    Interceptor.replace(addr3, new NativeCallback(function() {
        console.log('[Hook] _connectingView blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 7. 禁用_startCountdown
    // ==========================================
    var addr4 = base.add(0x1032cc0);
    Interceptor.replace(addr4, new NativeCallback(function() {
        console.log('[Hook] _startCountdown blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 8. 禁用自动跳转下一个直播间
    // ==========================================
    var addr5 = base.add(0xd6dc54);
    Interceptor.replace(addr5, new NativeCallback(function() {
        console.log('[Hook] skipLiveRoom blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 9. 禁用1v1状态获取
    // ==========================================
    var addr6 = base.add(0x101f490);
    Interceptor.replace(addr6, new NativeCallback(function() {
        console.log('[Hook] oneToOneStatusAndItemGet blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    // ==========================================
    // 10. 禁用首次充值大礼包弹框
    // ==========================================
    // showFirstRechargeDialog (0x12d3fa4) 在进入直播间约20秒后触发
    // 直接替换为空函数，阻止弹框显示
    var showFirstRechargeDialog = base.add(0x12d3fa4);
    Interceptor.replace(showFirstRechargeDialog, new NativeCallback(function() {
        console.log('[Hook] showFirstRechargeDialog blocked');
        return ptr(0);
    }, 'pointer', ['pointer']));
    
    console.log("[*] All 1v1 bypass hooks installed successfully");
}

// 等待libapp.so加载
setTimeout(setupHooks, 2000);
