const admin = require("firebase-admin");
const { getDb } = require("./db");
const { PLAN_DURATIONS } = require("./premium");

/**
 * Fix #2 — Shared payment processor.
 * Wraps the idempotency check, payment write, and premium extension inside a
 * single Firestore transaction so concurrent webhook + manual-verify calls
 * cannot double-extend a subscription (TOCTOU race condition).
 *
 * @param {string} reference - Paystack payment reference
 * @param {string} uid - Firebase user ID
 * @param {string} plan - Subscription plan key (e.g. "1month")
 * @param {object} txData - Verified Paystack transaction data object
 * @returns {Promise<{ alreadyProcessed: boolean }>}
 */
async function processSuccessfulPayment(reference, uid, plan, txData) {
  const db = getDb();
  return db.runTransaction(async (transaction) => {
    const paymentRef = db.collection("payments").doc(reference);
    const userRef    = db.collection("users").doc(uid);

    // Read both docs inside the transaction (atomic snapshot)
    const [paymentSnap, userSnap] = await Promise.all([
      transaction.get(paymentRef),
      transaction.get(userRef),
    ]);

    // Idempotency guard — only one concurrent caller can commit
    if (paymentSnap.exists && paymentSnap.data().status === "success") {
      return { alreadyProcessed: true };
    }

    // Calculate new premium expiry (extend from current if still active)
    const userData      = userSnap.exists ? userSnap.data() : {};
    const currentExpiry = userData.premiumExpiresAt?.toMillis() || 0;
    const baseTime      = currentExpiry > Date.now() ? currentExpiry : Date.now();
    const newExpiry     = baseTime + PLAN_DURATIONS[plan];

    // Write 1: mark payment as processed
    transaction.set(
      paymentRef,
      {
        status: "success",
        paidAt: txData.paid_at || null,
        channel: txData.channel || null,
        currency: txData.currency || "GHS",
        amount: txData.amount,
        customerEmail: txData.customer?.email || null,
        gatewayResponse: txData.gateway_response || null,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    // Write 2: activate / extend user premium
    transaction.set(
      userRef,
      {
        isPremium: true,
        premiumExpiresAt: admin.firestore.Timestamp.fromMillis(newExpiry),
        lastPaymentRef: reference,
        lastPaymentPlan: plan,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    return { alreadyProcessed: false };
  });
}

module.exports = {
  processSuccessfulPayment,
};
