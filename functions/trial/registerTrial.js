/**
 * registerTrial — registers a new user's trial.
 * Checks device fingerprint to prevent trial abuse via multiple accounts.
 *
 * Requires authenticated user.
 * Expected data: { deviceId: string, fidHash: string }
 * Returns: { trialStartedAt, alreadyUsed, isPremium, premiumExpiresAt }
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { getDb } = require("../helpers/db");
const { isPremiumActive, TRIAL_DURATION_MS } = require("../helpers/premium");
const { healDeviceLockedEmail } = require("../helpers/deviceLock");

exports.registerTrial = onCall({ invoker: "public", enforceAppCheck: false }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Must be logged in.");
  }

  const db = getDb();
  const uid = request.auth.uid;
  const email = request.auth.token.email || "";
  const { deviceId, fidHash } = request.data;

  if (!deviceId || typeof deviceId !== "string") {
    throw new HttpsError("invalid-argument", "deviceId is required.");
  }

  try {
    // Check if user already has a record
    const userDoc = await db.collection("users").doc(uid).get();
    if (userDoc.exists) {
      const userData = userDoc.data();
      const trialStartedAt = userData.trialStartedAt?.toMillis() || 0;
      const trialExpiredAt = trialStartedAt + TRIAL_DURATION_MS;
      const isPremium = isPremiumActive(userData);
      const premiumExpiresAt = userData.premiumExpiresAt?.toMillis() || 0;
      const trialExpired = !isPremium && Date.now() > trialExpiredAt;
      const daysLeft = Math.max(0, Math.ceil((trialExpiredAt - Date.now()) / (24 * 60 * 60 * 1000)));
      const subscriptionDaysLeft = isPremium
        ? Math.max(0, Math.ceil((premiumExpiresAt - Date.now()) / (24 * 60 * 60 * 1000)))
        : 0;
      return {
        trialStartedAt,
        deviceAlreadyUsed: false,
        isPremium,
        premiumExpiresAt,
        trialExpired,
        daysLeft,
        subscriptionDaysLeft,
        isRegistered: true,
      };
    }

    // Check if this device already had a trial
    const deviceDoc = await db.collection("devices").doc(deviceId).get();
    if (deviceDoc.exists) {
      const deviceData = deviceDoc.data();
      const lockedEmail = await healDeviceLockedEmail(db, deviceId, deviceData, "registerTrial");

      if (lockedEmail && lockedEmail !== email) {
        throw new HttpsError("permission-denied", "DEVICE_LOCKED");
      }

      const trialStartedAt = deviceData.trialStartedAt?.toMillis() || 0;
      const trialExpiredAt = trialStartedAt + TRIAL_DURATION_MS;
      const isExpired = Date.now() > trialExpiredAt;

      // Create user record but link to existing trial
      await db.collection("users").doc(uid).set({
        email,
        deviceId,
        trialStartedAt: deviceData.trialStartedAt,
        isPremium: false,
        premiumExpiresAt: null,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });

      logger.info("Returning existing device trial", { uid, deviceId, isExpired });

      const trialDaysLeft = Math.max(0, Math.ceil((trialExpiredAt - Date.now()) / (24 * 60 * 60 * 1000)));
      return {
        trialStartedAt,
        deviceAlreadyUsed: isExpired,
        isPremium: false,
        premiumExpiresAt: 0,
        trialExpired: isExpired,
        daysLeft: isExpired ? 0 : trialDaysLeft,
        subscriptionDaysLeft: 0,
        isRegistered: true,
      };
    }

    // ── Email-based lookup (bypass prevention) ───────────────────────
    const existingByEmail = await db.collection("users")
      .where("email", "==", email)
      .limit(1)
      .get();

    if (!existingByEmail.empty) {
      const oldData = existingByEmail.docs[0].data();
      const trialStartedAt = oldData.trialStartedAt?.toMillis() || 0;

      await db.collection("users").doc(uid).set({
        email,
        deviceId,
        trialStartedAt: oldData.trialStartedAt || admin.firestore.Timestamp.now(),
        isPremium: oldData.isPremium || false,
        premiumExpiresAt: oldData.premiumExpiresAt || null,
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
        linkedFromPreviousUid: existingByEmail.docs[0].id,
      });

      logger.info("registerTrial: existing email found under old UID — inheriting trial", {
        uid,
        email,
        oldUid: existingByEmail.docs[0].id,
      });

      const inheritedIsPremium = isPremiumActive(oldData);
      const inheritedPremiumExpiresAt = oldData.premiumExpiresAt?.toMillis() || 0;
      const inheritedTrialExpiredAt = trialStartedAt + TRIAL_DURATION_MS;
      const inheritedDaysLeft = Math.max(0, Math.ceil((inheritedTrialExpiredAt - Date.now()) / (24 * 60 * 60 * 1000)));
      const inheritedSubscriptionDaysLeft = inheritedIsPremium
        ? Math.max(0, Math.ceil((inheritedPremiumExpiresAt - Date.now()) / (24 * 60 * 60 * 1000)))
        : 0;
      return {
        trialStartedAt,
        deviceAlreadyUsed: true,
        isPremium: inheritedIsPremium,
        premiumExpiresAt: inheritedPremiumExpiresAt,
        trialExpired: !inheritedIsPremium && Date.now() > inheritedTrialExpiredAt,
        daysLeft: inheritedIsPremium ? inheritedSubscriptionDaysLeft : inheritedDaysLeft,
        subscriptionDaysLeft: inheritedSubscriptionDaysLeft,
        isRegistered: true,
      };
    }

    // New device, new user — start fresh trial
    const now = admin.firestore.Timestamp.now();

    await db.collection("users").doc(uid).set({
      email,
      deviceId,
      trialStartedAt: now,
      isPremium: false,
      premiumExpiresAt: null,
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });

    await db.collection("devices").doc(deviceId).set({
      uid,
      lockedEmail: email,
      trialStartedAt: now,
      linkedAt: admin.firestore.FieldValue.serverTimestamp(),
      fidHash: fidHash || null,
    });

    if (fidHash) {
      await db.collection("device_fids").doc(fidHash).set({
        primaryDeviceId: deviceId,
        linkedAt: admin.firestore.FieldValue.serverTimestamp(),
      });
    }

    logger.info("New trial registered", { uid, email, deviceId });

    const newTrialDaysLeft = Math.ceil(TRIAL_DURATION_MS / (24 * 60 * 60 * 1000));
    return {
      trialStartedAt: now.toMillis(),
      deviceAlreadyUsed: false,
      isPremium: false,
      premiumExpiresAt: 0,
      trialExpired: false,
      daysLeft: newTrialDaysLeft,
      subscriptionDaysLeft: 0,
      isRegistered: true,
    };
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logger.error("registerTrial error", err);
    throw new HttpsError("internal", "Could not register trial.");
  }
});
