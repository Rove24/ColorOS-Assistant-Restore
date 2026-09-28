package com.github.rove24.assistrestore;

import java.lang.reflect.Method;

/** Small reflection helpers. Every lookup returns {@code null} instead of throwing. */
final class Refl {
    private Refl() {
    }

    static Class<?> load(ClassLoader classLoader, String className) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (Throwable t) {
            return null;
        }
    }

    static Method staticMethod(ClassLoader classLoader, String className, String name, Class<?>... params) {
        Class<?> owner = load(classLoader, className);
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Finds a declared method on the class or on one of its superclasses. */
    static Method method(Class<?> owner, String name, Class<?>... params) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                return type.getDeclaredMethod(name, params);
            } catch (Throwable ignored) {
                // keep walking up the hierarchy
            }
        }
        return null;
    }

    static Boolean staticBooleanField(ClassLoader classLoader, String className, String fieldName) {
        Class<?> owner = load(classLoader, className);
        if (owner == null) {
            return null;
        }
        try {
            return owner.getField(fieldName).getBoolean(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
