package com.supermobtracker.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;


public final class ReflectionUtils {
    /** Cache for declared fields of classes. */
    private static final Map<Class<?>, Map<String, Field>> fieldCache = new HashMap<>();

    /** Cache for declared methods of classes. */
    private static final Map<Class<?>, Map<String, Method>> methodCache = new HashMap<>();


    private ReflectionUtils() {}

    public static Field getDeclaredField(Class<?> clazz, String... fieldNames) {
        if (clazz == null || fieldNames == null || fieldNames.length == 0) return null;

        Map<String, Field> cachedFields = getCachedFields(clazz);
        for (String fieldName : fieldNames) {
            Field field = cachedFields.get(fieldName);
            if (field != null) return field;
        }

        return null;
    }

    public static Field getDeclaredField(Class<?> clazz, Runnable onError, String... fieldNames) {
        Field field = getDeclaredField(clazz, fieldNames);
        if (field == null && onError != null) onError.run();

        return field;
    }

    public static Method getDeclaredMethod(Class<?> clazz, String[] methodNames, Class<?>... parameterTypes) {
        if (clazz == null || methodNames == null || methodNames.length == 0) return null;

        Map<String, Method> cachedMethods = getCachedMethods(clazz);
        for (String methodName : methodNames) {
            Method method = cachedMethods.get(buildMethodKey(methodName, parameterTypes));
            if (method != null) return method;
        }

        return null;
    }

    public static Method getDeclaredMethod(Class<?> clazz, Runnable onError, String[] methodNames, Class<?>... parameterTypes) {
        Method method = getDeclaredMethod(clazz, methodNames, parameterTypes);
        if (method == null && onError != null) onError.run();

        return method;
    }

    private static Map<String, Field> getCachedFields(Class<?> clazz) {
        Map<String, Field> cachedFields = fieldCache.get(clazz);
        if (cachedFields != null) return cachedFields;

        cachedFields = new HashMap<>();
        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);
            cachedFields.put(field.getName(), field);
        }

        fieldCache.put(clazz, cachedFields);

        return cachedFields;
    }

    private static Map<String, Method> getCachedMethods(Class<?> clazz) {
        Map<String, Method> cachedMethods = methodCache.get(clazz);
        if (cachedMethods != null) return cachedMethods;

        cachedMethods = new HashMap<>();
        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);
            cachedMethods.put(buildMethodKey(method.getName(), method.getParameterTypes()), method);
        }

        methodCache.put(clazz, cachedMethods);

        return cachedMethods;
    }

    private static String buildMethodKey(String methodName, Class<?>... parameterTypes) {
        StringBuilder keyBuilder = new StringBuilder(methodName);
        keyBuilder.append('#');

        for (Class<?> parameterType : parameterTypes) {
            keyBuilder.append(parameterType == null ? "null" : parameterType.getName());
            keyBuilder.append(';');
        }

        return keyBuilder.toString();
    }
}
