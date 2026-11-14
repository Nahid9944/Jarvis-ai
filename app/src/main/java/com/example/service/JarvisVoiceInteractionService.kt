package com.example.service

import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * Android VoiceInteractionService for Default Android Assistant Integration.
 */
class JarvisVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Log.i("JarvisVoiceService", "J.A.R.V.I.S. Android Voice Interaction Service is Ready.")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.i("JarvisVoiceService", "J.A.R.V.I.S. Voice Interaction Service Shutdown.")
    }
}
