package pack.simple_sock5

import io.netty.buffer.ByteBuf
import io.netty.buffer.CompositeByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.socket.SocketChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pack.simple_sock5.auth.Authenticator
import java.lang.ref.WeakReference
import java.net.InetAddress

sealed class InitConnectState(val authenticator: Authenticator) {
    abstract val channel: SocketChannel
    abstract fun acceptBuffer(newBuffer: ByteBuf): InitConnectState
    protected val logger: Logger = LoggerFactory.getLogger("init-connect-state")
}

class ReadMethods(
    override val channel: SocketChannel,
    val buffer: CompositeByteBuf = Unpooled.compositeBuffer(),
    authenticator: Authenticator
) : InitConnectState(authenticator) {
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

                var authMethod: Byte = 0xFF.toByte() // No acceptable methods
                for (b in methods) {
                    if (b == InitConnectProxyHandler.METHOD_USERNAME_PASSWORD) {
                        authMethod = InitConnectProxyHandler.METHOD_USERNAME_PASSWORD
                        break
                    }
                    /*if (b == InitConnectProxyHandler.METHOD_NO_AUTHENTICATION_REQUIRED) {
                        authMethod = InitConnectProxyHandler.METHOD_NO_AUTHENTICATION_REQUIRED
                        // Don't break yet, prefer username/password if available
                    }*/
                }

                val response = channel.alloc().buffer(2)
                response.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
                response.writeByte(authMethod.toInt())
                channel.writeAndFlush(response)

                logger.debug("response method: {}", authMethod)
                logger.debug("buffer remaining: {}", buffer.readableBytes())

                return when (authMethod) {
                    InitConnectProxyHandler.METHOD_USERNAME_PASSWORD -> {
                        val newState: InitConnectState = ReadUserPass(channel, buffer, authenticator)
                        newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
                    }

                    else -> {
                        logger.warn("client not support supported user-password auth methods {}", methods)
                        channel.close()
                        throw IllegalStateException("client not support any supported auth methods")
                    }
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

class VerifyConnectCommand(override val channel: SocketChannel, val buffer: CompositeByteBuf, authenticator: Authenticator) : InitConnectState(authenticator) {
    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }

        //nếu có đủ 4 byte thì xử lý
        return if (buffer.isReadable(4)) {
            val clientSockVersion = buffer.readByte()
            if (clientSockVersion != InitConnectProxyHandler.SOCKS_VERSION_5) {
                logger.warn("client {} send wrong version {}", channel.remoteAddress(), clientSockVersion)
                val response = channel.alloc().buffer(2)
                response.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
                response.writeByte(InitConnectProxyHandler.REP_GENERAL_SOCKS_SERVER_FAILURE.toInt())
                channel.writeAndFlush(response).addListener { channel.close() }
                return this
            }
            val cmd = buffer.readByte()
            if (cmd != InitConnectProxyHandler.CMD_CONNECT) {
                logger.warn("client {} not send cmd connect, cmd is {}", channel, cmd)
                val response = channel.alloc().buffer(2)
                response.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
                response.writeByte(InitConnectProxyHandler.REP_COMMAND_NOT_SUPPORTED.toInt())
                channel.writeAndFlush(response).addListener { channel.close() }
                return this
            }
            val reserve = buffer.readByte()
            val addressTypeCode = buffer.readByte()

            val addressType = ProxyAddressType.fromCode(addressTypeCode)
            if (addressType == null) {
                logger.warn("don't support address type code {}", addressTypeCode)
                val response = channel.alloc().buffer(2)
                response.writeByte(InitConnectProxyHandler.SOCKS_VERSION_5.toInt())
                response.writeByte(InitConnectProxyHandler.REP_ADDRESS_TYPE_NOT_SUPPORTED.toInt())
                channel.writeAndFlush(response).addListener { channel.close() }
                return this
            }
            logger.debug("done verify command")
            var newState: InitConnectState = ReadDestinationHost(
                channel,
                addressType,
                buffer,
                authenticator
            )
            newState = newState.acceptBuffer(Unpooled.EMPTY_BUFFER)
            newState
        } else this
    }
}

class ReadDestinationHost(
    override val channel: SocketChannel,
    val addressType: ProxyAddressType,
    val buffer: CompositeByteBuf,
    authenticator: Authenticator
) : InitConnectState(authenticator) {
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
                    var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer, authenticator)
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
                    var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer, authenticator)
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
                        var newState: InitConnectState = ReadDestinationPort(channel, hostName, buffer, authenticator)
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
    val buffer: CompositeByteBuf,
    authenticator: Authenticator
) : InitConnectState(authenticator) {

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

class ReadUserPass(
    override val channel: SocketChannel,
    val buffer: CompositeByteBuf,
    authenticator: Authenticator
) : InitConnectState(authenticator) {


    private fun resetBufferIndexAndReturnSame(indexToReset: Int): InitConnectState {
        buffer.readerIndex(indexToReset)
        return this
    }

    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }

        val originReadIndex = buffer.readerIndex()
        return if (buffer.isReadable(2)) { // VER + ULEN
            val version = buffer.readByte()
            if (version != 0x01.toByte()) { // Username/Password Authentication Protocol version 1
                logger.warn("Unsupported authentication version: {}", version)
                channel.close()
                throw IllegalStateException("Unsupported authentication version")
            }

            val ulen = buffer.readByte().toInt()
            if (buffer.isReadable(ulen)) { // ULEN + UNAME
                val unameBytes = ByteArray(ulen)
                buffer.readBytes(unameBytes)
                val uname = String(unameBytes)

                if (buffer.isReadable(1)) { // PLEN
                    val plen = buffer.readByte().toInt()
                    if (buffer.isReadable(plen)) { // PLEN + PASSWD
                        val passwdBytes = ByteArray(plen)
                        buffer.readBytes(passwdBytes)
                        val passwd = String(passwdBytes)
                        AuthenticateUserPass(
                            channel,
                            uname,
                            passwd,
                            buffer,
                            authenticator
                        )
                    } else {
                        resetBufferIndexAndReturnSame(originReadIndex)
                    }
                } else {
                    resetBufferIndexAndReturnSame(originReadIndex)
                }
            } else {
                resetBufferIndexAndReturnSame(originReadIndex)
            }
        } else {
            this
        }
    }
}

class AuthenticateUserPass(
    override val channel: SocketChannel,
    val userName: String,
    val password: String,
    val buffer: CompositeByteBuf,
    authenticator: Authenticator
) : InitConnectState(authenticator) {


    init {
        val refPipeline = WeakReference(channel.pipeline())
        SharedEventLoop.shareScope.launch {
            try {
                val authenSuccess = authenticator.authenticate(channel,userName, password)
                val pipeline = refPipeline.get()
                pipeline?.fireUserEventTriggered(AuthenResult(authenSuccess))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("err while authenticate user $userName", e)
                refPipeline.get()?.fireUserEventTriggered(AuthenResult(false))
            }
        }
    }


    

    override fun acceptBuffer(newBuffer: ByteBuf): InitConnectState {
        if (newBuffer.isReadable) {
            buffer.addComponent(true, newBuffer)
        }
        return this
    }
}

