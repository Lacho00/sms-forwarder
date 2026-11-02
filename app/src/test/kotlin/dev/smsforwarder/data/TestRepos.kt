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
