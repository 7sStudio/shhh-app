package io.github.shhhapp.shhh.update

import androidx.core.content.FileProvider
import java.lang.reflect.Modifier

/**
 * FileProvider caches path roots statically per authority, but Robolectric
 * gives every test a fresh data directory — stale roots must be dropped
 * before each test that resolves a content URI.
 */
fun resetFileProviderCache() {
    FileProvider::class.java.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && MutableMap::class.java.isAssignableFrom(it.type) }
        .forEach { field ->
            field.isAccessible = true
            (field.get(null) as MutableMap<*, *>).clear()
        }
}
