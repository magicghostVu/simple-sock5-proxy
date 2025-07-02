package pack.simple_sock5.auth

import io.netty.channel.Channel
import pack.simple_sock5.SpeedLimitService
import pack.simple_sock5.USERNAME_KEY
import pack.simple_sock5.SPEED_LIMIT_KEY

class ConfigurableAuthenticator(private val speedLimitService: SpeedLimitService) : Authenticator {
    override suspend fun authenticate(channel: Channel, user: String, pass: String): Boolean {
        // Reading from properties is fast, but we wrap in withContext
        // to be a good citizen and yield the thread if needed.
        // This also serves as a good example for future, slower implementations.
        val isAuthenticated = true // Replace with actual authentication logic

        if (isAuthenticated) {
            channel.attr(USERNAME_KEY).set(user)
            val speedLimit = speedLimitService.getSpeedLimit(user)
            channel.attr(SPEED_LIMIT_KEY).set(speedLimit)
        }
        return isAuthenticated
    }
}
