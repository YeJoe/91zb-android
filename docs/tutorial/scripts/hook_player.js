Java.perform(function() {
    console.log("[*] Hooking Player SDK - URL Analysis...");
    
    var IjkMediaPlayer = Java.use("com.tomato.ijk.media.player.IjkMediaPlayer");
    
    IjkMediaPlayer.setDataSource.overloads.forEach(function(overload, index) {
        overload.implementation = function(url) {
            console.log("[*] setDataSource[" + index + "]: " + url);
            
            if (url && url.indexOf("http") !== -1) {
                console.log("[*] URL: " + url);
                
                var headers = IjkMediaPlayer._getHeaders ? IjkMediaPlayer._getHeaders.call(this) : null;
                if (headers) {
                    console.log("[*] Headers: " + headers);
                }
            }
            
            return overload.apply(this, arguments);
        };
    });
    
    try {
        var OkHttpClient = Java.use("okhttp3.OkHttpClient");
        OkHttpClient.newCall.overloads.forEach(function(overload) {
            overload.implementation = function(request) {
                var url = request.url().toString();
                if (url.indexOf("flv") !== -1 || url.indexOf("m3u8") !== -1 || url.indexOf("play") !== -1) {
                    console.log("[*] OkHttp Request: " + url);
                    console.log("[*] Headers: " + request.headers());
                }
                return overload.apply(this, arguments);
            };
        });
    } catch(e) {}
    
    // Hook Flutter libapp.so 禁用启动页广告
    var libapp = Module.findBaseAddress('libapp.so');
    if (libapp) {
        console.log("[*] libapp.so found at: " + libapp);
        
        // Hook getStartPageAd 返回null，不显示启动页广告
        var getStartPageAdAddr = libapp.add(0xa0b34c);
        Interceptor.replace(getStartPageAdAddr, new NativeCallback(function() {
            console.log('[Hook] getStartPageAd blocked - returning null');
            return ptr(0); // 返回null，跳过广告直接进入主页
        }, 'pointer', ['pointer']));
        
        // Hook _startAdTimer 防止倒计时启动
        var startAdTimerAddr = libapp.add(0xa0a3f0);
        Interceptor.replace(startAdTimerAddr, new NativeCallback(function() {
            console.log('[Hook] _startAdTimer blocked');
            return ptr(0);
        }, 'pointer', ['pointer']));
        
        // Hook AdvDialog::show 禁用广告弹框
        var advDialogShowAddr = libapp.add(0x905074);
        Interceptor.replace(advDialogShowAddr, new NativeCallback(function() {
            console.log('[Hook] AdvDialog::show blocked');
            return ptr(0);
        }, 'pointer', ['pointer']));
        
        // Hook checkAppNeedUpdate 禁用更新弹框
        var checkAppNeedUpdateAddr = libapp.add(0x8c680c);
        Interceptor.replace(checkAppNeedUpdateAddr, new NativeCallback(function() {
            console.log('[Hook] checkAppNeedUpdate blocked');
            return ptr(0);
        }, 'pointer', ['pointer']));
    } else {
        console.log("[!] libapp.so not found");
    }
});
