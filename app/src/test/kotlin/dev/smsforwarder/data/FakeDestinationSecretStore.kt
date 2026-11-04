package dev.smsforwarder.data

class FakeDestinationSecretStore : DestinationSecretStore {
    val map = mutableMapOf<Long, DestinationSecrets>()
    var failOnSet = false
    var failOnGet = false
    var getFailure: Throwable? = null
    var setCalls = 0
    var getCalls = 0
    var hasSecretsFailure: Throwable? = null
    var hasSecretsThread: Thread? = null
    var removeFailsAfterSet = false
    var failOnNthSet: Int? = null
    var setFailure: Throwable? = null

    override suspend fun hasSecrets(destinationId: Long): Boolean {
        hasSecretsFailure?.let { throw it }
        hasSecretsThread = Thread.currentThread()
        return map.containsKey(destinationId)
    }

    override suspend fun get(destinationId: Long): DestinationSecrets? {
        getCalls++
        getFailure?.let { throw it }
        if (failOnGet) throw java.security.GeneralSecurityException("get failed")
        return map[destinationId]
    }

    override suspend fun set(destinationId: Long, s: DestinationSecrets) {
        setCalls++
        setFailure?.let { throw it }
        if (failOnNthSet == setCalls) throw java.security.GeneralSecurityException("set failed")
        if (failOnSet) throw java.security.GeneralSecurityException("set failed")
        map[destinationId] = s
    }

    override suspend fun remove(destinationId: Long) {
        if (removeFailsAfterSet && setCalls > 0) throw java.security.GeneralSecurityException("remove failed")
        map.remove(destinationId)
    }
}
