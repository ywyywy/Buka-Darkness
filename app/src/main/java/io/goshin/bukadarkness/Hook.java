package io.goshin.bukadarkness;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.widget.Toast;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ByteArrayEntity;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.goshin.bukadarkness.adapter.Index;
import io.goshin.bukadarkness.adapter.Items;
import io.goshin.bukadarkness.adapter.MangaAdapterFactory;
import io.goshin.bukadarkness.adapter.Utils;
import io.goshin.bukadarkness.database.CoverMapDatabase;
import io.goshin.bukadarkness.database.DatabaseBase;
import io.goshin.bukadarkness.database.ImageReferrerMapDatabase;

@SuppressWarnings("deprecation")
public class Hook implements IXposedHookLoadPackage {
    public static Bundle config = new Bundle();
    public static SharedPreferences bukaPref;

    public static boolean verbose = false;
    private Activity activity;
    private Handler mainThreadHandler;

    public static void log(String text) {
        if (verbose) {
            XposedBridge.log(text);
        }
    }

    public static void log(Throwable throwable) {
        if (verbose) {
            XposedBridge.log(throwable);
        }
    }


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.packageName.toLowerCase().contains("cn.ibuka.m")) {
            return;
        }

        loadPreference();
        XposedHelpers.findAndHookMethod("org.apache.http.impl.client.AbstractHttpClient", loadPackageParam.classLoader, "execute", HttpUriRequest.class, new XC_MethodHook() {
            private JSONObject getParam(HttpPost request) throws Throwable {
                try {
                    String i = URLDecoder.decode(URLEncodedUtils.parse(request.getEntity()).get(0).getValue());
                    log("HttpPost i " + i);
                    return new JSONObject(i);
                } catch (Exception ignored) {
                }
                return new JSONObject();
            }

            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!param.args[0].getClass().getCanonicalName().contains("HttpPost")) {
                    return;
                }
                HttpPost request = (HttpPost) param.args[0];

                String url = request.getURI().toString();
                JSONObject params = getParam(request);
                MangaAdapter adapter = MangaAdapterFactory.getAdapter(params.optString("f"));
                if (url.toLowerCase().contains("bug") || (adapter != null && adapter.needRedirect(params))) {
                    request.setURI(new URI("http://www.baidu.com/"));
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!param.args[0].getClass().getCanonicalName().contains("HttpPost")) {
                    return;
                }
                HttpPost request = (HttpPost) param.args[0];
                HttpResponse response = (HttpResponse) param.getResult();

                String url = request.getURI().toString();
                log("HttpPostURL " + url);

                JSONObject params = getParam(request);
                MangaAdapter adapter = MangaAdapterFactory.getAdapter(params.optString("f"));
                if (adapter == null) {
                    return;
                }

                JSONObject originalResult = null;
                if (!adapter.needRedirect(params)) {
                    GZIPInputStream gzipInputStream = new GZIPInputStream(response.getEntity().getContent());
                    byte[] responseReadBuffer = new byte[1024];
                    ByteArrayOutputStream responseBytesOutputStream = new ByteArrayOutputStream();
                    int n;
                    while ((n = gzipInputStream.read(responseReadBuffer)) > 0) {
                        responseBytesOutputStream.write(responseReadBuffer, 0, n);
                    }
                    String responseString = responseBytesOutputStream.toString();
                    log("Decode Response Stream " + responseString);

                    originalResult = new JSONObject(responseString);
                }

                String result = "{ret:-1}";
                try {
                    result = adapter.getResult(params, originalResult);
                } catch (ConnectException e) {
                    toast("连接插件后台服务失败，检查插件是否被阻止唤醒或阻止后台驻留。请完全退出布卡后再试");
                    e.printStackTrace();
                } catch (Throwable throwable) {
                    toast(throwable.getMessage());
                    log(throwable);
                }

                log("set result" + result);
                response.setEntity(new ByteArrayEntity(getGzipByteArray(result)));
            }

            @NonNull
            private byte[] getGzipByteArray(String result) throws IOException {
                ByteArrayOutputStream compressOutputStream = new ByteArrayOutputStream();
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(compressOutputStream);
                gzipOutputStream.write(result.getBytes());
                gzipOutputStream.close();
                return compressOutputStream.toByteArray();
            }
        });

        XposedHelpers.findAndHookConstructor("java.net.URL", loadPackageParam.classLoader, String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String url = (String) param.args[0];
                log("URL construct " + url);
                if (url.contains(Index.BASE_PREFIX)) {
                    param.args[0] = Index.getRealPicUrl(url);
                    log("Change URL to " + param.args[0]);
                } else if (url.contains(Items.COVER_PREFIX)) {
                    param.args[0] = CoverMapDatabase.getInstance().get(url.substring(url.lastIndexOf("-") + 1, url.lastIndexOf("/")));
                    log("Change URL to " + param.args[0]);
                }
            }
        });

        XposedBridge.hookAllConstructors(IOException.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                log((Throwable) param.thisObject);
            }
        });

        XC_MethodHook URLConnectHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                URLConnection urlConnection = (URLConnection) param.thisObject;
                String referrer = ImageReferrerMapDatabase.getInstance().get(urlConnection.getURL().toString());
                if (referrer != null) {
                    urlConnection.setRequestProperty("Referer", referrer);
                    log("referrer: " + referrer);
                }
            }
        };

        XC_MethodHook URLGetInputStreamHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                URL url = ((URLConnection) param.thisObject).getURL();
                log("URLConnection getInputStream " + url);
                if (!url.toString().matches(".*?/req\\d+\\.php.*")) {
                    return;
                }

                HashMap<String, String> params = new HashMap<>();
                for (NameValuePair p : URLEncodedUtils.parse(url.toURI(), "UTF-8")) {
                    params.put(p.getName(), p.getValue());
                }
                Index index = new Index(params.get("mid"), params.get("cid"));
                if (!index.match()) {
                    return;
                }
                param.setResult(Utils.indexEncode(index.getClip(), index.getBase(), Integer.parseInt(params.get("mid")), Integer.parseInt(params.get("cid"))));
            }
        };

        final int apiLevel = Build.VERSION.SDK_INT;
        if (apiLevel >= 23) {
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("com.android.okhttp.internal.huc.HttpURLConnectionImpl", loadPackageParam.classLoader, "getInputStream", URLGetInputStreamHook);
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("com.android.okhttp.internal.huc.HttpURLConnectionImpl", loadPackageParam.classLoader, "connect", URLConnectHook);
        } else if (apiLevel >= 19) {
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("com.android.okhttp.internal.http.HttpURLConnectionImpl", loadPackageParam.classLoader, "getInputStream", URLGetInputStreamHook);
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("com.android.okhttp.internal.http.HttpURLConnectionImpl", loadPackageParam.classLoader, "connect", URLConnectHook);
        } else {
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("libcore.net.http.HttpURLConnectionImpl", loadPackageParam.classLoader, "getInputStream", URLGetInputStreamHook);
            //noinspection SpellCheckingInspection
            XposedHelpers.findAndHookMethod("libcore.net.http.HttpURLConnectionImpl", loadPackageParam.classLoader, "connect", URLConnectHook);
        }


        final Intent serviceIntent = new Intent("BukaDarknessServer");
        //noinspection SpellCheckingInspection
        serviceIntent.setComponent(new ComponentName("io.goshin.bukadarkness", "io.goshin.bukadarkness.sited.Server"));
        XC_MethodHook startServiceHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                mainThreadHandler = new Handler();
                activity = (Activity) param.thisObject;
                activity.startService(serviceIntent);
                DatabaseBase.init(activity);
                bukaPref = activity.getSharedPreferences(activity.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            }
        };
        XC_MethodHook stopServiceHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                ((Activity) param.thisObject).stopService(serviceIntent);
            }
        };
        XC_MethodHook onMoveToBackHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                ((Activity) param.thisObject).finish();
                param.setResult(true);
            }
        };


        String packageName = loadPackageParam.packageName;
        String mainActivityClassName = packageName + ".ActivityMain";
        if (packageName.toLowerCase().contains("hd")) {
            mainActivityClassName = packageName + ".hd.HDActivityMain";
        }
        Class mainActivityClass;
        try {
            mainActivityClass = XposedHelpers.findClass(mainActivityClassName, loadPackageParam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError ignored) {
            mainActivityClass = XposedHelpers.findClass("cn.ibuka.manga.md.activity.ActivityMain", loadPackageParam.classLoader);
        }
        XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate", Bundle.class, startServiceHook);
        XposedHelpers.findAndHookMethod(mainActivityClass, "onDestroy", stopServiceHook);
        XposedHelpers.findAndHookMethod(Activity.class, "moveTaskToBack", "boolean", onMoveToBackHook);

        ReaderHook.initHook(packageName, loadPackageParam);


        XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", loadPackageParam.classLoader, "getInstalledApplications", int.class, new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                List<ApplicationInfo> applicationList = (List) param.getResult();
                List<ApplicationInfo> resultApplicationList = new ArrayList<>();
                for (ApplicationInfo applicationInfo : applicationList) {
                    if (!applicationInfo.processName.toLowerCase().contains("darkness")) {
                        resultApplicationList.add(applicationInfo);
                    }
                }
                param.setResult(resultApplicationList);
            }
        });

        XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", loadPackageParam.classLoader, "getInstalledPackages", int.class, new XC_MethodHook() {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                List<PackageInfo> packageInfoList = (List) param.getResult();
                List<PackageInfo> resultPackageInfoList = new ArrayList<>();
                for (PackageInfo packageInfo : packageInfoList) {
                    if (!packageInfo.packageName.toLowerCase().contains("darkness")) {
                        resultPackageInfoList.add(packageInfo);
                    }
                }
                param.setResult(resultPackageInfoList);
            }
        });
    }

    private void loadPreference() {
        XSharedPreferences pref = new XSharedPreferences(Hook.class.getPackage().getName(), "pref");
        pref.makeWorldReadable();
        pref.reload();

        verbose = pref.getBoolean("verbose", false);
        config.putBoolean("verbose", verbose);
        config.putBoolean("popupBars", pref.getBoolean("popup_bars", true));
        config.putBoolean("sortSearchResult", pref.getBoolean("sort_search_result", true));
        config.putBoolean("classifyChapter", pref.getBoolean("classify_chapter", true));
        config.putBoolean("splitLinkedPage", pref.getBoolean("split_linked_page", true));
    }

    private void toast(final String text) {
        if (mainThreadHandler == null) {
            return;
        }
        mainThreadHandler.post(new Runnable() {
            @Override
            public void run() {
                if (activity != null) {
                    Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
                }
            }
        });
    }
}
