/**
 * Cloud Functions for Firebase — Swift Agent (J330)
 *
 * This file is the lazy-loading entry point.
 * Each function is only loaded from disk when it is first invoked,
 * reducing cold-start time for functions that are not frequently called.
 *
 * Endpoints:
 *   1. registerTrial        (onCall)    – registers a new user's trial
 *   2. checkTrialStatus     (onCall)    – checks trial/subscription status
 *   3. lookupLinkedAccount  (onCall)    – pre-auth device→email lookup
 *   4. initializeTransaction(onCall)    – creates a Paystack transaction
 *   5. paystackWebhook      (onRequest) – receives Paystack payment events
 *   6. verifyTransactionManual(onCall)  – manual payment verification
 *   7. redeemSecretCode     (onCall)    – secret code redemption
 *   8. reportFraud          (onCall)    – fraud report via email
 *
 * See: https://paystack.com/docs/api/transaction/
 */

// Initialize Firebase Admin SDK once, at the entry point.
const admin = require("firebase-admin");
admin.initializeApp();

// ─── Trial functions ────────────────────────────────────────────────
Object.defineProperty(exports, "registerTrial", {
  enumerable: true,
  get: () => require("./trial/registerTrial").registerTrial,
});

Object.defineProperty(exports, "checkTrialStatus", {
  enumerable: true,
  get: () => require("./trial/checkTrialStatus").checkTrialStatus,
});

Object.defineProperty(exports, "lookupLinkedAccount", {
  enumerable: true,
  get: () => require("./trial/lookupLinkedAccount").lookupLinkedAccount,
});

// ─── Payment functions ──────────────────────────────────────────────
Object.defineProperty(exports, "initializeTransaction", {
  enumerable: true,
  get: () => require("./payments/initializeTransaction").initializeTransaction,
});

Object.defineProperty(exports, "paystackWebhook", {
  enumerable: true,
  get: () => require("./payments/paystackWebhook").paystackWebhook,
});

Object.defineProperty(exports, "verifyTransactionManual", {
  enumerable: true,
  get: () => require("./payments/verifyTransactionManual").verifyTransactionManual,
});

Object.defineProperty(exports, "redeemSecretCode", {
  enumerable: true,
  get: () => require("./payments/redeemSecretCode").redeemSecretCode,
});

// ─── Fraud functions ────────────────────────────────────────────────
Object.defineProperty(exports, "reportFraud", {
  enumerable: true,
  get: () => require("./fraud/reportFraud").reportFraud,
});
