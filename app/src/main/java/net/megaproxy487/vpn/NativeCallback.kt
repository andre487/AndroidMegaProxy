package net.megaproxy487.vpn

import java.lang.reflect.Proxy

internal fun nativeCallback(type: Class<*>, methodName: String, callback: (Array<out Any?>?) -> Any?): Any =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
        // JNI bindings may keep callbacks in maps or log them. Object methods must not
        // enter the native callback or throw on those threads.
        if (method.declaringClass == Any::class.java) {
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NativeCallback(${type.name})"
                else -> error("Unknown Object method ${method.name}")
            }
        } else {
            check(method.name == methodName) { "Unknown native callback ${method.name}" }
            callback(args)
        }
    }
