/**
 * initializeTransaction — creates a Paystack transaction for a subscription plan.
 *
 * Requires authenticated user.
 * Expected data: { plan: "1month" | "3months" | "6months" | "1year" }
 * Returns: { accessCode, reference, authorizationUrl }
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const { getDb } = require("../helpers/db");
const { PLAN_AMOUNTS } = require("../helpers/premium");

const paystackSecret = defineSecret("PAYSTACK_SECRET_KEY");

exports.initializeTransaction = onCall(
  { invoker: "public", enforceAppCheck: false, secrets: [paystackSecret] },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError("unauthenticated", "Must be logged in.");
    }

    const db = getDb();
    const uid = request.auth.uid;
    const email = request.auth.token.email || "";
    const { plan } = request.data;

    // Validate plan
    if (!plan || !PLAN_AMOUNTS[plan]) {
      throw new HttpsError(
        "invalid-argument",
        `Invalid plan. Must be one of: ${Object.keys(PLAN_AMOUNTS).join(", ")}`
      );
    }

    const amountInPesewas = PLAN_AMOUNTS[plan];
    const secretKey = paystackSecret.value();

    try {
      const response = await fetch("https://api.paystack.co/transaction/initialize", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${secretKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          email,
          amount: amountInPesewas,
          currency: "GHS",
          channels: ["mobile_money", "card"],
          metadata: {
            uid,
            plan,
            app: "momoswift",
            cancel_action: "https://momoswift.app",
          },
        }),
      });

      const result = await response.json();

      if (!result.status) {
        logger.error("Paystack initialize failed", result);
        throw new HttpsError("internal", result.message || "Failed to initialize transaction.");
      }

      logger.info("Transaction initialized", {
        reference: result.data.reference,
        email,
        plan,
        amount: amountInPesewas,
      });

      // Store pending payment
      await db.collection("payments").doc(result.data.reference).set({
        uid,
        email,
        plan,
        amount: amountInPesewas,
        currency: "GHS",
        reference: result.data.reference,
        status: "pending",
        createdAt: admin.firestore.FieldValue.serverTimestamp(),
      });

      return {
        accessCode: result.data.access_code,
        reference: result.data.reference,
        authorizationUrl: result.data.authorization_url,
      };
    } catch (err) {
      if (err instanceof HttpsError) throw err;
      logger.error("initializeTransaction error", err);
      throw new HttpsError("internal", "Could not reach payment provider.");
    }
  }
);
