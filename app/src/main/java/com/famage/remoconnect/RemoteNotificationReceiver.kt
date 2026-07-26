package com.famage.remoconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RemoteNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RemoConnectController.handleNotificationAction(context, intent.action)
    }
}
