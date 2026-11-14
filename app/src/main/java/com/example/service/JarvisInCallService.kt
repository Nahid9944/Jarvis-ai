package com.example.service

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class JarvisCallInfo(
    val phoneNumber: String = "",
    val callerName: String = "",
    val state: Int = Call.STATE_DISCONNECTED,
    val stateLabelBn: String = "কোনো সক্রিয় কল নেই",
    val connectTimeMillis: Long = 0L,
    val isIncoming: Boolean = false
)

/**
 * Android Telecom InCallService implementation for J.A.R.V.I.S. OS.
 * Manages incoming/outgoing calls, caller announcement, and voice-controlled answer/reject.
 */
class JarvisInCallService : InCallService() {

    companion object {
        private const val TAG = "JarvisInCallService"
        private var instance: JarvisInCallService? = null
        private var currentCall: Call? = null

        private val _currentCallInfo = MutableStateFlow(JarvisCallInfo())
        val currentCallInfo: StateFlow<JarvisCallInfo> = _currentCallInfo.asStateFlow()

        fun isConnected(): Boolean = instance != null
        fun hasActiveCall(): Boolean = currentCall != null && currentCall?.state != Call.STATE_DISCONNECTED
        fun isRinging(): Boolean = currentCall?.state == Call.STATE_RINGING

        fun isDefaultDialer(context: Context): Boolean {
            return try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                tm?.defaultDialerPackage == context.packageName
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Voice-controlled Call Answering
         */
        fun answerCall(): Boolean {
            val call = currentCall ?: return false
            return try {
                if (call.state == Call.STATE_RINGING) {
                    call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    Log.i(TAG, "Call answered via J.A.R.V.I.S. Voice Control")
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "Failed answering call", e)
                false
            }
        }

        /**
         * Voice-controlled Call Rejection for Ringing Calls
         */
        fun rejectCall(): Boolean {
            val call = currentCall ?: return false
            return try {
                if (call.state == Call.STATE_RINGING) {
                    call.reject(Call.REJECT_REASON_DECLINED)
                    Log.i(TAG, "Ringing call rejected via J.A.R.V.I.S.")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed rejecting call", e)
                false
            }
        }

        /**
         * Voice-controlled End/Disconnect for Active or In-progress Calls
         */
        fun endCall(): Boolean {
            val call = currentCall ?: return false
            return try {
                if (call.state != Call.STATE_DISCONNECTED) {
                    call.disconnect()
                    Log.i(TAG, "Call ended/disconnected via J.A.R.V.I.S.")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed disconnecting call", e)
                false
            }
        }

        /**
         * Silences the incoming ringer without hanging up or dropping the incoming call.
         */
        fun silenceIncomingRinger(context: Context): Boolean {
            return try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (tm != null && (isRinging() || hasActiveCall())) {
                    tm.silenceRinger()
                    Log.i(TAG, "Incoming call ringer silenced via TelecomManager without altering STREAM_RING permanently.")
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed silencing incoming ringer via TelecomManager", e)
                false
            }
        }

        fun silenceRinger(context: Context): Boolean {
            return silenceIncomingRinger(context)
        }

        fun setMute(muted: Boolean): Boolean {
            val service = instance ?: return false
            if (!hasActiveCall()) return false
            return try {
                service.setMuted(muted)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun setSpeaker(enabled: Boolean): Boolean {
            val service = instance ?: return false
            if (!hasActiveCall()) return false
            return try {
                val targetRoute = if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
                service.setAudioRoute(targetRoute)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun getCallDurationSeconds(): Long {
            val info = _currentCallInfo.value
            return if (info.connectTimeMillis > 0 && hasActiveCall()) {
                (System.currentTimeMillis() - info.connectTimeMillis) / 1000L
            } else 0L
        }

        fun resolveContactName(context: Context, phoneNumber: String): String {
            return com.example.util.JarvisContactSearchEngine.resolveContactNameFromNumber(context, phoneNumber)
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            updateCallInfo(call)
            if (state == Call.STATE_DISCONNECTED) {
                currentCall = null
                _currentCallInfo.value = JarvisCallInfo()
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            super.onDetailsChanged(call, details)
            updateCallInfo(call)
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        instance = this
        currentCall = call
        call.registerCallback(callCallback)
        updateCallInfo(call)
        Log.i(TAG, "New call added: state=${call.state}")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (currentCall == call) {
            currentCall = null
            _currentCallInfo.value = JarvisCallInfo()
        }
        Log.i(TAG, "Call removed")
    }

    private fun updateCallInfo(call: Call) {
        val number = call.details?.handle?.schemeSpecificPart ?: ""
        val callerName = resolveContactName(applicationContext, number)
        val isIncoming = call.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) != true &&
                (call.state == Call.STATE_RINGING || call.details?.callDirection == Call.Details.DIRECTION_INCOMING)

        val stateLabel = when (call.state) {
            Call.STATE_RINGING -> "ইনকামিং কল বাজছে"
            Call.STATE_DIALING -> "কল ডায়াল করা হচ্ছে"
            Call.STATE_ACTIVE -> "কল চলমান আছে"
            Call.STATE_HOLDING -> "কল হোল্ডে রাখা হয়েছে"
            Call.STATE_DISCONNECTED -> "কল সমাপ্ত হয়েছে"
            else -> "কল স্টেট: ${call.state}"
        }

        _currentCallInfo.value = JarvisCallInfo(
            phoneNumber = number,
            callerName = callerName,
            state = call.state,
            stateLabelBn = stateLabel,
            connectTimeMillis = call.details?.connectTimeMillis ?: 0L,
            isIncoming = isIncoming
        )
    }

    override fun onDestroy() {
        instance = null
        currentCall = null
        super.onDestroy()
    }
}
