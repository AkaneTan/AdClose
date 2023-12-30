package com.close.hook.ads.hook.gc.network;

import java.io.*;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.core.BackpressureStrategy;

import java.net.*;

import android.util.Log;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.content.pm.PackageManager;

import com.close.hook.ads.data.model.BlockedRequest;

import de.robv.android.xposed.*;

public class RequestHook {
	private static final String LOG_PREFIX = "[RequestHook] ";
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_HOSTS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<String, Boolean> BLOCKED_FullURL = new ConcurrentHashMap<>();
	private static final CountDownLatch loadDataLatch = new CountDownLatch(2);

	static {
		loadBlockedHostsAsync();
		loadBlockedFullURLAsync();
	}

	public static void init() {
		try {
			setupDNSRequestHook();
			setupHttpConnectionHook();
		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error while hooking: " + e.getMessage());
		}
	}

	private static void setupDNSRequestHook() {
		XposedHelpers.findAndHookMethod(InetAddress.class, "getByName", String.class, InetAddressHook);
		XposedHelpers.findAndHookMethod(InetAddress.class, "getAllByName", String.class, InetAddressHook);
	}

	private static final XC_MethodHook InetAddressHook = new XC_MethodHook() {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			String host = (String) param.args[0];
			if (host != null && shouldBlockDnsRequest(host)) {
				String methodName = param.method.getName();
				if ("getByName".equals(methodName)) {
					param.setResult(null);
				} else if ("getAllByName".equals(methodName)) {
					param.setResult(new InetAddress[0]);
				}
				return;
			}
		}
	};

	private static void setupHttpConnectionHook() {
		try {
			ClassLoader systemClassLoader = ClassLoader.getSystemClassLoader();
			Class<?> httpURLConnectionImplClass = Class.forName("com.android.okhttp.internal.huc.HttpURLConnectionImpl",
					true, systemClassLoader);

			XposedHelpers.findAndHookMethod(httpURLConnectionImplClass, "getInputStream", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
					if (shouldInterceptHttpConnection(httpURLConnection)) {
						BlockedURLConnection blockedConnection = new BlockedURLConnection(httpURLConnection.getURL());
						param.setResult(blockedConnection.getInputStream());
					}
				}
			});

			XposedHelpers.findAndHookMethod(httpURLConnectionImplClass, "getOutputStream", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					HttpURLConnection httpURLConnection = (HttpURLConnection) param.thisObject;
					if (shouldInterceptHttpConnection(httpURLConnection)) {
						BlockedURLConnection blockedConnection = new BlockedURLConnection(httpURLConnection.getURL());
						param.setResult(blockedConnection.getOutputStream());
					}
				}
			});

		} catch (Exception e) {
			XposedBridge.log(LOG_PREFIX + "Error setting up HTTP connection hook: " + e.getMessage());
		}
	}

	private static boolean shouldInterceptHttpConnection(HttpURLConnection httpURLConnection) {
		if (httpURLConnection == null) {
			return false;
		}
		URL url = httpURLConnection.getURL();
		if (url != null && shouldBlockHttpsRequest(url)) {
			return true;
		}
		return url != null && shouldBlockHttpsRequest(url.getHost());
	}

	private static boolean checkAndBlockRequest(String host, String requestType,
			Function<String, Boolean> blockChecker) {
		sendBlockedRequestBroadcast("all", requestType, null, host);
		if (host == null) {
			return false;
		}
		waitForDataLoading();
		boolean isBlocked = blockChecker.apply(host);
		sendBlockedRequestBroadcast(isBlocked ? "block" : "pass", requestType, isBlocked, host);
		return isBlocked;
	}

	private static boolean shouldBlockDnsRequest(String host) {
//		XposedBridge.log(LOG_PREFIX + "Checking DNS Request: " + host);
		return checkAndBlockRequest(host, "DNS", BLOCKED_HOSTS::containsKey);
	}

	private static boolean shouldBlockHttpsRequest(String host) {
		return checkAndBlockRequest(host, " HTTPS-host", BLOCKED_HOSTS::containsKey);
	}

	private static boolean shouldBlockHttpsRequest(URL url) {
		if (url == null) {
			return false;
		}

//		XposedBridge.log(LOG_PREFIX + "Checking URL: " + url.toString());

		String host = url.getHost();
		if (shouldBlockDnsRequest(host) || shouldBlockHttpsRequest(host)) {
			return true;
		}

		String baseUrlString;
		try {
			baseUrlString = new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getPath()).toExternalForm();
		} catch (MalformedURLException e) {
			XposedBridge.log(LOG_PREFIX + "Malformed URL: " + e.getMessage());
			return false;
		}

		return checkAndBlockRequest(baseUrlString, " HTTPS-full", BLOCKED_FullURL::containsKey);
	}

	private static void waitForDataLoading() {
		if (BLOCKED_HOSTS.isEmpty() && BLOCKED_FullURL.isEmpty()) {
			try {
				loadDataLatch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				XposedBridge.log(LOG_PREFIX + "Interrupted while waiting for data loading");
			}
		}
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedHostsAsync() {
		Flowable.create(emitter -> {
			try (InputStream inputStream = RequestHook.class.getResourceAsStream("/assets/blocked_hosts.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					return;
				}
				String host;
				while ((host = reader.readLine()) != null) {
					BLOCKED_HOSTS.put(host, Boolean.TRUE);
					emitter.onNext(host);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io()).observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown).subscribe(host -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked hosts: " + error));
	}

	@SuppressLint("CheckResult")
	private static void loadBlockedFullURLAsync() {
		Flowable.create(emitter -> {
			try (InputStream inputStream = RequestHook.class.getResourceAsStream("/assets/blocked_full_urls.txt");
					BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				if (inputStream == null) {
					return;
				}
				String url;
				while ((url = reader.readLine()) != null) {
					BLOCKED_FullURL.put(url.trim(), Boolean.TRUE);
					emitter.onNext(url);
				}
				emitter.onComplete();
			} catch (IOException e) {
				emitter.onError(e);
			}
		}, BackpressureStrategy.BUFFER).subscribeOn(Schedulers.io()).observeOn(Schedulers.computation())
				.doFinally(loadDataLatch::countDown).subscribe(url -> {
				}, error -> XposedBridge.log(LOG_PREFIX + "Error loading blocked full URLs: " + error));
	}

	private static void sendBlockedRequestBroadcast(String type, @Nullable String requestType,
			@Nullable Boolean isBlocked, String request) {
		Intent intent;
		if (Objects.equals(type, "all")) {
			intent = new Intent("com.rikkati.ALL_REQUEST");
		} else if (Objects.equals(type, "block")) {
			intent = new Intent("com.rikkati.BLOCKED_REQUEST");
		} else {
			intent = new Intent("com.rikkati.PASS_REQUEST");
		}

		Context currentContext = AndroidAppHelper.currentApplication();
		if (currentContext != null) {
			PackageManager pm = currentContext.getPackageManager();
			String appName;
			try {
				appName = pm
						.getApplicationLabel(
								pm.getApplicationInfo(currentContext.getPackageName(), PackageManager.GET_META_DATA))
						.toString();
			} catch (PackageManager.NameNotFoundException e) {
				appName = currentContext.getPackageName();

			}
			appName += requestType;
			String packageName = currentContext.getPackageName();
			BlockedRequest blockedRequest = new BlockedRequest(appName, packageName, request,
					System.currentTimeMillis(), type, isBlocked);

			intent.putExtra("request", blockedRequest);
			AndroidAppHelper.currentApplication().sendBroadcast(intent);
		} else {
			Log.w("RequestHook", "sendBlockedRequestBroadcast: currentContext is null");
		}

	}

}
