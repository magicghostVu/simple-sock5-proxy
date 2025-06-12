package pack.simple_sock5

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.util.ResourceLeakDetector
import org.slf4j.LoggerFactory
import pack.simple_sock5.config.ServerConfig


fun main(vararg args: String) {
    //ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID)
    val logger = LoggerFactory.getLogger("main")
    ServerConfig
    val server = ServerBootstrap()
    server.group(SharedEventLoop.eventLoopGroup)
    server.channel(NioServerSocketChannel::class.java)
    server.option(ChannelOption.SO_BACKLOG, 1024)
    server.option(ChannelOption.SO_REUSEADDR, true)
    server.childHandler(ClientHandler())
    server.bind(ServerConfig.DEPLOY_PORT)
    logger.info("proxy started at port {}", ServerConfig.DEPLOY_PORT)
}