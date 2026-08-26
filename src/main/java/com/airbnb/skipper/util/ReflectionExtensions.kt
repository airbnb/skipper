package com.airbnb.skipper.util

import com.airbnb.skipper.internal.reflection.ReflectionUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Kotlin-friendly extensions for [ReflectionUtils].
 * Returns properly typed Kotlin Lists for seamless use in Kotlin code.
 */

/** All declared methods from [this] class up to (not including) [stopClass]. */
fun Class<*>.allDeclaredMethods(stopClass: Class<*>): List<Method> = ReflectionUtils.getAllDeclaredMethods(this, stopClass)

/** All declared fields from [this] class up to (not including) [stopClass]. */
fun Class<*>.allDeclaredFields(stopClass: Class<*>): List<Field> = ReflectionUtils.getAllDeclaredFields(this, stopClass)
