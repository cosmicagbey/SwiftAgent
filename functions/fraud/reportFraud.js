/**
 * reportFraud — submits a fraud report via email.
 *
 * Sends a fraud alert email to the MTN fraud team on behalf of the user.
 * Optionally attaches a base64-encoded image as evidence.
 *
 * Expected data: {
 *   fraudulentPhone: string,
 *   fraudType: string,
 *   description: string,
 *   reporterPhone?: string,
 *   datetime?: string,
 *   userEmail?: string,
 *   imageBase64?: string
 * }
 * Returns: { success: boolean, message: string }
 */

const { onCall, HttpsError } = require("firebase-functions/v2/https");
const nodemailer = require("nodemailer");
const admin = require("firebase-admin");
const { getDb } = require("../helpers/db");

// Helper to normalize Ghanaian phone numbers to 10-digit format (054XXXXXXX)
function normalizePhoneNumber(raw) {
  if (!raw) return "";
  let digits = raw.replace(/\D/g, "");
  if (digits.startsWith("233") && digits.length === 12) {
    digits = "0" + digits.substring(3);
  }
  return digits;
}

// Initialize nodemailer transporter
const transporter = nodemailer.createTransport({
  service: "gmail",
  auth: {
    user: process.env.EMAIL_USER || "your-email@gmail.com",
    pass: process.env.EMAIL_PASS || "your-app-password",
  },
});

exports.reportFraud = onCall({ invoker: "public", enforceAppCheck: false }, async (request) => {
  try {
    const data = request.data;

    // Validate required fields
    if (!data.fraudulentPhone || !data.fraudType || !data.description) {
      throw new HttpsError(
        "invalid-argument",
        "Missing required fields (fraudulentPhone, fraudType, description)."
      );
    }

    const db = getDb();
    const rawPhone = data.fraudulentPhone.trim();
    const normalizedPhone = normalizePhoneNumber(rawPhone);
    const reporterPhone = data.reporterPhone || "Not provided";
    const datetime = data.datetime || new Date().toISOString();
    const userEmail = data.userEmail || request.auth?.token?.email || "Not provided";
    const reporterUid = request.auth?.uid || "anonymous";

    // 1. Save broadcast to Firestore so all SwiftAgent users receive instant detection
    const broadcastRef = db.collection("fraud_broadcasts").doc();
    const broadcastData = {
      id: broadcastRef.id,
      phoneNumber: rawPhone,
      normalizedNumber: normalizedPhone,
      fraudType: data.fraudType,
      description: data.description,
      reporterEmail: userEmail,
      reporterPhone: reporterPhone,
      reporterUid: reporterUid,
      reportedAt: admin.firestore.FieldValue.serverTimestamp(),
      broadcastLevel: data.broadcastLevel || "CRITICAL",
      isVerified: true
    };

    await broadcastRef.set(broadcastData);

    // 2. Also attempt to send the email alert to the MTN fraud team
    try {
      const mailOptions = {
        from: `"Swift Agent Fraud Alert" <${process.env.EMAIL_USER || "your-email@gmail.com"}>`,
        to: "mmfraudteam.GH@mtn.com",
        subject: `Fraud Report: ${data.fraudType} (Number: ${rawPhone})`,
        text: `
A new fraud report has been broadcasted via the Swift Agent App.

Details:
-----------------------------------------
Reporter Email: ${userEmail}
Reporter Phone: ${reporterPhone}
Fraudulent Phone: ${rawPhone} (Normalized: ${normalizedPhone})
Type of Fraud: ${data.fraudType}
Date and Time of Incident: ${datetime}

Description:
${data.description}
-----------------------------------------

Please investigate this issue.
        `,
      };

      if (userEmail && userEmail !== "Not provided") {
        mailOptions.replyTo = userEmail;
      }

      if (data.imageBase64) {
        mailOptions.attachments = [
          {
            filename: "evidence.jpg",
            content: data.imageBase64.split("base64,")[1] || data.imageBase64,
            encoding: "base64",
          },
        ];
      }

      if (process.env.EMAIL_USER && process.env.EMAIL_PASS) {
        await transporter.sendMail(mailOptions);
      }
    } catch (emailErr) {
      console.warn("Email alert dispatch failed, but Firestore broadcast was saved:", emailErr.message);
    }

    return {
      success: true,
      broadcastId: broadcastRef.id,
      message: "Scammer number broadcasted successfully to all SwiftAgent users.",
    };
  } catch (error) {
    console.error("Error reporting fraud:", error);
    if (error instanceof HttpsError) throw error;
    throw new HttpsError("internal", "Failed to broadcast fraud report.", error.message);
  }
});
