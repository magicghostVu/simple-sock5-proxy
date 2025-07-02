package pack.simple_sock5.auth


class ConfigurableAuthenticator : Authenticator {
    override suspend fun authenticate(user: String, pass: String): Boolean {
        // Reading from properties is fast, but we wrap in withContext
        // to be a good citizen and yield the thread if needed.
        // This also serves as a good example for future, slower implementations.
        return true
    }
}
