const logger = require("firebase-functions/logger");
const { getDb } = require("./db");

/**
 * Resolves the lockedEmail for a device. If lockedEmail is missing
 * but the device has an owner uid, self-heals by fetching the owner's
 * profile and backfilling the lockedEmail field.
 *
 * @param {FirebaseFirestore.Firestore} db - Firestore instance
 * @param {string} deviceId
 * @param {object} deviceData - The data snapshot of the device document
 * @param {string} [callerName] - Optional context label for log messages
 * @returns {Promise<string|null>} The locked email address or null.
 */
async function healDeviceLockedEmail(db, deviceId, deviceData, callerName = "unknown") {
  if (!deviceData) return null;
  let lockedEmail = deviceData.lockedEmail || null;

  if (!lockedEmail && deviceData.uid) {
    const originalOwnerDoc = await db.collection("users").doc(deviceData.uid).get();
    if (originalOwnerDoc.exists && originalOwnerDoc.data().email) {
      lockedEmail = originalOwnerDoc.data().email;
      await db.collection("devices").doc(deviceId).update({ lockedEmail });
      logger.info(`Self-healed ${callerName}: backfilled missing lockedEmail for device`, {
        deviceId,
        lockedEmail,
      });
    }
  }

  return lockedEmail;
}

module.exports = {
  healDeviceLockedEmail,
};
