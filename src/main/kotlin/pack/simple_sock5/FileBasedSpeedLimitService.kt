package pack.simple_sock5

import pack.simple_sock5.config.ServerConfig

class FileBasedSpeedLimitService(private val serverConfig: ServerConfig) : SpeedLimitService {

    override suspend fun getSpeedLimit(username: String): SpeedLimit {

        val userReadLimit = serverConfig.userReadLimits[username]
        val userWriteLimit = serverConfig.userWriteLimits[username]

        return if (userReadLimit != null || userWriteLimit != null) {
            SpeedLimit(userReadLimit ?: serverConfig.defaultReadLimit, userWriteLimit ?: serverConfig.defaultWriteLimit)
        } else {
            SpeedLimit(serverConfig.defaultReadLimit, serverConfig.defaultWriteLimit)
        }
    }
}
