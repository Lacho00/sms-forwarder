package dev.smsforwarder.data

import dev.smsforwarder.core.Clock

/** Destination and rule repositories over a test database, sharing one fake secret store. */
class TestRepos(
    db: AppDatabase,
    clock: Clock = Clock { 1_800_000_000_000L },
    val secrets: FakeDestinationSecretStore = FakeDestinationSecretStore(),
) {
    val destinations = DestinationRepository(db, secrets, clock, "Primary")
    val rules = RuleRepository(db, destinations, clock)
}

/** Records the outcome on the SMS's first delivery, for tests that only have a single destination in play. */
suspend fun SmsRepository.recordAttemptForSms(smsId: Long, outcome: dev.smsforwarder.core.SendOutcome) =
    recordAttempt(deliveriesOf(smsId).first().id, outcome)
