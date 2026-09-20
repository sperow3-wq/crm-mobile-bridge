package pl.usundlug.crmbridge.telephony

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager

object DialerRole {
    fun isHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom?.defaultDialerPackage == context.packageName
        }
    }
}
