/**
 * checkTrialStatus — checks the current user's trial and subscription status.
 * Called on every app launch.
 *
 * Requires authenticated user.
 * Expected data: { deviceId: string, fidHash: string, fingerprintHash: string }
 * Returns: { isPremium, premiumExpiresAt, trialStartedAt, trialExpired,
 *            daysLeft, deviceAlreadyUsed, subscriptionDaysLeft, isRegistered }
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { getDb } = require("../helpers/db");
const { isPremiumActive, TRIAL_DURATION_MS } = require("../helpers/premium");
const { healDeviceLockedEmail } = require("../helpers/deviceLock");

exports.checkTrialStatus = onCall({ invoker: "public", enforceAppCheck: false }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Must be logged in.");
  }

  const db = getDb();
  const uid = request.auth.uid;
  const email = request.auth.token.email || "";
  const { deviceId, fidHash, fingerprintHash } = request.data;

  if (!deviceId || typeof deviceId !== "string") {
    throw new HttpsError("invalid-argument", "deviceId is required.");
  }

  try {
    // Fix #6: Fetch user doc and device doc concurrently for the common registered-user path.
    // For first-time users (no user doc) we fall back to the device-only check below.
    const [userDoc, deviceDocEarly] = await Promise.all([
      db.collection("users").doc(uid).get(),
      db.collection("devices").doc(deviceId).get(),
    ]);

    if (!userDoc.exists) {
      // User not registered yet — use the already-fetched device doc
      const deviceDoc = deviceDocEarly;
      if (deviceDoc.exists) {
        const deviceData = deviceDoc.data();
        const lockedEmail = await healDeviceLockedEmail(
          db, deviceId, deviceData, "checkTrialStatus (new user)"
        );
        if (lockedEmail && lockedEmail !== email) {
          throw new HttpsError("permission-denied", "DEVICE_LOCKED");
        }
      }

      return {
        isPremium: false,
        premiumExpiresAt: 0,
        trialStartedAt: 0,
        trialExpired: false,
        daysLeft: 0,
        deviceAlreadyUsed: deviceDoc.exists,
        subscriptionDaysLeft: 0,
        isRegistered: false,
      };
    }

    const userData = userDoc.data();
    const trialStartedAt = userData.trialStartedAt?.toMillis() || 0;
    const trialExpiredAt = trialStartedAt + TRIAL_DURATION_MS;
    const bonusTrialUntil = userData.bonusTrialUntil?.toMillis() || 0;

    // If a bonus trial grant is active, treat trial as NOT expired
    const bonusActive = bonusTrialUntil > Date.now();
    const trialExpired = bonusActive ? false : Date.now() > trialExpiredAt;

    const effectiveExpiry = bonusActive ? bonusTrialUntil : trialExpiredAt;
    const trialDaysLeft = Math.max(0, Math.ceil((effectiveExpiry - Date.now()) / (24 * 60 * 60 * 1000)));

    const premium = isPremiumActive(userData);
    const premiumExpiresAt = userData.premiumExpiresAt?.toMillis() || 0;
    const subscriptionDaysLeft = premium
      ? Math.max(0, Math.ceil((premiumExpiresAt - Date.now()) / (24 * 60 * 60 * 1000)))
      : 0;

    // Fix #5 — Lazy-expiration: keep Firestore isPremium flag in sync when subscription lapses passively
    if (userData.isPremium && !premium) {
      db.collection("users").doc(uid).update({ isPremium: false }).catch((e) =>
        logger.warn("Lazy-expiration update failed", { uid, error: e.message })
      );
    }

    // Fix #6: Reuse the device doc already fetched concurrently at the top of the function.
    const deviceDoc = deviceDocEarly;
    const deviceAlreadyUsed = deviceDoc.exists && deviceDoc.data()?.uid !== uid;

    if (deviceDoc.exists) {
      const deviceData = deviceDoc.data();
      const lockedEmail = await healDeviceLockedEmail(
        db, deviceId, deviceData, "checkTrialStatus (existing user)"
      );

      if (lockedEmail && lockedEmail !== email) {
        throw new HttpsError("permission-denied", "DEVICE_LOCKED");
      }

      // Update existing device doc and secondary indexes if needed
      const updates = {};
      if (deviceData.uid !== uid) updates.uid = uid;
      if (fidHash && deviceData.fidHash !== fidHash) updates.fidHash = fidHash;
      if (fingerprintHash && deviceData.fingerprintHash !== fingerprintHash) updates.fingerprintHash = fingerprintHash;

      if (Object.keys(updates).length > 0) {
        await db.collection("devices").doc(deviceId).update(updates);

        const batch = db.batch();
        if (fidHash && deviceData.fidHash !== fidHash) {
          batch.set(db.collection("device_fids").doc(fidHash), {
            primaryDeviceId: deviceId,
            linkedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        }
        if (fingerprintHash && deviceData.fingerprintHash !== fingerprintHash) {
          batch.set(db.collection("device_fingerprints").doc(fingerprintHash), {
            primaryDeviceId: deviceId,
            linkedAt: admin.firestore.FieldValue.serverTimestamp(),
          });
        }
        await batch.commit();
      }
    } else {
      // Device not registered yet — link this device to the existing user's email
      await db.collection("devices").doc(deviceId).set({
        uid,
        lockedEmail: email,
        trialStartedAt: userData.trialStartedAt || admin.firestore.Timestamp.now(),
        linkedAt: admin.firestore.FieldValue.serverTimestamp(),
        fidHash: fidHash || null,
        fingerprintHash: fingerprintHash || null,
      });

      const batch = db.batch();
      if (fidHash) {
        batch.set(db.collection("device_fids").doc(fidHash), {
          primaryDeviceId: deviceId,
          linkedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }
      if (fingerprintHash) {
        batch.set(db.collection("device_fingerprints").doc(fingerprintHash), {
          primaryDeviceId: deviceId,
          linkedAt: admin.firestore.FieldValue.serverTimestamp(),
        });
      }
      await batch.commit();
      logger.info("Linked existing user email to new device", { uid, email, deviceId });
    }

    return {
      isPremium: premium,
      premiumExpiresAt,
      trialStartedAt,
      trialExpired,
      daysLeft: premium ? subscriptionDaysLeft : trialDaysLeft,
      deviceAlreadyUsed,
      subscriptionDaysLeft,
      isRegistered: true,
    };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logger.error("checkTrialStatus error", err);
    throw new HttpsError("internal", "Could not check status.");
  }
});
