package com.example.multiplicationcoach.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

data class ApkArchiveInfo(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val certificateSha256: String,
)

class UpdatePackageVerificationException(message: String) : Exception(message)

object UpdatePackageVerifier {
    fun verify(info: ApkArchiveInfo, manifest: UpdateManifest, installed: AppUpdateVersion) {
        if (info.packageName != manifest.android.packageName ||
            info.certificateSha256 != manifest.android.signingCertificateSha256 ||
            info.versionName != manifest.version.versionName ||
            info.versionCode != manifest.version.versionCode ||
            !manifest.version.isNewerThan(installed)
        ) {
            throw UpdatePackageVerificationException("Update APK identity does not match the signed manifest")
        }
    }
}

class AndroidUpdateInstaller(private val context: Context) {
    @Suppress("DEPRECATION")
    fun inspect(file: File): ApkArchiveInfo {
        require(file.isFile && file.extension.equals("apk", ignoreCase = true)) { "Update APK is unavailable" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val packageInfo = context.packageManager.getPackageArchiveInfo(file.path, flags)
            ?: throw UpdatePackageVerificationException("Unable to inspect update APK")
        val certificate = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.singleOrNull()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures?.singleOrNull()
        } ?: throw UpdatePackageVerificationException("Update APK must contain one signing certificate")
        return ApkArchiveInfo(
            packageName = packageInfo.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageVersionCode(packageInfo),
            certificateSha256 = certificate.toByteArray().sha256(),
        )
    }

    fun canRequestPackageInstalls(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    @Suppress("DEPRECATION")
    fun install(file: File) {
        require(file.canonicalFile.parentFile == File(context.cacheDir, "updates").canonicalFile) { "Update APK must be private" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update-files", file)
        context.startActivity(
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageVersionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
