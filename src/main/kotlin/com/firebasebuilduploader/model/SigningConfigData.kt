package com.firebasebuilduploader.model

data class SigningConfigData(
    val isNewKeystore: Boolean,
    val keystorePath: String,
    val keystorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    // Only used when isNewKeystore == true
    val validityYears: Int = 25,
    val firstAndLastName: String = "",
    val organizationalUnit: String = "",
    val organization: String = "",
    val city: String = "",
    val state: String = "",
    val countryCode: String = ""
)
