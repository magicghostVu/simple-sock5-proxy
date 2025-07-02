package pack.simple_sock5.auth

interface Authenticator {
    suspend fun authenticate(user: String, pass: String): Boolean
}
