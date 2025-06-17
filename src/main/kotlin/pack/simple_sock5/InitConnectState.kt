package pack.simple_sock5

import io.netty.buffer.ByteBuf
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.socket.SocketChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.InetAddress

sealed class InitConnectState {
    abstract val channel: SocketChannel
    abstract fun acceptBuffer(newBuffer: ByteBuf): InitConnectState
    protected val logger: Logger = LoggerFactory.getLogger("init-connect-state")
}

class ReadMethods(
    override val channel: SocketChannel,
    val buffer: CompositeByteBuf = Unpooled.compositeBuffer()
) : InitConnectState() {
    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {

        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }

        val originReadIndex = buffer.readerIndex()
        return if (buffer.isReadable(2)) {
            val sockVersion = buffer.readByte();
            if (sockVersion != InitConnectProxyHandler.SOCKS_VERSION_5) {
                logger.warn("client send wrong sock version {}", sockVersion)
            }

            val numMethods = buffer.readByte().toInt()
            val methods = ByteArray(numMethods)

            logger.debug("num methods: {}", numMethods)

            if (buffer.readableBytes() >= methods.size) {
                buffer.readBytes(methods)

                logger.debug("done read methods {}", methods)

                var noAuthSupported = false
                for (b in methods) {
                    if (b == InitConnectProxyHandler.METHOD_NO_AUTHENTICATION_REQUIRED) {
                        noAuthSupported = true
                        break
                    }
                }
                if (!noAuthSupported) {
                    logger.warn("client not support no auth {}", methods)
                    channel.close()
                    throw IllegalStateException("client not support no auth")
                } else {
                    // response lại client và chuyển state sau???
                    val response = channel.alloc().buffer()
                    response.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
                    response.writeByte(InitConnectProxyHandler.METHOD_NO_AUTHENTICATION_REQUIRED.toInt())
                    channel.writeAndFlush(response)
                    logger.debug("response method")
                    logger.debug("buffer remaining: {}", buffer.readableBytes())
                    var newState: InitConnectState = VerifyConnectCommand(
                        channel,
                        buffer,
                    )
                    newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
                    newState
                }
            } else {
                // reset lại reader index ban đầu
                buffer.readerIndex(originReadIndex)
                this
            }
        } else {
            this
        }
    }
}

class VerifyConnectCommand(override val channel: SocketChannel, val buffer: CompositeByteBuf) : InitConnectState() {
    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }

        //nếu có đủ 4 byte thì xử lý
        return if (buffer.isReadable(4)) {
            val clientSockVersion = buffer.readByte()
            if (clientSockVersion != InitConnectProxyHandler.SOCKS_VERSION_5) {
                logger.warn("client {} send wrong version {}", channel.remoteAddress(), clientSockVersion)
                /*channel.close()
                throw IllegalStateException("client send wrong version sock")*/
            }
            val cmd = buffer.readByte()
            if (cmd != InitConnectProxyHandler.CMD_CONNECT) {
                logger.warn("client {} not send cmd connect, cmd is {}", channel, cmd)
                throw IllegalStateException("client not send cmd connect")
            }
            val reserve = buffer.readByte()
            val addressTypeCode = buffer.readByte()

            val addressType = ProxyAddressType.fromCode(addressTypeCode)
            if (addressType == null) {
                logger.warn("don't support address type code {}", addressTypeCode)
                throw IllegalStateException("don't support address type code")
            }
            logger.debug("done verify command")
            var newState: InitConnectState = ReadDestinationHost(
                channel,
                addressType,
                buffer,
            )
            newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
            newState
        } else this
    }
}

class ReadDestinationHost(
    override val channel: SocketChannel,
    val addressType: ProxyAddressType,
    val buffer: CompositeByteBuf
) : InitConnectState() {
    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }

        val originReadIndex = buffer.readerIndex()
        return when (addressType) {
            ProxyAddressType.IP_V4 -> {
                if (buffer.isReadable(4)) {
                    val arrayIp = ByteArray(4)
                    buffer.readBytes(arrayIp)
                    val hostName = InetAddress.getByAddress(arrayIp).hostName
                    var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer)
                    newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
                    newState
                } else {
                    this
                }
            }

            ProxyAddressType.IP_V6 -> {
                if (buffer.isReadable(16)) {
                    logger.warn("receive ip v6, try to parse")
                    val arrayIp = ByteArray(16)
                    buffer.readBytes(arrayIp)
                    val hostName = InetAddress.getByAddress(arrayIp).hostName
                    var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer)
                    newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
                    newState
                } else {
                    this
                }
            }

            ProxyAddressType.DOMAIN_NAME -> {
                if (buffer.isReadable) {
                    val numByteForDomainName = buffer.readByte().toInt()

                    if (buffer.isReadable(numByteForDomainName)) {
                        val bufferForDomainName = ByteArray(numByteForDomainName)
                        buffer.readBytes(bufferForDomainName)

                        val hostName = String(bufferForDomainName)
                        var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer)
                        newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
                        newState
                    }
                    // reset read index and wait next buffer
                    else {
                        buffer.readerIndex(originReadIndex)
                        this
                    }
                } else {
                    this
                }
            }
        }

    }
}

class ReadDestinationPort(
    override val channel: SocketChannel,
    val hostName: String,
    val buffer: CompositeByteBuf
) : InitConnectState() {

    var targetPort: Int = -1;

    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }
        if (buffer.isReadable(2)) {
            targetPort = buffer.readUnsignedShort()
        }
        return this
    }
}

enum class ProxyAddressType(val byteCode: Byte) {
    IP_V4(InitConnectProxyHandler.ATYP_IPV4),
    IP_V6(InitConnectProxyHandler.ATYP_IPV6),
    DOMAIN_NAME(InitConnectProxyHandler.ATYP_DOMAIN_NAME);

    companion object {
        fun fromCode(code: Byte): ProxyAddressType? {
            return entries.asSequence().firstOrNull { it.byteCode == code }
        }
    }
}
