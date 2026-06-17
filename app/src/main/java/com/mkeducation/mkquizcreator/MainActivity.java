package com.mkeducation.mkquizcreator;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST   = 100;
    private static final int PERMISSION_REQUEST     = 200;
    private static final int MANAGE_STORAGE_REQUEST = 300;

    // ─────────────────────────────────────────────────────────
    //  onCreate
    // ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        setupWebView();
        requestStoragePermissions();
        loadApp();
    }

    // ─────────────────────────────────────────────────────────
    //  WebView Setup
    // ─────────────────────────────────────────────────────────
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setTextZoom(100);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setBlockNetworkLoads(false);

        // Web Crypto API (AES-GCM) உருவாக background threads-ல் இயங்கும்
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Android Bridge இணைக்கிறோம்
        webView.addJavascriptInterface(new QuizCreatorBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http") || url.startsWith("https")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {

            // WebView audio/microphone permission auto-grant
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            // File chooser — input[type=file] support
            @Override
            public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(
                    Intent.createChooser(intent, "குறியாக்க கோப்பை தேர்வு செய்யுங்கள்"),
                    FILE_CHOOSER_REQUEST
                );
                return true;
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    //  Load HTML Asset
    // ─────────────────────────────────────────────────────────
    private void loadApp() {
        webView.loadUrl("file:///android_asset/MK_Quiz_Creator.html");
    }

    // ─────────────────────────────────────────────────────────
    //  Storage Permission Handling
    // ─────────────────────────────────────────────────────────
    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, PERMISSION_REQUEST);
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Android Bridge — JavaScript ↔ Java
    // ─────────────────────────────────────────────────────────
    public class QuizCreatorBridge {

        // ══════════════════════════════════════════════════════
        // 📥 readFileAsBase64 — HTML input[type=file] மூலம்
        //    தேர்ந்த கோப்பை Base64 ஆக மாற்றி JS-க்கு கொடுக்கிறோம்
        // ══════════════════════════════════════════════════════
        @JavascriptInterface
        public String readFileAsBase64(String uriString) {
            try {
                Uri uri = Uri.parse(uriString);
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return "";
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                is.close();
                return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        // ══════════════════════════════════════════════════════
        // 💾 saveEncryptedFile — JS இல் encrypt செய்த Base64
        //    data-ஐ பெற்று Downloads folder-ல் .mkenc ஆக சேமிக்கிறோம்
        //
        //    JS call:
        //    AndroidBridge.saveEncryptedFile(base64Data, fileName)
        //    Returns: "ok" | "error:<msg>"
        // ══════════════════════════════════════════════════════
        @JavascriptInterface
        public String saveEncryptedFile(String base64Data, String fileName) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.NO_WRAP);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Android 10+ — MediaStore மூலம் Downloads-ல் சேமிக்கிறோம்
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH,
                           Environment.DIRECTORY_DOWNLOADS + "/MKQuizCreator");

                    Uri uri = getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "error:URI null";

                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "error:OutputStream null";
                        os.write(bytes);
                    }
                } else {
                    // Android 9 and below — நேரடி file system
                    File dir = new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "MKQuizCreator");
                    if (!dir.exists()) dir.mkdirs();

                    File outFile = new File(dir, fileName);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(bytes);
                    }
                }

                // Toast — UI thread-ல் காட்டணும்
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                        "✅ " + fileName + " — Downloads/MKQuizCreator-ல் சேமிக்கப்பட்டது!",
                        Toast.LENGTH_LONG).show()
                );
                return "ok";

            } catch (Exception e) {
                return "error:" + e.getMessage();
            }
        }

        // ══════════════════════════════════════════════════════
        // 📤 shareFile — Share intent மூலம் .mkenc கோப்பை
        //    WhatsApp / Email / Drive போன்றவற்றில் share செய்யலாம்
        // ══════════════════════════════════════════════════════
        @JavascriptInterface
        public void shareFile(String base64Data, String fileName) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.NO_WRAP);

                // Cache dir-ல் தற்காலிகமாக சேமித்து share செய்கிறோம்
                File cacheDir = new File(getCacheDir(), "mkenc_share");
                if (!cacheDir.exists()) cacheDir.mkdirs();
                File shareFile = new File(cacheDir, fileName);

                try (FileOutputStream fos = new FileOutputStream(shareFile)) {
                    fos.write(bytes);
                }

                Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                    MainActivity.this,
                    getPackageName() + ".provider",
                    shareFile
                );

                runOnUiThread(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/octet-stream");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
                    shareIntent.putExtra(Intent.EXTRA_SUBJECT, "MK Quiz Creator — " + fileName);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(shareIntent,
                        fileName + " — பகிர்வு / Share"));
                });

            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                        "பகிர்வு தோல்வி: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show()
                );
            }
        }

        // App version
        @JavascriptInterface
        public String getAppVersion() {
            return "MK Quiz Creator Android 1.0";
        }

        // Android device check — HTML download fallback switch
        @JavascriptInterface
        public boolean isAndroid() {
            return true;
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Activity Results
    // ─────────────────────────────────────────────────────────
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
            @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) results = new Uri[]{uri};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                        "கோப்புகளை சேமிக்க Storage அனுமதி தேவை",
                        Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Back button — WebView history
    // ─────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
