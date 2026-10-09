package io.github.kyengilamcoder.nakyeng;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 앱이 스스로 새 버전을 받아 설치한다(자동 업데이트).
 * GitHub 최신 릴리스에서 …-android.apk 를 앱 안으로 내려받아 패키지·버전·서명을 확인한 뒤 PackageInstaller 세션으로 설치한다.
 * Android 12 이상은 앱이 자기 자신을 업데이트할 때 확인 화면 없이 설치할 수 있다(AOSP PackageInstallerSession의 isSelfUpdate).
 * 조건: 이 앱에 "출처를 알 수 없는 앱 설치"가 허용되어 있고 UPDATE_PACKAGES_WITHOUT_USER_ACTION을 선언(자동 부여)했을 것.
 * 그때는 사용자가 앱을 벗어날 때(onStop) 조용히 설치해 다음에 열면 새 버전이다. Android 7~11이거나 허용 전이면
 * "설치"를 누를 때 허용 화면·안드로이드 확인 화면을 거친다(이 경우는 운영체제가 확인을 생략하지 않는다).
 * 상태는 window.__nakyengUpdate({s, v, p, silent, m, manual})로 페이지에 알려 아래 띠에 보인다.
 */
final class AppUpdater {
    interface Page { void send(String js); }

    private static final String RELEASE_API = "https://api.github.com/repos/kyengilam-coder/YeonEo_Nakyeng_release/releases/latest";
    private static final String PREFS = "nakyeng_update";
    private static final long CHECK_MS = 60L * 60L * 1000L;          // 자동 확인은 한 시간에 한 번
    private static final long MAX_APK = 100L * 1024L * 1024L;
    private static final String ACTION_STATUS = "io.github.kyengilamcoder.nakyeng.UPDATE_STATUS";

    private final Activity activity;
    private final Page page;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private String state = "idle";      // idle | checking | latest | available | downloading | ready | permission | installing | error
    private String version = "";        // 새 버전 이름
    private String apkUrl = "";
    private int percent = -1;
    private File apk;                   // 확인을 마친 새 APK
    private boolean resumed, stopped, installAfterDownload, needsConfirm, hold, destroyed;
    private int sessionId = -1;
    private boolean receiverOn;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onInstallStatus(intent);
        }
    };

    AppUpdater(Activity activity, Page page) {
        this.activity = activity;
        this.page = page;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- 생명 주기 (MainActivity가 부름) ----
    void onCreate() {
        // 다른 앱이 이 수신기로 설치 결과를 꾸며 보내지 못하게, 앱 안에서만 받는다(Android 12 이하는 androidx가 서명 권한으로 막음)
        ContextCompat.registerReceiver(activity, statusReceiver, new IntentFilter(ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverOn = true;
        long code = currentCode(), last = prefs.getLong("lastCode", 0);
        if (last > 0 && code > last) {
            Toast.makeText(activity, "새 버전 " + currentName() + "(으)로 업데이트했습니다.", Toast.LENGTH_LONG).show();
        }
        if (code != last) prefs.edit().putLong("lastCode", code).apply();
        // 지난번에 끝나지 않은 설치 세션(확인 창을 닫은 경우 등)은 버린다
        try {
            PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
            for (PackageInstaller.SessionInfo si : pi.getMySessions()) pi.abandonSession(si.getSessionId());
        } catch (Exception ignored) { }
        // 받아 둔 APK가 아직 새 버전이면 그대로 쓰고, 이미 설치되었거나 낡았으면 지운다
        String path = prefs.getString("apkPath", "");
        File f = path.isEmpty() ? null : new File(path);
        if (f != null && f.isFile() && prefs.getLong("apkCode", 0) > code) {
            apk = f; version = prefs.getString("apkVer", ""); state = "ready";
        } else {
            clearDownload();
        }
    }

    void onResume() {
        resumed = true; stopped = false; hold = false;
        // 설치 허용 화면에서 돌아옴. Android 11은 허용을 켜면 앱 프로세스를 다시 띄우므로 표시를 저장소에 둔다
        if (prefs.getBoolean("afterPermission", false) && apk != null) {
            prefs.edit().remove("afterPermission").apply();
            if (canInstallPackages()) install();
            else { state = "ready"; send(true, "설치 허용이 꺼져 있어 설치하지 않았습니다."); }
            return;
        }
        // "받아서 설치"를 눌렀는데 받기가 파일 고르기·공유 중(hold)에 끝나 미뤄 둔 설치
        if (installAfterDownload && "ready".equals(state)) { installAfterDownload = false; install(); return; }
        if (isAuto()) check(false);   // 확인할 때가 되었으면 확인하고, 받는 중·준비됨이면 그 상태를 다시 알림
        else if (!"idle".equals(state) && !"latest".equals(state)) send(false);   // 자동이 꺼져 있어도 진행 중인 상태는 다시 알림
    }

    void onPause() {
        resumed = false;
    }

    void onStop() {
        stopped = true;
        // 사용자가 앱을 벗어났을 때: 확인 화면 없이 설치할 수 있으면 지금 설치한다(다음에 열면 새 버전).
        // 파일 고르기·공유 창처럼 앱이 결과를 기다리는 동안(hold)에는 설치하지 않는다(설치되면 앱이 닫혀 결과를 잃음)
        if (!hold && isAuto() && "ready".equals(state) && apk != null && canSilent() && !activity.isChangingConfigurations()) commit(true);
    }

    /** 앱이 파일 고르기·공유 창 같은 다른 화면을 띄우기 직전에 부른다. 돌아올 때(onResume)까지 조용한 설치를 미룬다. */
    void hold() {
        hold = true;
    }

    void onDestroy() {
        destroyed = true;   // 화면이 닫힌 뒤 늦게 도착한 결과(main.post)는 아무것도 하지 않는다
        if (receiverOn) {
            try { activity.unregisterReceiver(statusReceiver); } catch (IllegalArgumentException ignored) { }
            receiverOn = false;
        }
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ---- 페이지에서 부르는 기능 (UI 스레드에서) ----
    boolean isAuto() {
        return prefs.getBoolean("auto", true);
    }

    void setAuto(boolean on) {
        prefs.edit().putBoolean("auto", on).apply();
        if ("ready".equals(state)) send(false);   // 띠의 "자동으로 설치" 문구(silent)가 설정을 따르게
        else if (on && "available".equals(state)) download();   // 알리기만 하던 새 버전은 켜는 즉시 받기 시작
    }

    /** 새 버전 확인. manual이면 시간 간격과 관계없이 확인하고 결과를 꼭 알린다. 받는 중·준비됨이면 그 상태를 다시 알린다. */
    void check(boolean manual) {
        if ("checking".equals(state) || "downloading".equals(state) || "installing".equals(state) || "ready".equals(state) || "permission".equals(state)) {
            send(manual);
            return;
        }
        long now = System.currentTimeMillis();
        if (!manual && now - prefs.getLong("lastCheck", 0) < CHECK_MS) {
            if ("available".equals(state)) send(false);
            return;
        }
        if (destroyed) return;
        state = "checking"; send(manual);
        String api = apiUrl();
        io.execute(() -> {
            try {
                JSONObject j = new JSONObject(httpText(api));
                String v = j.optString("tag_name", "").replaceFirst("^v", "");
                String url = "";
                JSONArray assets = j.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a != null && a.optString("name").endsWith("-android.apk")) url = a.optString("browser_download_url");
                }
                final String fv = v, fu = url;
                main.post(() -> {
                    if (destroyed) return;
                    prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
                    if (!newer(fv, currentName()) || fu.isEmpty()) { installAfterDownload = false; state = "latest"; version = ""; send(manual); return; }
                    // 검사에서 거절한 버전(서명이 다름 등)은 자동으로 다시 받지 않는다. "새 버전 확인"을 누르면 다시 시도
                    if (!manual && fv.equals(prefs.getString("rejected", ""))) { installAfterDownload = false; state = "idle"; version = ""; return; }
                    version = fv; apkUrl = fu; state = "available";
                    if (isAuto() || installAfterDownload) download();
                    else send(manual);
                });
            } catch (Exception e) {
                // 자동 확인(오프라인 등)의 실패는 조용히: 버전을 비워 페이지가 안내문을 띄우지 않게 한다
                main.post(() -> { if (destroyed) return; installAfterDownload = false; state = "error"; version = ""; send(manual, "새 버전을 확인하지 못했습니다(" + e.getMessage() + ")"); state = "idle"; });
            }
        });
    }

    /** "설치"·"받아서 설치"·"지금 설치". 받아 둔 것이 없으면 받은 뒤 이어서 설치한다. */
    void install() {
        if ("installing".equals(state) || "checking".equals(state)) { send(true); return; }
        if ("downloading".equals(state)) { installAfterDownload = true; send(true); return; }
        if (apk == null || !apk.isFile()) {
            installAfterDownload = true;
            if ("available".equals(state)) download(); else check(true);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !canInstallPackages()) {
            // 앱이 화면에 없으면(받는 동안 홈으로 나감) 허용 화면을 띄울 수 없다(Android 10+ 백그라운드 시작 제한). 돌아와서 "설치"를 누르게 둔다
            if (!resumed) { state = "ready"; send(false); return; }
            // "출처를 알 수 없는 앱 설치"를 이 앱에 허용해야 한다. 돌아오면(onResume) 이어서 설치
            state = "permission"; send(true);
            prefs.edit().putBoolean("afterPermission", true).commit();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException | SecurityException e) {
                prefs.edit().remove("afterPermission").apply(); state = "ready";
                send(true, "설치 허용 화면을 열지 못했습니다. 설정 → 앱 → 나경 → 출처를 알 수 없는 앱 설치를 허용하십시오.");
            }
            return;
        }
        commit(false);
    }

    // ---- 받기·확인 ----
    private void download() {
        if (destroyed) return;
        state = "downloading"; percent = 0; send(false);
        final String url = apkUrl, ver = version;
        io.execute(() -> {
            File dir = new File(activity.getNoBackupFilesDir(), "update");
            deleteTree(dir);
            File part = new File(dir, "Nakyeng-" + ver + ".apk.part"), done = new File(dir, "Nakyeng-" + ver + ".apk");
            String fail = null;
            try {
                if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("저장 공간");
                HttpURLConnection c = open(url);
                try {
                    int code = c.getResponseCode();
                    if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                    long total = c.getContentLengthLong();
                    if (total > MAX_APK) throw new IOException("파일이 너무 큼");
                    try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        long got = 0, lastSent = 0;
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            got += n;
                            if (got > MAX_APK) throw new IOException("파일이 너무 큼");
                            long t = SystemClock.uptimeMillis();
                            if (total > 0 && t - lastSent > 300) {
                                lastSent = t;
                                final int p = (int) (got * 100 / total);
                                main.post(() -> { if ("downloading".equals(state)) { percent = p; send(false); } });
                            }
                        }
                    }
                } finally {
                    c.disconnect();
                }
                if (!part.renameTo(done)) throw new IOException("파일 이름");
                fail = verify(done);
            } catch (Exception e) {
                fail = "새 버전을 받지 못했습니다(" + e.getMessage() + ")";
            }
            final String err = fail;
            final boolean rejected = fail != null && done.isFile();   // 받기는 끝났는데 검사에서 거절
            main.post(() -> {
                if (destroyed) return;
                if (err != null) {
                    if (rejected) prefs.edit().putString("rejected", ver).apply();
                    deleteTree(dir);
                    installAfterDownload = false;
                    state = "error"; send(true, err); state = rejected ? "idle" : "available";
                    return;
                }
                apk = done; state = "ready"; percent = -1;
                prefs.edit().remove("rejected").apply();
                prefs.edit().putString("apkPath", done.getAbsolutePath()).putString("apkVer", ver).putLong("apkCode", archiveCode(done)).apply();
                // 파일 고르기·공유 중(hold)이면 설치를 미룬다: "받아서 설치"는 돌아올 때(onResume), 자동 설치는 다음에 앱을 벗어날 때(onStop)
                if (installAfterDownload && !hold) { installAfterDownload = false; send(false); install(); return; }
                send(false);
                if (stopped && !hold && isAuto() && canSilent()) commit(true);   // 받는 동안 앱을 벗어났으면 바로 조용히 설치
            });
        });
    }

    /** 받은 APK가 이 앱의 더 높은 버전이고 같은 서명인지. 문제가 있으면 안내 글, 괜찮으면 null. */
    @SuppressWarnings("deprecation")
    private String verify(File f) {
        PackageManager pm = activity.getPackageManager();
        // Android 9·10은 APK 파일의 서명을 GET_SIGNATURES가 있어야 읽으므로 둘 다 준다(signingInfo도 함께 채워짐)
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES : PackageManager.GET_SIGNATURES;
        PackageInfo a = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
        if (a == null || !activity.getPackageName().equals(a.packageName)) return "나경 APK가 아니어서 설치하지 않았습니다.";
        if (code(a) <= currentCode()) return "지금보다 새 버전이 아니어서 설치하지 않았습니다.";
        try {
            Set<String> mine = signers(pm.getPackageInfo(activity.getPackageName(), flags)), theirs = signers(a);
            // 둘 다 읽혔는데 다르면 거절한다. 읽지 못하면 설치 단계에서 안드로이드가 서명을 다시 확인한다
            if (!mine.isEmpty() && !theirs.isEmpty() && !mine.equals(theirs)) return "서명이 지금 앱과 달라 설치하지 않았습니다. 이전 앱을 지우고 내려받기 페이지의 APK로 다시 설치하십시오.";
        } catch (PackageManager.NameNotFoundException e) {
            return "지금 앱 정보를 읽지 못했습니다.";
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28) {
            if (p.signingInfo != null) sigs = p.signingInfo.hasMultipleSigners() ? p.signingInfo.getApkContentsSigners() : p.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = p.signatures;
        }
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) {
                StringBuilder h = new StringBuilder();
                for (byte b : md.digest(s.toByteArray())) h.append(String.format("%02x", b));
                out.add(h.toString());
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ---- 설치 ----
    /** 확인 화면 없이 설치할 수 있는지: Android 12+, 확인 없는 업데이트 권한(선언하면 자동 부여), 이 앱에 설치 허용. 안드로이드가 그래도 확인을 요구하면 needsConfirm. */
    private boolean canSilent() {
        if (Build.VERSION.SDK_INT < 31 || needsConfirm) return false;
        if (ContextCompat.checkSelfPermission(activity, "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION") != PackageManager.PERMISSION_GRANTED) return false;
        return canInstallPackages();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void commit(boolean silent) {
        if (destroyed) return;
        final File f = apk;
        if (f == null || !f.isFile()) return;
        state = "installing"; send(!silent);
        io.execute(() -> {
            String err = null;
            int id = -1;
            try {
                PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(activity.getPackageName());
                sp.setSize(f.length());
                if (Build.VERSION.SDK_INT >= 26) sp.setInstallReason(PackageManager.INSTALL_REASON_USER);
                if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);   // 조건이 맞을 때만 안드로이드가 받아 줌
                id = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent i = new Intent(ACTION_STATUS).setPackage(activity.getPackageName());
                    int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);   // 안드로이드가 결과를 채워 넣어야 하므로 mutable
                    s.commit(PendingIntent.getBroadcast(activity, id, i, pf).getIntentSender());
                }
            } catch (Exception e) {
                err = "설치를 시작하지 못했습니다(" + e.getMessage() + ")";
                if (id >= 0) try { activity.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { }
            }
            final String fe = err;
            final int fid = id;
            main.post(() -> {
                if (destroyed) return;
                if (fe != null) { state = "error"; send(true, fe); state = "ready"; return; }
                sessionId = fid;
            });
        });
    }

    private void onInstallStatus(Intent intent) {
        int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (id != sessionId) return;
        int st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent.class);
            if (resumed && confirm != null) {
                try { activity.startActivity(confirm); return; } catch (ActivityNotFoundException | SecurityException ignored) { }
            }
            // 앱 밖에서 조용히 설치하려 했는데 안드로이드가 확인을 요구함: 이번 세션은 버리고, 다음에 앱을 열면 "설치"로 확인을 받는다
            try { activity.getPackageManager().getPackageInstaller().abandonSession(id); } catch (Exception ignored) { }
            needsConfirm = true; sessionId = -1; state = "ready";
            send(false);   // 띠가 "설치 중…"에 머물지 않게, 앱 밖에 있어도 알린다(돌아오면 보임)
            return;
        }
        sessionId = -1;
        if (st == PackageInstaller.STATUS_SUCCESS) { clearDownload(); state = "idle"; return; }   // 보통은 설치되면서 앱이 닫혀 여기까지 오지 않음
        if (st == PackageInstaller.STATUS_FAILURE_ABORTED) { needsConfirm = true; state = "ready"; send(true); return; }   // 확인 화면에서 취소
        String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (st == PackageInstaller.STATUS_FAILURE_CONFLICT || st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            if (!version.isEmpty()) prefs.edit().putString("rejected", version).apply();   // 자동 확인이 같은 파일을 다시 받지 않게
            clearDownload(); state = "error";
            send(true, "이 기기의 앱과 맞지 않아 설치하지 못했습니다. 이전 앱을 지우고 내려받기 페이지의 APK로 다시 설치하십시오.");
            state = "idle";
            return;
        }
        state = "error"; send(true, "설치하지 못했습니다" + (m == null ? "." : "(" + m + ")")); state = "ready";
    }

    // ---- 도움 ----
    private void send(boolean manual) {
        send(manual, null);
    }

    private void send(boolean manual, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("s", state);
            if (!version.isEmpty()) o.put("v", version);
            if ("downloading".equals(state)) o.put("p", percent);
            if ("ready".equals(state)) o.put("silent", isAuto() && canSilent());
            if (message != null) o.put("m", message);
            if (manual) o.put("manual", true);
            page.send("window.__nakyengUpdate&&window.__nakyengUpdate(" + o + ")");
        } catch (Exception ignored) { }
    }

    private void clearDownload() {
        apk = null;
        prefs.edit().remove("apkPath").remove("apkVer").remove("apkCode").apply();
        if (!io.isShutdown()) io.execute(() -> deleteTree(new File(activity.getNoBackupFilesDir(), "update")));
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** 시험용: 디버그 빌드에서만 확인 주소를 바꿀 수 있다(adb shell am start … --es updApi http://10.0.2.2:8000/latest.json). */
    void setTestApi(String url) {
        if ((activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        prefs.edit().putString("testApi", url).putLong("lastCheck", 0).apply();
    }

    private String apiUrl() {
        String t = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0 ? prefs.getString("testApi", "") : "";
        return t.isEmpty() ? RELEASE_API : t;
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);   // github.com → objects.githubusercontent.com (둘 다 https라 따라감)
        c.setRequestProperty("User-Agent", "Nakyeng-Android/" + currentName());
        return c;
    }

    private String httpText(String url) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    b.write(buf, 0, n);
                    if (b.size() > 2 * 1024 * 1024) throw new IOException("응답이 너무 큼");
                }
                return new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static boolean newer(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    private static int[] parts(String v) {
        int[] r = new int[3];
        String[] s = (v == null ? "" : v.replaceFirst("^v", "")).split("[.-]");
        for (int i = 0; i < 3 && i < s.length; i++) {
            try { r[i] = Integer.parseInt(s[i].trim()); } catch (NumberFormatException ignored) { }
        }
        return r;
    }

    private String currentName() {
        try {
            String n = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            return n == null ? "0.0.0" : n;
        } catch (PackageManager.NameNotFoundException e) {
            return "0.0.0";
        }
    }

    private long currentCode() {
        try {
            return code(activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private long archiveCode(File f) {
        PackageInfo a = activity.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        return a == null ? 0 : code(a);
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }
}
