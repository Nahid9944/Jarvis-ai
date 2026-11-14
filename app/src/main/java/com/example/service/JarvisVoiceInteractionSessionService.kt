package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log
import com.example.MainActivity

/**
 * Voice Interaction Session Service for Android System Assistant Integration.
 */
class JarvisVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return JarvisVoiceSession(this)
    }

    private class JarvisVoiceSession(context: Context) : VoiceInteractionSession(context) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            Log.i("JarvisVoiceSession", "Android System Assist invoked J.A.R.V.I.S.")
            try {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra("EXTRA_WAKE_TRIGGERED", true)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("JarvisVoiceSession", "Failed launching MainActivity on assist trigger", e)
            }
            hide()
        }
    }
}
