package com.github.rove24.assistrestore;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Repaints the assistant invocation arc with Google's own colours and replays the Pixel completion
 * animation, instead of ColorOS' single-colour version.
 *
 * <p>Ported from the standalone "Pixel 底色" module. ColorOS draws the arc that grows from the
 * bottom edge while the user holds the gesture, but paints it in one colour; the Pixel build paints
 * four lights in Google's brand colours and collapses them into a single point when the invocation
 * completes.</p>
 *
 * <p>Two classes carry the work:</p>
 * <ul>
 *   <li>{@code com.android.systemui.assist.ui.InvocationLightsView} — the arc itself. Its colour
 *   list is rewritten on inflation and again whenever the system tries to set its own colours, and
 *   its darkness pass is suppressed so the arc keeps the Google palette.</li>
 *   <li>{@code com.android.systemui.assist.ui.DefaultUiController} — the driver. Its progress and
 *   completion callbacks are replaced with the Pixel geometry and easing.</li>
 * </ul>
 *
 * <p>Every hook checks {@link AssistConfig#pixelLights} on each call, so flipping the switch takes
 * effect without restarting SystemUI.</p>
 */
final class PixelLightHooks {
    private static final String LIGHTS_VIEW = "com.android.systemui.assist.ui.InvocationLightsView";
    private static final String UI_CONTROLLER = "com.android.systemui.assist.ui.DefaultUiController";

    /** Google's brand palette, in the order the arc paints the four lights. */
    private static final int[] GOOGLE_COLORS = {
            0xFF4285F4, // blue
            0xFFEA4335, // red
            0xFFFBBC05, // yellow
            0xFF34A853, // green
    };

    /** The easing Pixel uses while the invocation grows. */
    private static final PathInterpolator PROGRESS_EASING =
            new PathInterpolator(0.83f, 0.0f, 0.84f, 1.0f);

    private final AssistRestoreModule module;

    /* InvocationLightsView members, resolved once from the first instance seen. */
    private boolean lightsViewResolved;
    private Field useNavBarColorField;
    private Field assistLightsField;
    private Field guideField;
    private Method setLightMethod;
    private Method registerNavBarListenerMethod;

    /* Per-light colour accessor, resolved once from the first light seen. */
    private boolean lightColorResolved;
    private Method setColorMethod;
    private Field lightColorField;

    /* The view's own colour entry points, kept so the original palette can be put back. */
    private Method setColorsMethod;
    private Method updateDarknessMethod;
    /** Last darkness the system asked for, replayed when the feature is switched back off. */
    private float lastDarkIntensity;
    private boolean sawDarkIntensity;

    /* The guide's region geometry, resolved once from the first instance seen. */
    private boolean guideResolved;
    private Field regionsField;
    private Field normalizedLengthField;

    /* DefaultUiController members, resolved once from the first instance seen. */
    private boolean controllerResolved;
    private Field lightsViewField;
    private Field lastProgressField;
    private Field invocationInProgressField;

    private ValueAnimator completionAnimator;

    private PixelLightHooks(AssistRestoreModule module) {
        this.module = module;
    }

    static void install(AssistRestoreModule module, ClassLoader classLoader) {
        PixelLightHooks hooks = new PixelLightHooks(module);
        hooks.installLightsView(classLoader);
        hooks.installUiController(classLoader);
    }

    // -----------------------------------------------------------------------------------------
    // InvocationLightsView: the arc
    // -----------------------------------------------------------------------------------------

    private void installLightsView(ClassLoader classLoader) {
        Class<?> view;
        try {
            view = Class.forName(LIGHTS_VIEW, true, classLoader);
        } catch (Throwable t) {
            module.logWarn("pixel_lights_view_missing target=" + LIGHTS_VIEW + " reason=" + t);
            return;
        }
        // Kept so the original palette can be put back: these are the very methods the system uses
        // to do that, and they are called through the hooks below so the switch still governs them.
        setColorsMethod = optionalMethod(view, "setColors", Integer.class);
        updateDarknessMethod = optionalMethod(view, "updateDarkness", float.class);

        hook(view, "onFinishInflate", new Class<?>[0], chain -> {
            Object result = chain.proceed();
            if (AssistConfig.pixelLights(HookPrefs.get())) {
                applyGoogleColors(chain.getThisObject());
            }
            return result;
        });
        // ColorOS re-tints the arc for light backgrounds; the Google palette must survive that.
        hook(view, "updateDarkness", new Class<?>[] { float.class }, chain -> {
            lastDarkIntensity = (Float) chain.getArg(0);
            sawDarkIntensity = true;
            if (!AssistConfig.pixelLights(HookPrefs.get())) {
                return chain.proceed();
            }
            return null;
        });
        hook(view, "setColors", new Class<?>[] { Integer.class }, chain -> {
            if (!AssistConfig.pixelLights(HookPrefs.get())) {
                return chain.proceed();
            }
            applyGoogleColors(chain.getThisObject());
            return null;
        });
    }

    // -----------------------------------------------------------------------------------------
    // DefaultUiController: progress and completion
    // -----------------------------------------------------------------------------------------

    private void installUiController(ClassLoader classLoader) {
        Class<?> controller;
        try {
            controller = Class.forName(UI_CONTROLLER, true, classLoader);
        } catch (Throwable t) {
            module.logWarn("pixel_lights_controller_missing target=" + UI_CONTROLLER
                    + " reason=" + t);
            return;
        }
        hook(controller, "setProgressInternal", new Class<?>[] { float.class }, chain -> {
            if (!AssistConfig.pixelLights(HookPrefs.get())) {
                // Switching the feature off has to put the view back. The palette was written into
                // the lights themselves, so merely stopping would leave them Google-coloured until
                // SystemUI re-inflates the arc — which is why turning the switch off used to need
                // a SystemUI restart.
                restoreOriginalColors(chain.getThisObject());
                return chain.proceed();
            }
            setProgress(chain.getThisObject(), (Float) chain.getArg(0));
            return null;
        });
        hook(controller, "animateInvocationCompletion", new Class<?>[0], chain -> {
            if (!AssistConfig.pixelLights(HookPrefs.get())) {
                return chain.proceed();
            }
            animateCompletion(chain.getThisObject());
            return null;
        });
        hook(controller, "hide", new Class<?>[0], chain -> {
            cancelCompletion();
            return chain.proceed();
        });
    }

    private void hook(Class<?> owner, String name, Class<?>[] parameterTypes,
            XposedInterface.Hooker hooker) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            module.hook(method)
                    .setId("pixel_lights_" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            module.logInfo("hook_installed target=" + owner.getName() + "." + name);
        } catch (Throwable t) {
            module.logWarn("hook_skipped target=" + owner.getName() + "." + name + " reason=" + t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Colours
    // -----------------------------------------------------------------------------------------

    /**
     * Puts the system's own palette back.
     *
     * <p>{@code setColors(null)} is the view's own "follow the navigation bar again" path: it flips
     * {@code mUseNavBarColor} back on and re-registers the navigation-bar listener. Replaying the
     * last darkness then recomputes the real colour, because {@code updateDarkness} only does any
     * work while that flag is set.</p>
     *
     * <p>Both calls go through the hooks above, so the switch still governs them: with the feature
     * off they reach the original methods, and with it on they are no-ops.</p>
     */
    private void restoreOriginalColors(Object controller) {
        if (setColorsMethod == null) {
            return;
        }
        try {
            resolveControllerFields(controller);
            Object lightsView = lightsViewField.get(controller);
            if (lightsView == null) {
                return;
            }
            setColorsMethod.invoke(lightsView, (Object) null);
            if (updateDarknessMethod != null && sawDarkIntensity) {
                updateDarknessMethod.invoke(lightsView, lastDarkIntensity);
            }
        } catch (Throwable t) {
            module.logWarn("pixel_lights_restore_failed " + t);
        }
    }

    /** Rewrites the arc's colour list with the Google palette. */
    private void applyGoogleColors(Object lightsView) {
        if (lightsView == null) {
            return;
        }
        try {
            resolveLightsViewFields(lightsView);
            if (useNavBarColorField != null) {
                // The nav bar colour would otherwise override every light.
                useNavBarColorField.setBoolean(lightsView, false);
            }
            Object lights = assistLightsField.get(lightsView);
            if (!(lights instanceof List)) {
                return;
            }
            List<?> list = (List<?>) lights;
            int count = Math.min(list.size(), GOOGLE_COLORS.length);
            for (int i = 0; i < count; i++) {
                Object light = list.get(i);
                if (light != null) {
                    resolveLightColorAccessor(light);
                    setLightColor(light, GOOGLE_COLORS[i]);
                }
            }
        } catch (Throwable t) {
            module.logWarn("pixel_lights_colors_failed " + t);
        }
    }

    private void resolveLightColorAccessor(Object light) {
        if (lightColorResolved) {
            return;
        }
        synchronized (PixelLightHooks.class) {
            if (lightColorResolved) {
                return;
            }
            Class<?> type = light.getClass();
            try {
                setColorMethod = type.getDeclaredMethod("setColor", int.class);
                setColorMethod.setAccessible(true);
            } catch (Throwable t) {
                try {
                    lightColorField = findField(type, "mColor");
                    lightColorField.setAccessible(true);
                } catch (Throwable ignored) {
                    // Neither accessor exists: setLightColor() will simply do nothing.
                }
            }
            lightColorResolved = true;
        }
    }

    private void setLightColor(Object light, int color) {
        try {
            if (setColorMethod != null) {
                setColorMethod.invoke(light, color);
            } else if (lightColorField != null) {
                lightColorField.setInt(light, color);
            }
        } catch (Throwable t) {
            module.logWarn("pixel_lights_set_color_failed " + t);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Geometry
    // -----------------------------------------------------------------------------------------

    /**
     * Places the four lights for the current progress.
     *
     * <p>Below 1.0 the arc grows out of the bottom edge as four equal spans with a small gap
     * between the outer pair; above 1.0 the outer pair collapses inward so the whole thing gathers
     * into the centre.</p>
     */
    private void layoutLights(Object lightsView, float progress) throws Throwable {
        resolveLightsViewFields(lightsView);
        resolveGuideFields(lightsView);
        Object[] regions = (Object[]) regionsField.get(guideField.get(lightsView));
        float normalizedLength = normalizedLengthField.getFloat(regions[7]);
        float totalWidth = normalizedLengthField.getFloat(regions[0]);
        float quarter = totalWidth / 4.0f;

        if (progress > 1.0f) {
            float collapsed = (normalizedLength * 0.6f) / 2.0f;
            float edge = collapsed
                    + ((quarter - collapsed) * (1.0f - Math.min(1.0f, progress - 1.0f)));
            setLightRange(lightsView, quarter - edge, quarter, 0);
            float twoQuarters = 2.0f * quarter;
            setLightRange(lightsView, quarter, twoQuarters, 1);
            float threeQuarters = quarter * 3.0f;
            setLightRange(lightsView, twoQuarters, threeQuarters, 2);
            setLightRange(lightsView, threeQuarters, edge + threeQuarters, 3);
            return;
        }

        float gap = 0.2f * normalizedLength;
        float remaining = 1.0f - progress;
        float leftStart = ((-normalizedLength) + gap) * remaining;
        float rightEnd = totalWidth + ((normalizedLength - gap) * remaining);
        float span = quarter * progress;
        float leftEnd = leftStart + span;
        setLightRange(lightsView, leftStart, leftEnd, 0);
        float twoSpans = 2.0f * span;
        setLightRange(lightsView, leftEnd, leftStart + twoSpans, 1);
        float rightStart = rightEnd - span;
        setLightRange(lightsView, rightEnd - twoSpans, rightStart, 2);
        setLightRange(lightsView, rightStart, rightEnd, 3);
    }

    private void setLightRange(Object lightsView, float from, float to, int index) {
        if (setLightMethod == null) {
            return;
        }
        try {
            setLightMethod.invoke(lightsView, from, to, index);
        } catch (Throwable t) {
            module.logWarn("pixel_lights_set_light_failed " + t);
        }
    }

    /** Parks every light at the origin so nothing is left painted after the arc fades out. */
    private void collapseLights(Object lightsView) {
        for (int i = 0; i < GOOGLE_COLORS.length; i++) {
            if (setLightMethod == null) {
                return;
            }
            try {
                setLightMethod.invoke(lightsView, 0.0f, 0.0f, i);
            } catch (Throwable t) {
                return;
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Progress / completion
    // -----------------------------------------------------------------------------------------

    private void setProgress(Object controller, float progress) {
        try {
            resolveControllerFields(controller);
            Object lightsViewObject = lightsViewField.get(controller);
            if (lightsViewObject == null) {
                return;
            }
            View lightsView = (View) lightsViewObject;
            if (progress <= 0.0f) {
                cancelCompletion();
                lightsView.setVisibility(View.GONE);
                lightsView.setAlpha(1.0f);
                setInvocationInProgress(controller, false);
                return;
            }
            if (registerNavBarListenerMethod != null) {
                try {
                    registerNavBarListenerMethod.invoke(lightsViewObject, (Object[]) null);
                } catch (Throwable ignored) {
                    // Optional: only present on some builds.
                }
            }
            applyGoogleColors(lightsViewObject);
            cancelCompletion();
            float eased = progress <= 0.85f
                    ? PROGRESS_EASING.getInterpolation(progress / 0.85f)
                    : (((progress - 0.85f) / 0.15f) * 0.35f) + 1.0f;
            if (lastProgressField != null) {
                lastProgressField.setFloat(controller, progress);
            }
            setInvocationInProgress(controller, true);
            layoutLights(lightsViewObject, eased);
            lightsView.setAlpha(1.0f);
            lightsView.setVisibility(View.VISIBLE);
            lightsView.invalidate();
        } catch (Throwable t) {
            module.logWarn("pixel_lights_progress_failed " + t);
        }
    }

    /** Runs the Pixel gather animation and fades the arc out when it ends. */
    private void animateCompletion(Object controller) {
        try {
            resolveControllerFields(controller);
            Object lightsViewObject = lightsViewField.get(controller);
            if (lightsViewObject == null) {
                return;
            }
            View lightsView = (View) lightsViewObject;
            cancelCompletion();

            float start = 1.0f;
            if (lastProgressField != null) {
                try {
                    float last = lastProgressField.getFloat(controller);
                    if (last > 0.85f) {
                        start = 1.0f + (((last - 0.85f) / 0.15f) * 0.35f);
                    }
                } catch (Throwable ignored) {
                    // Keep the default start value.
                }
            }

            ValueAnimator animator = ValueAnimator.ofFloat(start, 1.35f);
            animator.setDuration(180L);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(valueAnimator -> {
                try {
                    layoutLights(lightsViewObject,
                            (Float) valueAnimator.getAnimatedValue());
                    lightsView.invalidate();
                } catch (Throwable ignored) {
                    // A failed frame must not kill the animation.
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    lightsView.animate()
                            .alpha(0.0f)
                            .setDuration(140L)
                            .setListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator fadeOut) {
                                    lightsView.setVisibility(View.GONE);
                                    lightsView.setAlpha(1.0f);
                                    collapseLights(lightsViewObject);
                                    setInvocationInProgress(controller, false);
                                }
                            })
                            .start();
                }
            });
            completionAnimator = animator;
            animator.start();
        } catch (Throwable t) {
            module.logWarn("pixel_lights_completion_failed " + t);
        }
    }

    private void cancelCompletion() {
        ValueAnimator animator = completionAnimator;
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        completionAnimator = null;
    }

    private void setInvocationInProgress(Object controller, boolean inProgress) {
        if (invocationInProgressField == null) {
            return;
        }
        try {
            invocationInProgressField.setBoolean(controller, inProgress);
        } catch (Throwable ignored) {
            // Best effort only.
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reflection helpers
    // -----------------------------------------------------------------------------------------

    private void resolveLightsViewFields(Object lightsView) {
        if (lightsViewResolved) {
            return;
        }
        synchronized (PixelLightHooks.class) {
            if (lightsViewResolved) {
                return;
            }
            Class<?> type = lightsView.getClass();
            try {
                useNavBarColorField = optionalField(type, "mUseNavBarColor");
                assistLightsField = findField(type, "mAssistInvocationLights");
                assistLightsField.setAccessible(true);
                guideField = optionalField(type, "mGuide");
                setLightMethod = optionalMethod(type, "setLight",
                        float.class, float.class, int.class);
                registerNavBarListenerMethod =
                        optionalMethod(type, "attemptRegisterNavBarListener");
                lightsViewResolved = true;
            } catch (Throwable t) {
                module.logWarn("pixel_lights_view_fields_failed " + t);
            }
        }
    }

    private void resolveGuideFields(Object lightsView) {
        if (guideResolved) {
            return;
        }
        synchronized (PixelLightHooks.class) {
            if (guideResolved) {
                return;
            }
            try {
                Object guide = guideField.get(lightsView);
                regionsField = findField(guide.getClass(), "mRegions");
                regionsField.setAccessible(true);
                Object[] regions = (Object[]) regionsField.get(guide);
                if (regions != null && regions.length > 0 && regions[0] != null) {
                    normalizedLengthField =
                            findField(regions[0].getClass(), "normalizedLength");
                    normalizedLengthField.setAccessible(true);
                }
                guideResolved = true;
            } catch (Throwable t) {
                module.logWarn("pixel_lights_guide_fields_failed " + t);
            }
        }
    }

    private void resolveControllerFields(Object controller) {
        if (controllerResolved) {
            return;
        }
        synchronized (PixelLightHooks.class) {
            if (controllerResolved) {
                return;
            }
            Class<?> type = controller.getClass();
            try {
                lightsViewField = findField(type, "mInvocationLightsView");
                lightsViewField.setAccessible(true);
                lastProgressField = optionalField(type, "mLastInvocationProgress");
                invocationInProgressField = optionalField(type, "mInvocationInProgress");
                controllerResolved = true;
            } catch (Throwable t) {
                module.logWarn("pixel_lights_controller_fields_failed " + t);
            }
        }
    }

    private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Walk up to the superclass.
            }
        }
        throw new NoSuchFieldException(name + " not found in hierarchy of " + owner.getName());
    }

    private Field optionalField(Class<?> owner, String name) {
        try {
            Field field = findField(owner, name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }

    private Method optionalMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }
}
