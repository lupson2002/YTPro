package com.google.android.youtube.pro;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.widget.Toast;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;

// Import the separated components
import com.google.android.youtube.pro.webview.YTProWebView;
import com.google.android.youtube.pro.webview.YTProWebViewClient;
import com.google.android.youtube.pro.webview.YTProWebChromeClient;
import com.google.android.youtube.pro.webview.WebAppInterface;
import com.google.android.youtube.pro.webview.BinaryStreamManager;

import com.google.android.youtube.pro.receivers.MediaCommandReceiver;

public class MainActivity extends Activity {

    public boolean portrait = false;
    public boolean isPlaying = false;
    public boolean mediaSession = false;
    public boolean isPip = false;
    public boolean dL = false;
    // 🔻 PiP 해고 직후 한 번의 백은 다시 PiP 진입하지 않고 실제 뒤로/종료(루프 방지)
    private boolean suppressPipOnNextBack = false;
    // 🔻 API33+ 자동 PiP(setAutoEnterEnabled) armed 여부 — 홈 버튼 시스템 자동 전환 부드러움
    private volatile boolean pipAutoArmed = false;

    private YTProWebView web;
    private MediaCommandReceiver broadcastReceiver;
    private OnBackInvokedCallback backCallback;
    public BinaryStreamManager streamManager;

    // 🔒 오디오 포커스 — 이어폰/알림/타 앱이 포커스를 빼앗을 때 재생이 멈추고 자동 복구 안 되는 현상 방지.
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API26+
    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS:
                    // 일시/영구 손실: 재생 중이던 기록 후 일시정지(조작 없이 멈춘 상태 방지)
                    web.evaluateJavascript(
                        "try{var v=document.getElementsByClassName('video-stream')[0];" +
                        "if(v && !v.paused){window.__ytproWasPlaying=true; v.pause();}}catch(e){}", null);
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // ducking — 시스템이 자동으로 볼륨 낮춤(요청을 GAIN 으로 했으므로 여기선 별도 처리 불필요)
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    // 포커스 회수: 직전에 재생 중이었으면 자동 재개(사용자가 다시 조작하지 않아도 복구)
                    web.evaluateJavascript(
                        "try{if(window.__ytproWasPlaying){var v=document.getElementsByClassName('video-stream')[0];" +
                        "if(v){v.muted=false; var p=v.play(); if(p&&p.catch)p.catch(function(){});" +
                        "window.__ytproWasPlaying=false;}}}catch(e){}", null);
                    break;
            }
        }
    };
    
    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        SharedPreferences prefs = getSharedPreferences("YTPRO", MODE_PRIVATE);
        if (!prefs.contains("bgplay")) {
            prefs.edit().putBoolean("bgplay", true).apply();
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        load(false);
    }

    public void load(boolean dl) {
              
        
        this.dL = dl;
        web = findViewById(R.id.web);
        
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // ⚡ 렌더링/스크롤 반응 최적화
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);   // 하드웨어 가속 강제
        web.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH); // deprecated but best-effort
        web.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);             // 신선한 캐시 우선, 부족 시 네트워크
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            web.getSettings().setOffscreenPreRaster(true); // 오프스크린 프레임 사전 래스터화 → 스크롤/전환 jank 감소
        }
        web.getSettings().setBlockNetworkImage(false);     // 이미지 로딩 차단 없음(기본값 명시)

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(web, true);
        }

        Intent intent = getIntent();
        String action = intent.getAction();
        Uri data = intent.getData();
        String url = "https://m.youtube.com/";
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            url = data.toString();
        } else if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && (sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                url = sharedText;
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
          web.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }


        web.addJavascriptInterface(new WebAppInterface(this, web), "Android");
        web.setWebChromeClient(new YTProWebChromeClient(this, web));
        web.setWebViewClient(new YTProWebViewClient(this, web));
        
        web.loadUrl(url);

        setupReceiver();
        setupBackNavigation();
        streamManager = new BinaryStreamManager(web,this);

        // 🔒 미디어 재생용 오디오 포커스 확보 — 이어폰/알림/타 앱 전환 시 자동 일시정지·재개 처리의 전제.
        requestAudioFocus();
    }

    /** 🔒 오디오 포커스 요청(USAGE_MEDIA). API26+ 은 AudioFocusRequest, 미만은 구형 API. */
    private void requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        }
        if (audioManager == null) return;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            try { web.onResume(); } catch (Exception e) {}
        }
        // 화면 복귀 시 포커스 재확보(백그라운드 중 해제됐을 수 있음)
        requestAudioFocus();
    }
         

   

    private void setupReceiver() {
        broadcastReceiver = new MediaCommandReceiver(web);
        if (Build.VERSION.SDK_INT >= 34 && getApplicationInfo().targetSdkVersion >= 34) {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver, new IntentFilter("TRACKS_TRACKS"));
        }
    }

    private void setupBackNavigation() {
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBackPress();
                }
            };
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    /** 🔻 유튜브 프리미엄 동작: 재생 중인 watch/shorts 화면에서 백(뒤로) 버튼 시 앱 종료가 아니라
     *  PiP(작은 화면) 모드로 전환해 백그라운드 재생 유지. PiP 진입 성공 시 true. */
    private boolean enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < 26) return false;
        if (isPip) return false;
        if (suppressPipOnNextBack) { suppressPipOnNextBack = false; return false; }
        String u = web != null ? web.getUrl() : null;
        if (u == null || !(u.contains("watch") || u.contains("shorts"))) return false;
        if (!isPlaying) return false;
        try {
            isPip = true;
            PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9));
            enterPictureInPictureMode(b.build());
            return true;
        } catch (IllegalStateException e) {
            isPip = false;
            return false;
        }
    }

    /** 🔻 API33+ 자동 PiP armed — 재생 중 홈 버튼 시 시스템이 부드럽게 자동 PiP 전환(예측형 백 애니메이션 호환).
     *  재생 시작/재개 시 arm(true), 일시정지/정지 시 arm(false). JS 스레드에서 호출되므로 UI 스레드에서 실행. */
    @SuppressLint("NewApi")
    public void armPipAutoEnter(boolean enable) {
        if (Build.VERSION.SDK_INT < 33) return;
        pipAutoArmed = enable;
        runOnUiThread(() -> {
            try {
                PictureInPictureParams p = new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(portrait ? 9 : 16, portrait ? 16 : 9))
                        .setAutoEnterEnabled(enable)
                        .build();
                setPictureInPictureParams(p);
            } catch (Exception e) { /* non-watch 상태 등 무시 */ }
        });
    }

    private void handleBackPress() {
        // 🔻 재생 중 영상 화면에서 백 → PiP(작은 화면) 전환, 백그라운드 재생 유지(프리미엄 동작)
        if (enterPipIfPossible()) return;
        if (web.canGoBack()) {
            web.goBack();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackPress();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                web.loadUrl("https://m.youtube.com");
            } else {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_mic), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), getString(R.string.grant_storage), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        web.evaluateJavascript(isInPictureInPictureMode ? "PIPlayer();" : "removePIP();", null);
        isPip = isInPictureInPictureMode;
        if (!isInPictureInPictureMode) {
            // 🔻 PiP에서 빠져나온 직후 한 번의 백은 뒤로/종료(다시 PiP로 빠지는 루프 방지)
            suppressPipOnNextBack = true;
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // API33+ 에선 setAutoEnterEnabled 로 시스템이 자동 PiP 전환(더 부드러움) → 수동 진입 스킵(이중 방지).
        // 미지원 기기/미무장 시에만 수동 전환.
        if (pipAutoArmed) return;
        enterPipIfPossible();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 🔒 백그라운드 오디오 유지: web.onPause() 를 호출하지 않음 — 호출 시 WebView 가 미디어/JS 타이머를
        // 일시정지시켜 화면 꺼짐 시 재생이 멈춤. YTProWebView.onWindowVisibilityChanged 가 bgPlay 시
        // super 호출을 건너뛰어 가시성 변경에 의한 pause 도 차단.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 🔒 화면 잠금/백그라운드 진입 시에도 WebView 미디어·JS 타이머가 멈추지 않도록
        // web.onPause()/web.onStop() 호출 금지. CPU 유지는 ForegroundService + WakeLock 이 담당.
        CookieManager.getInstance().flush();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        abandonAudioFocus();
        stopService(new Intent(getApplicationContext(), ForegroundService.class));
        if (broadcastReceiver != null) unregisterReceiver(broadcastReceiver);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        if (streamManager != null) {
            streamManager.cleanup();
        }
    }
}
