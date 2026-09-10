const admin = require("firebase-admin");

if (!admin.apps.length) {
  admin.initializeApp();
}
const db = admin.firestore();

function getDb() {
  return db;
}

module.exports = {
  admin,
  db,
  getDb,
};
