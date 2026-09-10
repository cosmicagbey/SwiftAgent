const TRIAL_DURATION_MS = 60 * 24 * 60 * 60 * 1000; // 60 days (2 months)

const PLAN_DURATIONS = {
  "1month": 30 * 24 * 60 * 60 * 1000,
  "3months": 90 * 24 * 60 * 60 * 1000,
};

const PLAN_AMOUNTS = {
  "1month": 700, // GHS 7.00
  "3months": 1500, // GHS 15.00
};

function isPremiumActive(userData) {
  if (!userData.isPremium) return false;
  const expiresAt = userData.premiumExpiresAt?.toMillis() || 0;
  return expiresAt > Date.now();
}

module.exports = {
  TRIAL_DURATION_MS,
  PLAN_DURATIONS,
  PLAN_AMOUNTS,
  isPremiumActive,
};
