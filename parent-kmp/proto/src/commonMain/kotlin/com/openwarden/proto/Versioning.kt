package com.openwarden.proto

/** Wire format version negotiated between parent and child (PARENT_KMP_STRUCTURE.md §1 Versioning.kt). */
const val POLICY_BUNDLE_FORMAT_VERSION: Int = 1

object Versioning {
    /** True if a remote-declared format version is one this build can speak. */
    fun isCompatible(remote: Int): Boolean = remote == POLICY_BUNDLE_FORMAT_VERSION
}
