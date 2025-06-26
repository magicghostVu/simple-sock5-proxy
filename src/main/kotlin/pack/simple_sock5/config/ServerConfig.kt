package pack.simple_sock5.config

import java.io.File
import java.io.FileInputStream
import java.util.Properties

object ServerConfig {
    val DEPLOY_PORT: Int;
    val PROXY_USERNAME: String;
    val PROXY_PASSWORD: String;

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
    }
}