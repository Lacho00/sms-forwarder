package dev.smsforwarder.data

class FakeDestinationSecretStore : DestinationSecretStore {
    val map = mutableMapOf<Long, DestinationSecrets>()
    var failOnSet = false
    var failOnGet = false
    var getFailure: Throwable? = null
    var setCalls = 0
    var failOnNthSet: Int? = null

    override suspend fun get(destinationId: Long): DestinationSecrets? {
        getFailure?.let { throw it }
        if (failOnGet) throw java.security.GeneralSecurityException("get failed")
        return map[destinationId]
    }

    override suspend fun set(destinationId: Long, s: DestinationSecrets) {
        setCalls++
        if (failOnNthSet == setCalls) throw java.security.GeneralSecurityException("set failed")
        if (failOnSet) throw java.security.GeneralSecurityException("set failed")
        map[destinationId] = s
    }

    override suspend fun remove(destinationId: Long) {
        map.remove(destinationId)
    }
}
