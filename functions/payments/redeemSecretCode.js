/**
 * redeemSecretCode — redeems a one-time secret code for premium access.
 *
 * Requires authenticated user.
 * Expected data: { code: string }
 * Returns: { success: boolean, message: string }
 *
 * Uses a Firestore transaction to atomically:
 *   1. Validate the code exists and has not been used.
 *   2. Mark the code as used.
 *   3. Extend the user's premium subscription.
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { getDb } = require("../helpers/db");
const { PLAN_DURATIONS } = require("../helpers/premium");

exports.redeemSecretCode = onCall({ invoker: "public", enforceAppCheck: false }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Must be logged in.");
  }

  const db = getDb();
  const uid = request.auth.uid;
  const { code } = request.data;

  const normalizedCode = code ? code.trim().toUpperCase() : "";

  if (!normalizedCode) {
    throw new HttpsError("invalid-argument", "Secret code is required.");
  }

  try {
    return await db.runTransaction(async (transaction) => {
      const codeRef = db.collection("secret_codes").doc(normalizedCode);
      const userRef = db.collection("users").doc(uid);

      // ── ALL READS FIRST (Firestore transaction requirement) ──────────
      const codeDoc = await transaction.get(codeRef);
      const userDoc = await transaction.get(userRef);

      if (!codeDoc.exists) {
        throw new HttpsError("not-found", "Invalid secret code.");
      }

      const codeData = codeDoc.data();
      if (codeData.used) {
        throw new HttpsError("already-exists", "This code has already been used.");
      }

      const plan = codeData.plan;
      if (!plan || !PLAN_DURATIONS[plan]) {
        throw new HttpsError("internal", "This code has an invalid plan associated with it.");
      }

      const userData = userDoc.exists ? userDoc.data() : {};
      const currentExpiry = userData.premiumExpiresAt?.toMillis() || 0;
      const baseTime = currentExpiry > Date.now() ? currentExpiry : Date.now();
      const newExpiry = baseTime + PLAN_DURATIONS[plan];

      // ── ALL WRITES AFTER ALL READS ───────────────────────────────────
      // Mark the code as used
      transaction.update(codeRef, {
        used: true,
        usedBy: uid,
        usedAt: admin.firestore.FieldValue.serverTimestamp(),
      });

      // Update user's premium status
      transaction.set(
        userRef,
        {
          isPremium: true,
          premiumExpiresAt: admin.firestore.Timestamp.fromMillis(newExpiry),
          lastPaymentRef: `CODE-${normalizedCode}`,
          lastPaymentPlan: plan,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        },
        { merge: true }
      );

      logger.info("Secret code redeemed successfully", { uid, code, plan });
      return { success: true, message: "Code redeemed successfully. Your account is now active!" };
    });
  } catch (err) {
    if (err instanceof HttpsError) throw err;
    logger.error("redeemSecretCode error", err);
    throw new HttpsError("internal", "Could not redeem secret code.");
  }
});
