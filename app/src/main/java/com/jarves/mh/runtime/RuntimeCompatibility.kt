package com.jarves.mh.runtime

internal fun supportsArm64Runtime(supportedAbis: Array<String>, osArchitecture: String?): Boolean {
    val kernelIsArm64 = osArchitecture.equals("aarch64", ignoreCase = true) ||
        osArchitecture.equals("arm64", ignoreCase = true)
    return kernelIsArm64 && supportedAbis.any { it.equals("arm64-v8a", ignoreCase = true) }
}
