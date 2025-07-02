package pack.simple_sock5.config

import java.io.File
import java.io.FileInputStream
import java.util.Properties

object ServerConfig {
    val DEPLOY_PORT: Int
    val PROXY_USERNAME: String
    val PROXY_PASSWORD: String
    val defaultReadLimit: Long
    val defaultWriteLimit: Long
    val userReadLimits: Map<String, Long>
    val userWriteLimits: Map<String, Long>

    init {
        val configFile = File("config/server.properties")
        val p = Properties()
        val fStream = FileInputStream(configFile)
        fStream.use {
            p.load(it)
        }
        DEPLOY_PORT = p.getProperty("deploy.port").toInt()
        PROXY_USERNAME = p.getProperty("proxy.username")
        PROXY_PASSWORD = p.getProperty("proxy.password")

        defaultReadLimit = p.getProperty("default.readLimit", "0").toLong()
        defaultWriteLimit = p.getProperty("default.writeLimit", "0").toLong()

        userReadLimits = p.stringPropertyNames()
            .filter { it.startsWith("user.") && it.endsWith(".readLimit") }
            .associate { it.substringAfter("user.").substringBefore(".readLimit") to p.getProperty(it).toLong() }
        userWriteLimits = p.stringPropertyNames()
            .filter { it.startsWith("user.") && it.endsWith(".writeLimit") }
            .associate { it.substringAfter("user.").substringBefore(".writeLimit") to p.getProperty(it).toLong() }
    }
}