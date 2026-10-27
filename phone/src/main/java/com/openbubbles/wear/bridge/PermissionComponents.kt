package com.openbubbles.wear.bridge

import android.content.ComponentName

/** Android may return either package/.ShortClass or package/package.FullClass. */
internal object PermissionComponents {
    fun matches(value: String?, expected: ComponentName): Boolean =
        value?.trim()?.let(ComponentName::unflattenFromString) == expected

    fun contains(list: String?, expected: ComponentName): Boolean =
        list.orEmpty().split(':').any { matches(it, expected) }
}
