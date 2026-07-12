/*
 * First-run permissions + battery-exemption checklist (#4).
 *
 * The app's core use case is an unattended phone with a non-technical
 * operator (design doc's own framing) -- system permission dialogs with
 * zero context, thrown at the user on every onCreate with no explanation,
 * are a poor fit for that. This screen replaces the old
 * checkAndMaybeRequestPermissions() "just fire every missing permission at
 * once" behavior with an explicit checklist: what's needed, why, and a
 * per-item grant action, plus a battery-optimization exemption request
 * that never existed before (a real reliability gap -- Doze can silently
 * kill the recording foreground service if the app is never exempted).
 *
 * State here is never persisted/cached -- every check reads live from the
 * OS (ContextCompat.checkSelfPermission / PowerManager), since permissions
 * can be revoked by the user at any time outside the app's control. This
 * screen re-appears automatically (see MainActivity's onResume + NavHost
 * start-destination logic) whenever something required is missing, rather
 * than only being shown once on first install.
 */

package com.polarppgbp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.polarppgbp.ui.Spacing
import com.polarppgbp.ui.StatusColors

/** One checklist requirement. [isGranted] reads live OS state -- never
 * cached -- so a permission revoked after setup is detected correctly. */
data class SetupRequirement(
    val title: String,
    val explanation: String,
    val isGranted: (Context) -> Boolean,
    /** What kind of action grants this item -- a runtime permission
     * request (routed through the Activity's registered launcher) or a
     * one-off Settings intent (e.g. battery exemption, which isn't a
     * runtime permission at all). */
    val grant: GrantAction,
)

sealed class GrantAction {
    data class Permissions(val permissions: Array<String>) : GrantAction()
    data class SettingsIntent(val build: (Activity) -> Intent) : GrantAction()
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** The full checklist. Body sensors is NOT used by the Polar BLE SDK for
 * streaming from a third-party device (confirmed against the SDK's own
 * official example manifests, which declare only Bluetooth scan/connect)
 * -- but IS required at runtime by RecordingService's own
 * foregroundServiceType="health" declaration on Android 14+ (targetSdk 34
 * enforces this specifically; the FOREGROUND_SERVICE_HEALTH manifest
 * permission alone is not sufficient). Confirmed the hard way: removing it
 * during this same change crashed RecordingService.onStartCommand with a
 * SecurityException on every recording attempt. Kept, with corrected
 * explanatory copy reflecting the real reason. */
val setupRequirements: List<SetupRequirement> = listOf(
    SetupRequirement(
        title = "Bluetooth",
        explanation = "Needed to find and connect to the Polar sensor and Omron cuff over BLE.",
        isGranted = { ctx ->
            hasPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
        },
        grant = GrantAction.Permissions(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
        ),
    ),
    SetupRequirement(
        title = "Notifications",
        explanation = "Recording runs as a foreground service and must show an ongoing notification " +
            "while it's active, so Android doesn't kill it.",
        isGranted = { ctx -> hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) },
        grant = GrantAction.Permissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS)),
    ),
    SetupRequirement(
        title = "Body sensors",
        explanation = "Required by Android for the recording service to run as a \"health\" " +
            "background service, even though this app doesn't read the phone's own sensors.",
        isGranted = { ctx -> hasPermission(ctx, Manifest.permission.BODY_SENSORS) },
        grant = GrantAction.Permissions(arrayOf(Manifest.permission.BODY_SENSORS)),
    ),
    SetupRequirement(
        title = "Battery optimization exemption",
        explanation = "Without this, Android can silently pause or kill recording on an " +
            "unattended phone to save battery, losing data with no warning.",
        isGranted = { ctx -> isIgnoringBatteryOptimizations(ctx) },
        grant = GrantAction.SettingsIntent { activity ->
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}"),
            )
        },
    ),
)

fun allSetupRequirementsMet(context: Context): Boolean =
    setupRequirements.all { it.isGranted(context) }

@Composable
fun FirstRunScreen(
    context: Context,
    refreshKey: Int,
    onRequestPermissions: (Array<String>) -> Unit,
    onRequestSettingsIntent: ((Activity) -> Intent) -> Unit,
    onContinue: () -> Unit,
) {
    // refreshKey is bumped by MainActivity after any grant action returns
    // (permission dialog or Settings screen) so this recomposes and
    // re-reads live OS state instead of showing stale granted/missing status.
    val allGranted = remember(refreshKey) { allSetupRequirementsMet(context) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text("Before you start", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This app is meant to run recording sessions on a phone that's left alone for a " +
                    "while. These permissions make sure nothing fails silently mid-session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xs))

            setupRequirements.forEach { req ->
                val granted = remember(refreshKey) { req.isGranted(context) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (granted) "✓" else "○",
                                color = if (granted) StatusColors.Capturing else MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(req.title, style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            req.explanation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!granted) {
                        Spacer(Modifier.height(Spacing.sm))
                        OutlinedButton(onClick = {
                            when (val g = req.grant) {
                                is GrantAction.Permissions -> onRequestPermissions(g.permissions)
                                is GrantAction.SettingsIntent -> onRequestSettingsIntent(g.build)
                            }
                        }) { Text("Grant") }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onContinue,
                enabled = allGranted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (allGranted) "Continue" else "Grant all to continue")
            }
        }
    }
}
