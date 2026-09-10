/**
 * lookupLinkedAccount — pre-auth device→email lookup.
 * Called BEFORE the user signs in (no Firebase auth token required).
 *
 * Tries device signals in priority order:
 *   1. Android ID (primary, backward-compat)
 *   2. Firebase Installation ID hash (reinstall-resilient)
 *
 * Expected data: { deviceId, fidHash }
 * Returns: { hasAccount, lockedEmail, matchedSignal }
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const { getDb } = require("../helpers/db");

exports.lookupLinkedAccount = onCall({ invoker: "public", enforceAppCheck: false }, async (request) => {
  const db = getDb();
  const { deviceId, fidHash } = request.data;

  if (!deviceId && !fidHash) {
    return { hasAccount: false, lockedEmail: null, matchedSignal: null };
  }

  /**
   * Helper: given a primary device doc ID, fetch and return the lockedEmail.
   */
  async function fetchLockedEmail(primaryDeviceId) {
    if (!primaryDeviceId) return null;
    const doc = await db.collection("devices").doc(primaryDeviceId).get();
    if (!doc.exists) return null;
    return doc.data().lockedEmail || null;
  }

  try {
    // ── Signal 1: Android ID (primary, fastest) ──────────────────
    if (deviceId) {
      const deviceDoc = await db.collection("devices").doc(deviceId).get();
      if (deviceDoc.exists && deviceDoc.data().lockedEmail) {
        const lockedEmail = deviceDoc.data().lockedEmail;
        logger.info("lookupLinkedAccount: matched by androidId", { deviceId });
        return { hasAccount: true, lockedEmail, matchedSignal: "androidId" };
      }
    }

    // ── Signal 2: Firebase Installation ID hash ──────────────────
    if (fidHash) {
      const fidDoc = await db.collection("device_fids").doc(fidHash).get();
      if (fidDoc.exists) {
        const lockedEmail = await fetchLockedEmail(fidDoc.data().primaryDeviceId);
        if (lockedEmail) {
          logger.info("lookupLinkedAccount: matched by FID hash", { fidHash: fidHash.slice(0, 8) });
          return { hasAccount: true, lockedEmail, matchedSignal: "fid" };
        }
      }
    }

    // No signal matched
    return { hasAccount: false, lockedEmail: null, matchedSignal: null };
  } catch (err) {
    logger.error("lookupLinkedAccount error", err);
    // Fail safely — never block the sign-in flow
    return { hasAccount: false, lockedEmail: null, matchedSignal: null };
  }
});
