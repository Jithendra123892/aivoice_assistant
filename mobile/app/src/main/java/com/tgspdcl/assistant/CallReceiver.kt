package com.tgspdcl.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d("CallReceiver", "Phone State Changed: $state")

        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            Log.d("CallReceiver", "Incoming call from: $incomingNumber")

            if (incomingNumber.isNullOrEmpty()) return

            // 1. Check if the number is in contacts
            val inContacts = isNumberInContacts(context, incomingNumber)
            Log.d("CallReceiver", "Is in contacts: $inContacts")

            if (inContacts) {
                // Known contact: Let the default dialer handle it and ring normally
                Log.d("CallReceiver", "Known contact. Letting it ring normally.")
                return
            }

            // 2. Check if AI Call Assistant is turned ON
            val sharedPrefs = context.getSharedPreferences("TGSPDCL_PREFS", Context.MODE_PRIVATE)
            val isAiActive = sharedPrefs.getBoolean("ai_assistant_active", true)
            Log.d("CallReceiver", "AI Assistant Active State: $isAiActive")

            if (!isAiActive) {
                // AI is off: Let it ring normally
                Log.d("CallReceiver", "AI Assistant is OFF. Letting call ring normally.")
                return
            }

            // 3. Unknown caller & AI is ON: Intercept & Auto-answer!
            Log.d("CallReceiver", "Unknown caller & AI is ON. Intercepting call...")
            try {
                // Request programmatically answering the call (Requires API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                        telecomManager.acceptRingingCall()
                        Log.d("CallReceiver", "Call answered programmatically.")

                        // Route call audio to speakerphone
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.mode = AudioManager.MODE_IN_CALL
                        audioManager.isSpeakerphoneOn = true
                        Log.d("CallReceiver", "Speakerphone enabled.")

                        // Launch MainActivity Call Screen
                        val launchIntent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("EXTRA_INCOMING_CALL", true)
                            putExtra("EXTRA_CALLER_NUMBER", incomingNumber)
                        }
                        context.startActivity(launchIntent)
                    } else {
                        Log.e("CallReceiver", "ANSWER_PHONE_CALLS permission not granted.")
                    }
                }
            } catch (e: Exception) {
                Log.e("CallReceiver", "Error answering call programmatically: ${e.message}", e)
            }
        }
    }

    private fun isNumberInContacts(context: Context, phoneNumber: String): Boolean {
        try {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("CallReceiver", "READ_CONTACTS permission not granted, assuming unknown.")
                return false
            }
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val contactName = it.getString(0)
                    Log.d("CallReceiver", "Found contact: $contactName")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("CallReceiver", "Error querying contacts: ${e.message}", e)
        }
        return false
    }
}
