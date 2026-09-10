/**
 * paystackWebhook — HTTPS endpoint for Paystack to call on payment events.
 *
 * Verifies the HMAC-SHA512 signature from Paystack, verifies the transaction
 * against Paystack's API, and activates the user's premium subscription atomically.
 *
 * Fix #3: Uses req.rawBody for HMAC to avoid re-serialisation drift.
 * Fix #2: Uses processSuccessfulPayment (Firestore transaction) to prevent double-extension.
 * Fix #17: Returns 500 on transient errors so Paystack retries; 200 on permanent data errors.
 */

const { onRequest } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const crypto = require("crypto");
const { getDb } = require("../helpers/db"); // used for payment/user lookups before the atomic tx
const { PLAN_AMOUNTS } = require("../helpers/premium");
const { processSuccessfulPayment } = require("../helpers/paymentProcessor");

const paystackSecret = defineSecret("PAYSTACK_SECRET_KEY");

exports.paystackWebhook = onRequest({ secrets: [paystackSecret] }, async (req, res) => {
  if (req.method !== "POST") {
    res.status(405).send("Method Not Allowed");
    return;
  }

  const secretKey = paystackSecret.value();

  // Verify Paystack signature
  const signature = req.headers["x-paystack-signature"];
  // Fix #3: Use the original raw bytes for HMAC — re-serialising req.body can differ from the wire bytes
  const rawBody = req.rawBody;
  const expectedSignature = crypto
    .createHmac("sha512", secretKey)
    .update(rawBody)
    .digest("hex");

  if (signature !== expectedSignature) {
    logger.warn("Invalid Paystack webhook signature");
    res.status(401).send("Invalid signature");
    return;
  }

  const event = req.body;
  logger.info("Paystack webhook received", {
    event: event.event,
    reference: event.data?.reference,
  });

  if (event.event !== "charge.success") {
    res.status(200).send("OK");
    return;
  }

  const reference = event.data?.reference;
  if (!reference) {
    res.status(400).send("Missing reference");
    return;
  }

  const db = getDb();

  try {
    // Verify with Paystack API
    const verifyResponse = await fetch(
      `https://api.paystack.co/transaction/verify/${encodeURIComponent(reference)}`,
      {
        method: "GET",
        headers: { "Authorization": `Bearer ${secretKey}` },
      }
    );

    const verifyResult = await verifyResponse.json();

    if (!verifyResult.status || verifyResult.data?.status !== "success") {
      logger.warn("Transaction verification failed", { reference });
      res.status(200).send("Verification failed");
      return;
    }

    const txData = verifyResult.data;

    // Get payment record to find uid and plan
    const paymentDoc = await db.collection("payments").doc(reference).get();
    const paymentData = paymentDoc.exists ? paymentDoc.data() : null;

    const uid = paymentData?.uid || txData.metadata?.uid;
    const plan = paymentData?.plan || txData.metadata?.plan;

    // Fix #17: Permanent data errors return 200 (Paystack should NOT retry these)
    if (!uid) {
      logger.warn("Webhook: missing uid in payment metadata — skipping", { reference });
      res.status(200).send("Missing uid");
      return;
    }

    // BILLING GUARD: fast-path early exit before the transaction (idempotency also inside tx)
    if (paymentData?.status === "success") {
      logger.info("Webhook already processed successfully.", { reference });
      res.status(200).send("Already processed");
      return;
    }

    // SECURITY PATCH: Verify the paid amount matches the plan's cost
    if (!plan || !PLAN_AMOUNTS[plan]) {
      logger.warn("Webhook processing failed - invalid plan", { reference, plan });
      res.status(200).send("Invalid plan");
      return;
    }
    const expectedAmount = PLAN_AMOUNTS[plan];
    if (txData.amount < expectedAmount) {
      logger.error("SECURITY ALERT: Price spoofing attempt detected", { reference, expectedAmount, actualAmount: txData.amount });
      res.status(200).send("Amount mismatch");
      return;
    }

    // Fix #2: Atomic Firestore transaction — prevents double-extension on concurrent webhook + manual-verify
    const { alreadyProcessed } = await processSuccessfulPayment(reference, uid, plan, txData);
    if (alreadyProcessed) {
      logger.info("Webhook: idempotency guard fired inside transaction", { reference });
    } else {
      logger.info("Webhook: user premium activated", { uid, plan });
    }

    res.status(200).send("OK");
  } catch (err) {
    logger.error("Webhook processing error", err);
    // Fix #17: Return 500 so Paystack retries on transient failures (Firestore lockup, timeout, etc.)
    res.status(500).send("Processing error");
  }
});
