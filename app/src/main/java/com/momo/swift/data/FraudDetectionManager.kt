package com.momo.swift.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Real-time manager that syncs community fraud alerts from Firestore,
 * persists them locally for offline protection, and detects scam numbers instantly as agents type.
 */
object FraudDetectionManager {

    private const val TAG = "FraudDetectionManager"
    private const val COLLECTION_NAME = "fraud_broadcasts"
    private const val CACHE_FILE_NAME = "fraud_alerts_cache.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var firestore: FirebaseFirestore? = null
    private var listenerRegistration: ListenerRegistration? = null
    private var cacheFile: File? = null

    private val _alertsFlow = MutableStateFlow<List<FraudAlert>>(emptyList())
    val alertsFlow: StateFlow<List<FraudAlert>> = _alertsFlow.asStateFlow()

    private val _newBroadcastEvent = MutableSharedFlow<FraudAlert>(extraBufferCapacity = 5)
    val newBroadcastEvent: SharedFlow<FraudAlert> = _newBroadcastEvent.asSharedFlow()

    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        loadCachedAlerts()

        try {
            firestore = FirebaseFirestore.getInstance()
            startRealtimeSync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firestore for fraud broadcasts", e)
        }
    }

    /**
     * Normalizes a phone number into standard 10-digit format (054XXXXXXX).
     */
    fun normalizePhone(raw: String): String {
        if (raw.isBlank()) return ""
        var digits = raw.replace(Regex("[^0-9]"), "")
        if (digits.startsWith("233") && digits.length >= 12) {
            digits = "0" + digits.substring(3)
        } else if (digits.length == 9 && !digits.startsWith("0")) {
            digits = "0$digits"
        }
        return digits
    }

    /**
     * Checks if a typed or pasted phone number matches any broadcasted fraudulent number.
     * Returns the matching FraudAlert if found, or null otherwise.
     */
    fun checkNumber(input: String): FraudAlert? {
        val cleanInput = normalizePhone(input)
        if (cleanInput.length < 8) return null

        val currentAlerts = _alertsFlow.value
        return currentAlerts.firstOrNull { alert ->
            val cleanAlertNorm = normalizePhone(alert.normalizedNumber)
            val cleanAlertRaw = normalizePhone(alert.phoneNumber)

            cleanInput == cleanAlertNorm ||
            cleanInput == cleanAlertRaw ||
            (cleanInput.length >= 9 && cleanAlertNorm.endsWith(cleanInput.takeLast(9))) ||
            (cleanAlertNorm.length >= 9 && cleanInput.endsWith(cleanAlertNorm.takeLast(9)))
        }
    }

    /**
     * Broadcasts a suspicious/fraudulent phone number to the nationwide SwiftAgent network.
     */
    suspend fun broadcastFraudNumber(
        phoneNumber: String,
        fraudType: String,
        description: String,
        reporterPhone: String = ""
    ): Result<String> {
        return try {
            val db = firestore ?: FirebaseFirestore.getInstance()
            val rawPhone = phoneNumber.trim()
            val normalizedPhone = normalizePhone(rawPhone)
            val auth = FirebaseAuth.getInstance()
            val userEmail = auth.currentUser?.email ?: "Agent User"
            val userUid = auth.currentUser?.uid ?: "anonymous"

            val docRef = db.collection(COLLECTION_NAME).document()
            val alertData = hashMapOf(
                "id" to docRef.id,
                "phoneNumber" to rawPhone,
                "normalizedNumber" to normalizedPhone,
                "fraudType" to fraudType,
                "description" to description,
                "reporterEmail" to userEmail,
                "reporterPhone" to reporterPhone,
                "reporterUid" to userUid,
                "reportedAt" to com.google.firebase.Timestamp.now(),
                "broadcastLevel" to "CRITICAL",
                "isVerified" to true
            )

            docRef.set(alertData).await()
            
            // Optimistically update local state and disk cache immediately
            val newAlert = FraudAlert(
                id = docRef.id,
                phoneNumber = rawPhone,
                normalizedNumber = normalizedPhone,
                fraudType = fraudType,
                description = description,
                reporterEmail = userEmail,
                reporterPhone = reporterPhone,
                reportedAt = System.currentTimeMillis(),
                broadcastLevel = "CRITICAL",
                isVerified = true
            )
            val currentList = _alertsFlow.value.toMutableList()
            if (currentList.none { it.id == docRef.id }) {
                currentList.add(0, newAlert)
                _alertsFlow.value = currentList
                saveAlertsToCache(currentList)
            }

            Log.d(TAG, "Successfully broadcasted fraud alert for $rawPhone")
            Result.success(docRef.id)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast fraud alert", e)
            Result.failure(e)
        }
    }

    fun parseDocument(doc: DocumentSnapshot): FraudAlert? {
        return try {
            val id = doc.id
            val phone = doc.getString("phoneNumber") ?: ""
            if (phone.isBlank()) return null
            val normalized = doc.getString("normalizedNumber") ?: normalizePhone(phone)
            val fraudType = doc.getString("fraudType") ?: "Scam"
            val desc = doc.getString("description") ?: ""
            val reporterEmail = doc.getString("reporterEmail") ?: ""
            val reporterPhone = doc.getString("reporterPhone") ?: ""
            val timestamp = try {
                doc.getTimestamp("reportedAt")?.toDate()?.time
                    ?: doc.getLong("reportedAt")
                    ?: 0L
            } catch (e: Exception) {
                0L
            }
            val level = doc.getString("broadcastLevel") ?: "CRITICAL"
            val verified = doc.getBoolean("isVerified") ?: true

            FraudAlert(
                id = id,
                phoneNumber = phone,
                normalizedNumber = normalized,
                fraudType = fraudType,
                description = desc,
                reporterEmail = reporterEmail,
                reporterPhone = reporterPhone,
                reportedAt = timestamp,
                broadcastLevel = level,
                isVerified = verified
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing fraud alert document ${doc.id}", e)
            null
        }
    }

    fun startRealtimeSync() {
        val db = firestore ?: FirebaseFirestore.getInstance()
        listenerRegistration?.remove()

        listenerRegistration = db.collection(COLLECTION_NAME)
            .limit(100)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Listen failed for fraud broadcasts", error)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val alerts = snapshot.documents.mapNotNull { parseDocument(it) }
                        .sortedByDescending { it.reportedAt }

                    val previousIds = _alertsFlow.value.map { it.id }.toSet()
                    _alertsFlow.value = alerts
                    saveAlertsToCache(alerts)

                    // Emit event if a newly broadcasted alert arrived from another agent
                    if (previousIds.isNotEmpty()) {
                        val newAlerts = alerts.filter { it.id !in previousIds }
                        newAlerts.forEach { alert ->
                            _newBroadcastEvent.tryEmit(alert)
                        }
                    }
                }
            }
    }

    fun refreshAlerts() {
        scope.launch {
            try {
                val db = firestore ?: FirebaseFirestore.getInstance()
                if (listenerRegistration == null) {
                    startRealtimeSync()
                }
                val snapshot = db.collection(COLLECTION_NAME)
                    .limit(100)
                    .get()
                    .await()
                val alerts = snapshot.documents.mapNotNull { parseDocument(it) }
                    .sortedByDescending { it.reportedAt }
                if (alerts.isNotEmpty()) {
                    _alertsFlow.value = alerts
                    saveAlertsToCache(alerts)
                    Log.d(TAG, "Refreshed ${alerts.size} fraud alerts from Firestore")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh alerts via get()", e)
            }
        }
    }

    private fun loadCachedAlerts() {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                if (!file.exists()) return@launch

                val jsonStr = file.readText()
                val jsonArray = JSONArray(jsonStr)
                val cachedList = mutableListOf<FraudAlert>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    cachedList.add(
                        FraudAlert(
                            id = obj.optString("id"),
                            phoneNumber = obj.optString("phoneNumber"),
                            normalizedNumber = obj.optString("normalizedNumber"),
                            fraudType = obj.optString("fraudType"),
                            description = obj.optString("description"),
                            reporterEmail = obj.optString("reporterEmail"),
                            reporterPhone = obj.optString("reporterPhone"),
                            reportedAt = obj.optLong("reportedAt"),
                            broadcastLevel = obj.optString("broadcastLevel"),
                            isVerified = obj.optBoolean("isVerified", true)
                        )
                    )
                }

                if (_alertsFlow.value.isEmpty() && cachedList.isNotEmpty()) {
                    _alertsFlow.value = cachedList
                    Log.d(TAG, "Loaded ${cachedList.size} cached fraud alerts for offline protection")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading cached fraud alerts", e)
            }
        }
    }

    private fun saveAlertsToCache(alerts: List<FraudAlert>) {
        scope.launch {
            try {
                val file = cacheFile ?: return@launch
                val jsonArray = JSONArray()
                for (alert in alerts) {
                    val obj = JSONObject().apply {
                        put("id", alert.id)
                        put("phoneNumber", alert.phoneNumber)
                        put("normalizedNumber", alert.normalizedNumber)
                        put("fraudType", alert.fraudType)
                        put("description", alert.description)
                        put("reporterEmail", alert.reporterEmail)
                        put("reporterPhone", alert.reporterPhone)
                        put("reportedAt", alert.reportedAt)
                        put("broadcastLevel", alert.broadcastLevel)
                        put("isVerified", alert.isVerified)
                    }
                    jsonArray.put(obj)
                }
                file.writeText(jsonArray.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error saving fraud alerts to cache", e)
            }
        }
    }
}
