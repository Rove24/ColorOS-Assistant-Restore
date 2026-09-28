package com.github.rove24.assistrestore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.List;
import java.lang.reflect.Method;

/**
 * Turns a configured target into something that can actually be started.
 *
 * <p>Assistants reach the user in two shapes: an {@code ACTION_ASSIST} activity (the AOSP way an
 * app declares "I can answer an assist request") or a {@code VoiceInteractionService}. A voice
 * interaction service can only be woken through the system assist stack, so a pinned app is always
 * started through its assist activity; when it has none the launcher activity is used instead and
 * the log says so.</p>
 */
final class TargetIntents {
    private static final String ACTION_ASSIST = "android.intent.action.ASSIST";
    private static final String EXTRA_ASSIST_INPUT_HINT_KEYBOARD =
            "android.intent.extra.ASSIST_INPUT_HINT_KEYBOARD";

    private TargetIntents() {
    }

    /**
     * Application context of the injected process.
     *
     * <p>SystemUI and the launcher run a normal Application, so this is a plain {@code Context} that
     * can start the pinned assistant's activity. In {@code system_server} there is no Application
     * and this returns {@code null}, which is why the power-key dispatch is routed from SystemUI.</p>
     */
    static Context appContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return the intent to start, or {@code null} when the package has nothing to start
     */
    static Intent build(Context context, SharedPreferences prefs, String entry) {
        String packageName = AssistConfig.targetPackage(prefs, entry);
        if (packageName == null || packageName.isEmpty()) {
            return null;
        }
        String method = AssistConfig.targetMethod(prefs, entry);

        if (AssistConfig.METHOD_INTENT.equals(method)) {
            String component = AssistConfig.targetComponent(prefs, entry);
            Intent intent = new Intent();
            applyArgs(intent, AssistConfig.targetArgs(prefs, entry), packageName, component);
            if (intent.getComponent() == null && intent.getAction() == null) {
                return null;
            }
            // SystemUI is not an Activity: without this flag startActivity throws and nothing shows.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        // A component filled in by hand is an explicit instruction: use it instead of guessing the
        // app's assist entry. Without one the assist activity (or the launcher entry) is used.
        String component = AssistConfig.targetComponent(prefs, entry);
        if (component != null && !component.trim().isEmpty()) {
            Intent explicit = new Intent();
            applyArgs(explicit, AssistConfig.targetArgs(prefs, entry), packageName, component);
            if (explicit.getComponent() != null) {
                explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return explicit;
            }
        }
        Intent assist = resolveAssistActivity(context, packageName);
        if (assist != null) {
            return assist;
        }
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return launch;
    }

    /** The app's own assist activity, if it declares one. */
    private static Intent resolveAssistActivity(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent probe = new Intent(ACTION_ASSIST).setPackage(packageName);
            List<ResolveInfo> matches = pm.queryIntentActivities(probe, 0);
            if (matches == null || matches.isEmpty()) {
                return null;
            }
            ResolveInfo match = matches.get(0);
            Intent intent = new Intent(ACTION_ASSIST);
            intent.setComponent(new ComponentName(match.activityInfo.packageName,
                    match.activityInfo.name));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Mirrors what AssistManager passes when the gesture did not come from a keyboard.
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_ASSIST_INPUT_HINT_KEYBOARD, false);
            intent.putExtras(extras);
            return intent;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Parses the custom screen's parameter field. Tokens are separated by {@code &}, {@code ,},
     * {@code ·} or whitespace; {@code action=...} sets the intent action, anything else becomes an
     * extra (integer when it parses as one, string otherwise).
     */
    /**
     * Applies the custom screen's parameter field. Tokens may come from the plain {@code key=value}
     * form or from a pasted JSON object, so punctuation is stripped and {@code :} is accepted as a
     * separator too. {@code action}, {@code category}, {@code packageName} and {@code className} are
     * understood; anything else becomes an extra.
     */
    private static void applyArgs(
            Intent intent, String args, String packageName, String component) {
        String resolvedPackage = packageName == null ? "" : packageName.trim();
        String resolvedClass = "";
        if (component != null && component.contains("/")) {
            int slash = component.indexOf('/');
            resolvedPackage = component.substring(0, slash).trim();
            resolvedClass = component.substring(slash + 1).trim();
        } else if (component != null && !component.trim().isEmpty()) {
            // A bare class name is accepted too: the package栏 supplies the package part.
            resolvedClass = component.trim();
        }
        if (args != null && !args.isEmpty()) {
            String cleaned = args
                    .replace('{', ' ')
                    .replace('}', ' ')
                    .replace('"', ' ')
                    .replace('\n', ' ')
                    .replace('\r', ' ');
            for (String token : cleaned.split("[&,\\u00b7\\s]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                int separator = token.indexOf('=');
                if (separator < 0) {
                    separator = token.indexOf(':');
                }
                if (separator <= 0) {
                    continue;
                }
                String key = token.substring(0, separator).trim();
                String value = token.substring(separator + 1).trim();
                if (value.isEmpty()) {
                    continue;
                }
                if ("action".equals(key)) {
                    intent.setAction(value);
                } else if ("category".equals(key)) {
                    intent.addCategory(value);
                } else if ("packageName".equals(key) || "package".equals(key)) {
                    resolvedPackage = value;
                } else if ("className".equals(key) || "component".equals(key)) {
                    resolvedClass = value;
                } else if (!"extra".equals(key)) {
                    try {
                        intent.putExtra(key, Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        intent.putExtra(key, value);
                    }
                }
            }
        }
        if (!resolvedPackage.isEmpty() && !resolvedClass.isEmpty()) {
            String className = resolvedClass.startsWith(".")
                    ? resolvedPackage + resolvedClass
                    : resolvedClass;
            intent.setComponent(new ComponentName(resolvedPackage, className));
        }
    }
}
