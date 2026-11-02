package dev.smsforwarder.data

class FakeDestinationSecretStore : DestinationSecretStore {
    val map = mutableMapOf<Long, DestinationSecrets>()
    var failOnSet = false

    override suspend fun get(destinationId: Long): DestinationSecrets? = map[destinationId]

    override suspend fun set(destinationId: Long, s: DestinationSecrets) {
        if (failOnSet) throw java.security.GeneralSecurityException("set failed")
        map[destinationId] = s
    }

    override suspend fun remove(destinationId: Long) {
        map.remove(destinationId)
    }
}
