/**
 * verifyTransactionManual — verifies a Paystack transaction when user clicks "I have Paid".
 *
 * Requires authenticated user.
 * Expected data: { reference: string }
 * Returns: { success: boolean, message: string }
 *
 * Security patches applied:
 *   - IDOR / Ownership check: rejects requests where the transaction belongs to a different account.
 *   - Price spoofing check: rejects if the actual paid amount is less than the plan's expected cost.
 *   - Fix #2: Atomic Firestore transaction via processSuccessfulPayment to prevent double-extension.
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { getDb } = require("../helpers/db");
const { PLAN_DURATIONS, PLAN_AMOUNTS } = require("../helpers/premium");
const { processSuccessfulPayment } = require("../helpers/paymentProcessor");

const paystackSecret = defineSecret("PAYSTACK_SECRET_KEY");

exports.verifyTransactionManual = onCall(
  { invoker: "public", enforceAppCheck: false, secrets: [paystackSecret] },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in.");
    }

    const db = getDb();
    const uid = request.auth.uid;
    const { reference } = request.data;

    if (!reference) {
      throw new HttpsError("invalid-argument", "Missing reference.");
    }

    const secretKey = paystackSecret.value();

    try {
      logger.info(`Starting manual verification for reference: ${reference}`);
      const controller = new AbortController();
      const timeoutId = setTimeout(() => controller.abort(), 15000); // 15s timeout

      const verifyResponse = await fetch(
        `https://api.paystack.co/transaction/verify/${encodeURIComponent(reference)}`,
        {
          method: "GET",
          headers: { "Authorization": `Bearer ${secretKey}` },
          signal: controller.signal,
        }
      );
      clearTimeout(timeoutId);

      const verifyResult = await verifyResponse.json();
      logger.info(`Paystack verify response:`, verifyResult);

      if (!verifyResult.status || verifyResult.data?.status !== "success") {
        logger.info(`Payment not successful yet. Status: ${verifyResult.data?.status}`);
        return { success: false, message: "Payment has not been confirmed yet." };
      }

      const txData = verifyResult.data;
      logger.info(`Payment is successful on Paystack. Updating Firestore...`);

      // Get payment record to find plan
      const paymentDoc = await db.collection("payments").doc(reference).get();
      const paymentData = paymentDoc.exists ? paymentDoc.data() : null;
      const plan = paymentData?.plan || txData.metadata?.plan;
      const transactionUid = paymentData?.uid || txData.metadata?.uid;

      if (!plan || !PLAN_DURATIONS[plan] || !PLAN_AMOUNTS[plan]) {
        return { success: false, message: "Invalid plan attached to payment." };
      }

      // SECURITY PATCH 1: IDOR / Ownership Check
      if (transactionUid !== uid) {
        logger.error("SECURITY ALERT: Account spoofing attempt detected", {
          reference,
          expectedUid: transactionUid,
          requestingUid: uid,
        });
        return { success: false, message: "This transaction belongs to a different account." };
      }

      // SECURITY PATCH 2: Price Spoofing Check
      const expectedAmount = PLAN_AMOUNTS[plan];
      if (txData.amount < expectedAmount) {
        logger.error("SECURITY ALERT: Price spoofing attempt detected in manual verify", {
          reference,
          expectedAmount,
          actualAmount: txData.amount,
        });
        return { success: false, message: "Transaction amount does not match the selected plan." };
      }

      // Fix #2: Atomic Firestore transaction — prevents double-extension on concurrent webhook + manual-verify
      const { alreadyProcessed } = await processSuccessfulPayment(reference, uid, plan, txData);
      if (alreadyProcessed) {
        return { success: true, message: "Payment already processed." };
      }

      logger.info("Manual verification succeeded", { uid, plan, reference });
      return { success: true, message: "Payment verified successfully." };

    } catch (err) {
      if (err instanceof HttpsError) throw err;
      logger.error("verifyTransactionManual error", err);
      throw new HttpsError("internal", "Could not complete manual verification.");
    }
  }
);
