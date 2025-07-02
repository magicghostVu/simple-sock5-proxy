package pack.simple_sock5

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.socket.nio.NioServerSocketChannel
import org.slf4j.LoggerFactory
import pack.simple_sock5.auth.ConfigurableAuthenticator
import pack.simple_sock5.config.ServerConfig


fun main(vararg args: String) {
    val logger = LoggerFactory.getLogger("main")
    ServerConfig
    val bootstrap = ServerBootstrap()
    bootstrap.group(SharedEventLoop.eventLoopGroup)
    bootstrap.channel(NioServerSocketChannel::class.java)
    bootstrap.option(ChannelOption.SO_BACKLOG, 1024)
    bootstrap.option(ChannelOption.SO_REUSEADDR, true)
    val authenticator = ConfigurableAuthenticator()
    bootstrap.childHandler(ClientHandler(authenticator))
    bootstrap.bind(ServerConfig.DEPLOY_PORT)
    logger.info("sock5 proxy started at port {}", ServerConfig.DEPLOY_PORT)
}