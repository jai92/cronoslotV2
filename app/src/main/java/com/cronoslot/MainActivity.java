package com.cronoslot;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private boolean appLoaded = false;

    private static final int CAMERA_PERMISSION_CODE = 100;   // permiso al arrancar
    private static final int CAMERA_PHOTO_PERMISSION_CODE = 102; // permiso al ir a hacer una foto
    private static final int STORAGE_PERMISSION_CODE = 101;  // solo Android 9 o anterior
    private static final int FILE_CHOOSER_CODE = 200;
    private static final String AUTHORITY = "com.cronoslot.fileprovider";

    // Selector de fotos (cámara / galería)
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraPhotoUri;

    // Archivo pendiente de guardar mientras se pide permiso (Android 9 o anterior)
    private String pendingName, pendingMime;
    private byte[] pendingData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setAllowFileAccess(true);

        // Puente para que la página pueda guardar/compartir el Excel y mantener la pantalla encendida
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            // Botones "Hacer foto" y "Elegir de la galería" de la página
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                cameraPhotoUri = null;
                try {
                    Intent intent;
                    if (params.isCaptureEnabled()) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA}, CAMERA_PHOTO_PERMISSION_CODE);
                            cancelChooser();
                            Toast.makeText(MainActivity.this, "Concede el permiso de cámara y vuelve a pulsar \"Hacer foto\"", Toast.LENGTH_LONG).show();
                            return true;
                        }
                        File dir = new File(getCacheDir(), "photos");
                        if (!dir.exists()) dir.mkdirs();
                        File photo = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
                        cameraPhotoUri = FileProvider.getUriForFile(MainActivity.this, AUTHORITY, photo);
                        intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri);
                        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } else {
                        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType("image/*");
                        intent = Intent.createChooser(pick, "Elegir foto");
                    }
                    startActivityForResult(intent, FILE_CHOOSER_CODE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    cancelChooser();
                    Toast.makeText(MainActivity.this, "No hay ninguna app de cámara o galería disponible", Toast.LENGTH_LONG).show();
                    return true;
                } catch (Exception e) {
                    cancelChooser();
                    Toast.makeText(MainActivity.this, "Error al abrir la cámara/galería: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            loadApp();
        }
    }

    private void loadApp() {
        if (appLoaded) return;
        appLoaded = true;
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void cancelChooser() {
        if (filePathCallback != null) filePathCallback.onReceiveValue(null);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_CODE) return;
        if (filePathCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (cameraPhotoUri != null) {
                result = new Uri[]{cameraPhotoUri};
            } else if (data != null) {
                if (data.getClipData() != null && data.getClipData().getItemCount() > 0) {
                    result = new Uri[]{data.getClipData().getItemAt(0).getUri()};
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraPhotoUri = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == CAMERA_PERMISSION_CODE) {
            loadApp();
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (granted && pendingData != null) {
                try {
                    saveToDownloads(pendingName, pendingMime, pendingData);
                    Toast.makeText(this, "Guardado en Descargas: " + pendingName, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            } else if (!granted) {
                Toast.makeText(this, "Sin permiso no se puede guardar en Descargas. Usa \"Compartir\".", Toast.LENGTH_LONG).show();
            }
            pendingData = null;
        }
    }

    // Guarda un archivo en la carpeta Descargas del móvil
    private void saveToDownloads(String name, String mime, byte[] bytes) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("no se pudo crear el archivo");
            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os == null) throw new Exception("no se pudo escribir el archivo");
            try { os.write(bytes); } finally { os.close(); }
        } else {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(file);
            try { fos.write(bytes); } finally { fos.close(); }
            MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    // Métodos que la página puede llamar como window.AndroidBridge.xxx()
    public class AndroidBridge {
        @JavascriptInterface
        public String saveFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingName = name; pendingMime = mime; pendingData = bytes;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
                        }
                    });
                    return "PERMISO";
                }
                saveToDownloads(name, mime, bytes);
                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public String shareFile(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "exports");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(file);
                try { fos.write(bytes); } finally { fos.close(); }
                final Uri uri = FileProvider.getUriForFile(MainActivity.this, AUTHORITY, file);
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType(mime);
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, name);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        startActivity(Intent.createChooser(send, "Compartir Excel"));
                    }
                });
                return "OK";
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void keepScreenOn(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }
    }
}
