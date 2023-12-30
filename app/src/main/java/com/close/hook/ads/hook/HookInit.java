package com.close.hook.ads.hook;

import android.util.Log;
import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.close.hook.ads.hook.gc.DisableFlagSecure;
import com.close.hook.ads.hook.gc.DisableShakeAd;
import com.close.hook.ads.hook.gc.HideEnvi;
import com.close.hook.ads.hook.gc.network.HideVPNStatus;
import com.close.hook.ads.hook.gc.network.RequestHook;
import com.close.hook.ads.hook.ha.AppAds;
import com.close.hook.ads.hook.ha.BlockForeignAd;
import com.close.hook.ads.hook.ha.SDKHooks;
import com.close.hook.ads.hook.preference.PreferencesHelper;
import com.close.hook.ads.ui.activity.MainActivity;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookInit implements IXposedHookLoadPackage {
	private static final String TAG = "com.close.hook.ads";

	@SuppressLint("SuspiciousIndentation")
	@Override
	public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
		if (shouldIgnorePackage(lpparam)) {
			return;
		}

		performHooking(lpparam);
	}

	private void activateModule(XC_LoadPackage.LoadPackageParam lpparam) {
		XposedHelpers.findAndHookMethod(MainActivity.class.getName(), lpparam.classLoader, "isModuleActivated",
				XC_MethodReplacement.returnConstant(true));
	}

	private void performHooking(XC_LoadPackage.LoadPackageParam lpparam) {
		if (TAG.equals(lpparam.packageName)) {
			activateModule(lpparam);
		}

		PreferencesHelper prefsHelper = new PreferencesHelper(TAG, "com.close.hook.ads_preferences");
		SettingsManager settingsManager = new SettingsManager(prefsHelper, lpparam.packageName);

		applySettings(settingsManager, lpparam);
	}

	private void applySettings(SettingsManager settingsManager, XC_LoadPackage.LoadPackageParam lpparam) {
		if (settingsManager.isRequestHookEnabled()) {
			RequestHook.init();
		}

    	if (settingsManager.isHandlePlatformAdEnabled()) {
		    BlockForeignAd.INSTANCE.blockAds(lpparam);
		}

		if (settingsManager.isHideVPNStatusEnabled()) {
			HideVPNStatus.proxy();
		}

		if (settingsManager.isDisableFlagSecureEnabled()) {
			DisableFlagSecure.process();
		}

		if (settingsManager.isHideEnivEnabled()) {
			HideEnvi.handle();
		}

		if (settingsManager.isDisableShakeAdEnabled()) {
			DisableShakeAd.handle(lpparam);
		}

		try {
			XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) {
					Context context = (Context) param.args[0];
					ClassLoader classLoader = context.getClassLoader();

					String packageName = context.getPackageName();
					CharSequence appName = getAppName(context, packageName);

					if (!TAG.equals(packageName)) {
						XposedBridge.log("found classload is => " + classLoader.toString());
						XposedBridge.log("Application Name: " + appName);
					}

					AppAds.progress(classLoader, packageName);

					if (settingsManager.isHandlePlatformAdEnabled()) {
						SDKHooks.hookAds(classLoader);
					}
				}
			});
		} catch (Exception e) {
			XposedBridge.log(TAG + " Exception in handleLoadPackage: " + Log.getStackTraceString(e));
		}
	}

	private boolean shouldIgnorePackage(XC_LoadPackage.LoadPackageParam lpparam) {
		return lpparam.appInfo == null
				|| !lpparam.isFirstApplication;
	}

	private CharSequence getAppName(Context context, String packageName) {
		PackageManager packageManager = context.getPackageManager();
		try {
			ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
			return packageManager.getApplicationLabel(appInfo);
		} catch (PackageManager.NameNotFoundException e) {
			XposedBridge.log("Application Name Not Found for package: " + packageName);
			return null;
		}
	}

}