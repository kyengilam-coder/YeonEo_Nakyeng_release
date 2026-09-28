package io.github.kyengilamcoder.nakyeng;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 羅經 · 나경 안드로이드 앱.
 * assets/www 의 웹앱을 WebView 하나에 띄우고, 웹앱이 부르는 window.NakyengAndroid
 * (파일 저장·보내기·복사·화면 유지·외부 링크)를 제공한다. 인터넷 없이 동작한다.
 * 파일 고르기(기록 가져오기)와 카메라(QR 읽기)는 WebView가 요청하면 이어 준다.
 */
public class MainActivity extends Activity {
    private static final String START_URL = "file:///android_asset/www/index.html";
    private static final String FILES_AUTHORITY = "io.github.kyengilamcoder.nakyeng.files";
    private static final int REQ_LOCATION = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_FILE = 3;

    private WebView web;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    private PermissionRequest pendingCamera;
    private ValueCallback<Uri[]> pendingFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#3b1b14"));
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Android 15부터는 화면 끝까지 그리므로, 상태 표시줄·내비게이션 막대만큼 안쪽으로 들인다
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false); // QR 읽기 카메라 화면 자동 재생

        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "NakyengAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if ("file".equals(u.getScheme())) return false;
                openExternalUrl(u.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeo = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                }
            }

            // 웹앱의 getUserMedia(카메라) 요청
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                boolean wantsCamera = false;
                for (String res : request.getResources()) if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) wantsCamera = true;
                if (!wantsCamera) { request.deny(); return; }
                if (has(Manifest.permission.CAMERA)) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                } else {
                    pendingCamera = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                }
            }

            // <input type="file"> — 기록 파일 가져오기, QR 사진 고르기
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFile != null) pendingFile.onReceiveValue(null);
                pendingFile = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, "파일 고르기"), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    pendingFile = null;
                    Toast.makeText(MainActivity.this, "파일을 고를 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    private boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean ok = false;
        for (int g : grantResults) ok |= g == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_LOCATION && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, ok, false);
            pendingGeo = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQ_CAMERA && pendingCamera != null) {
            if (ok) pendingCamera.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            else pendingCamera.deny();
            pendingCamera = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && pendingFile != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) result = new Uri[]{data.getData()};
            pendingFile.onReceiveValue(result);
            pendingFile = null;
        }
    }

    private void openExternalUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    // 뒤로 가기: 작은 창이나 패널이 열려 있으면 닫고, 아니면 앱을 닫는다
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){if(typeof aimOn!=='undefined'&&aimOn&&typeof closeAim==='function'){closeAim();return 1}"
                        + "var m=document.getElementById('modal');if(m&&m.classList.contains('on')){if(typeof stopScan==='function')stopScan();if(typeof closeModal==='function')closeModal();return 1}"
                        + "if(document.body.classList.contains('open')&&typeof closeDrawer==='function'){closeDrawer();return 1}return 0})()",
                v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private static String safeName(String name, String fallback) {
        String n = name == null || name.trim().isEmpty() ? fallback : name;
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String mimeOf(String name) {
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        return "text/plain";
    }

    /** 웹앱이 window.NakyengAndroid 로 부르는 기능. 웹 스레드에서 불리므로 화면 작업은 UI 스레드로 넘긴다. */
    private class Bridge {
        /** 글 파일을 다운로드 폴더(Download/Nakyeng)에 저장하고, 사용자에게 보여 줄 위치를 돌려준다. 실패하면 빈 글. */
        @JavascriptInterface
        public String saveText(String name, String text) {
            String safe = safeName(name, "nakyeng.txt");
            byte[] data = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            try {
                String shown;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentResolver cr = getContentResolver();
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(safe));
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Nakyeng");
                    Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "";
                    try (OutputStream os = cr.openOutputStream(uri)) {
                        if (os == null) return "";
                        os.write(data);
                    }
                    shown = "Download/Nakyeng/" + safe;
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists() && !dir.mkdirs()) return "";
                    File f = new File(dir, safe);
                    try (FileOutputStream os = new FileOutputStream(f)) {
                        os.write(data);
                    }
                    shown = f.getAbsolutePath();
                }
                final String msg = shown;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "저장했습니다: " + msg, Toast.LENGTH_LONG).show());
                return shown;
            } catch (Exception e) {
                return "";
            }
        }

        /** 글을 파일로 만들어 안드로이드 공유 창(카톡·메일·드라이브 등)으로 보낸다. */
        @JavascriptInterface
        public void shareText(String name, String text) {
            String safe = safeName(name, "nakyeng-records.json");
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache dir");
                File f = new File(dir, safe);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this, FILES_AUTHORITY, f);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mimeOf(safe));
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, "나경 기록");
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> {
                    try {
                        startActivity(Intent.createChooser(send, "기록 보내기"));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(MainActivity.this, "보낼 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "보내기 준비에 실패했습니다.", Toast.LENGTH_SHORT).show());
            }
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("나경", text == null ? "" : text));
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(MainActivity.this, "복사했습니다.", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            runOnUiThread(() -> openExternalUrl(url));
        }
    }
}
