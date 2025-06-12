package pack.simple_sock5;

import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SimpleSocks5ProxyServer {

    private static final int DEFAULT_PORT = 2200;
    private static final byte SOCKS_VERSION_5 = 0x05;
    private static final byte METHOD_NO_AUTHENTICATION_REQUIRED = 0x00;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN_NAME = 0x03;
    private static final byte ATYP_IPV6 = 0x04; // We'll parse but might not fully handle IPv6 connection if system doesn't support well

    private static final byte REP_SUCCEEDED = 0x00;
    private static final byte REP_GENERAL_SOCKS_SERVER_FAILURE = 0x01;
    private static final byte REP_CONNECTION_NOT_ALLOWED_BY_RULESET = 0x02;
    private static final byte REP_NETWORK_UNREACHABLE = 0x03;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CONNECTION_REFUSED = 0x05;
    private static final byte REP_COMMAND_NOT_SUPPORTED = 0x07;
    private static final byte REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    private final int port;
    private final ExecutorService executorService;

    public SimpleSocks5ProxyServer(int port) {
        this.port = port;
        this.executorService = Executors.newCachedThreadPool(); // Or a fixed-size pool
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("SOCKS5 Proxy Server started on port " + port);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected from: " + clientSocket.getRemoteSocketAddress());
                executorService.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executorService.shutdown();
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;

        public ClientHandler(Socket clientSocket) {
            this.clientSocket = clientSocket;
        }

        @Override
        public void run() {
            try (DataInputStream clientIn = new DataInputStream(clientSocket.getInputStream());
                 DataOutputStream clientOut = new DataOutputStream(clientSocket.getOutputStream())) {

                // 1. Version and Method Selection
                byte version = clientIn.readByte();
                if (version != SOCKS_VERSION_5) {
                    System.err.println("Unsupported SOCKS version: " + version);
                    return; // Or send an error reply, but SOCKS4/other clients might not understand SOCKS5 replies
                }

                byte nMethods = clientIn.readByte();
                byte[] methods = new byte[nMethods];
                clientIn.readFully(methods);

                boolean noAuthSupported = false;
                for (byte method : methods) {
                    if (method == METHOD_NO_AUTHENTICATION_REQUIRED) {
                        noAuthSupported = true;
                        break;
                    }
                }

                if (!noAuthSupported) {
                    System.err.println("Client does not support NO AUTHENTICATION method.");
                    // Send "NO ACCEPTABLE METHODS" (0xFF) - though client might not expect it
                    // clientOut.writeByte(SOCKS_VERSION_5);
                    // clientOut.writeByte((byte)0xFF);
                    // clientOut.flush();
                    return;
                }

                // Send server choice: SOCKS5, NO AUTHENTICATION
                clientOut.writeByte(SOCKS_VERSION_5);
                clientOut.writeByte(METHOD_NO_AUTHENTICATION_REQUIRED);
                clientOut.flush();
                System.out.println("SOCKS5 Handshake: Method NO AUTHENTICATION selected.");

                // 2. Client Request
                version = clientIn.readByte(); // Should be 0x05
                byte cmd = clientIn.readByte();
                byte rsv = clientIn.readByte(); // Reserved, should be 0x00
                byte atyp = clientIn.readByte();

                if (version != SOCKS_VERSION_5) {
                    System.err.println("Invalid SOCKS version in request: " + version);
                    sendErrorReply(clientOut, REP_GENERAL_SOCKS_SERVER_FAILURE, ATYP_IPV4, new byte[4], (short)0);
                    return;
                }
                if (cmd != CMD_CONNECT) {
                    System.err.println("Unsupported command: " + cmd);
                    sendErrorReply(clientOut, REP_COMMAND_NOT_SUPPORTED, ATYP_IPV4, new byte[4], (short)0);
                    return;
                }

                String destAddrStr;
                byte[] destAddrBytes;
                int destPort;

                if (atyp == ATYP_IPV4) {
                    destAddrBytes = new byte[4];
                    clientIn.readFully(destAddrBytes);
                    destAddrStr = InetAddress.getByAddress(destAddrBytes).getHostAddress();
                } else if (atyp == ATYP_DOMAIN_NAME) {
                    byte len = clientIn.readByte();
                    destAddrBytes = new byte[len];
                    clientIn.readFully(destAddrBytes);
                    destAddrStr = new String(destAddrBytes); // Default charset
                } else if (atyp == ATYP_IPV6) {
                    destAddrBytes = new byte[16];
                    clientIn.readFully(destAddrBytes);
                    destAddrStr = InetAddress.getByAddress(destAddrBytes).getHostAddress();
                    System.out.println("IPv6 Address received, handling may depend on system config: " + destAddrStr);
                }
                else {
                    System.err.println("Unsupported address type: " + atyp);
                    sendErrorReply(clientOut, REP_ADDRESS_TYPE_NOT_SUPPORTED, ATYP_IPV4, new byte[4], (short)0);
                    return;
                }

                destPort = clientIn.readUnsignedShort(); // Reads 2 bytes as unsigned short

                System.out.println("Client requests CONNECT to " + destAddrStr + ":" + destPort);

                // 3. Establish connection to destination
                Socket destSocket = null;
                try {
                    destSocket = new Socket(destAddrStr, destPort);
                    System.out.println("Connected to destination: " + destAddrStr + ":" + destPort);

                    // Send success reply
                    // VER | REP | RSV | ATYP | BND.ADDR | BND.PORT
                    clientOut.writeByte(SOCKS_VERSION_5);
                    clientOut.writeByte(REP_SUCCEEDED);
                    clientOut.writeByte((byte) 0x00); // RSV

                    // Use the address type and address of the socket that connected to the destination
                    // For simplicity, we can send 0.0.0.0 and port 0 if we don't want to expose local details
                    // or if resolving local address is complex. Many clients ignore BND.ADDR/BND.PORT.
                    // However, it's more correct to send the actual bound address.
                    InetAddress boundAddress = destSocket.getLocalAddress();
                    byte[] boundAddrBytes;
                    byte boundAtyp;

                    if (boundAddress instanceof Inet6Address) {
                        boundAtyp = ATYP_IPV6;
                        boundAddrBytes = boundAddress.getAddress();
                    } else { // Assume IPv4 for simplicity, including "anyLocalAddress"
                        boundAtyp = ATYP_IPV4;
                        // If boundAddress is 0.0.0.0, send that.
                        if (boundAddress.isAnyLocalAddress()) {
                            boundAddrBytes = new byte[]{0,0,0,0};
                        } else {
                            boundAddrBytes = boundAddress.getAddress();
                        }
                    }

                    clientOut.writeByte(boundAtyp);
                    clientOut.write(boundAddrBytes);
                    clientOut.writeShort(destSocket.getLocalPort());
                    clientOut.flush();

                    // 4. Relay data
                    Thread clientToDest = new Thread(new Relay(clientIn, destSocket.getOutputStream(), "ClientToDest"));
                    Thread destToClient = new Thread(new Relay(destSocket.getInputStream(), clientOut, "DestToClient"));

                    clientToDest.start();
                    destToClient.start();

                    // Wait for both relay threads to finish
                    clientToDest.join();
                    destToClient.join();

                } catch (UnknownHostException e) {
                    System.err.println("Host unreachable: " + destAddrStr + " - " + e.getMessage());
                    sendErrorReply(clientOut, REP_HOST_UNREACHABLE, atyp, destAddrBytes, (short)destPort);
                } catch (ConnectException e) {
                    System.err.println("Connection refused by " + destAddrStr + ":" + destPort + " - " + e.getMessage());
                    sendErrorReply(clientOut, REP_CONNECTION_REFUSED, atyp, destAddrBytes, (short)destPort);
                } catch (IOException e) {
                    System.err.println("Error connecting to destination or during relay: " + e.getMessage());
                    sendErrorReply(clientOut, REP_GENERAL_SOCKS_SERVER_FAILURE, atyp, destAddrBytes, (short)destPort);
                    // e.printStackTrace();
                } catch (InterruptedException e) {
                    System.err.println("Relay interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                } finally {
                    if (destSocket != null && !destSocket.isClosed()) {
                        try {
                            destSocket.close();
                        } catch (IOException e) { /* ignore */ }
                    }
                }

            } catch (EOFException e) {
                System.out.println("Client disconnected prematurely: " + clientSocket.getRemoteSocketAddress());
            } catch (IOException e) {
                System.err.println("IO error with client " + clientSocket.getRemoteSocketAddress() + ": " + e.getMessage());
                // e.printStackTrace();
            } finally {
                System.out.println("Closing connection for client: " + clientSocket.getRemoteSocketAddress());
                try {
                    clientSocket.close();
                } catch (IOException e) { /* ignore */ }
            }
        }

        private void sendErrorReply(DataOutputStream clientOut, byte replyCode, byte atyp, byte[] addr, short port) {
            try {
                clientOut.writeByte(SOCKS_VERSION_5);
                clientOut.writeByte(replyCode);
                clientOut.writeByte((byte) 0x00); // RSV
                clientOut.writeByte(atyp); // Original ATYP or a default like IPv4
                if (atyp == ATYP_DOMAIN_NAME) { // If original was domain, send length and domain
                    clientOut.writeByte((byte) addr.length);
                    clientOut.write(addr);
                } else { // For IPv4/IPv6, just send address bytes
                    clientOut.write(addr);
                }
                clientOut.writeShort(port);
                clientOut.flush();
            } catch (IOException e) {
                System.err.println("Error sending error reply: " + e.getMessage());
            }
        }
    }

    private static class Relay implements Runnable {
        private final InputStream in;
        private final OutputStream out;
        private final String name;
        private static final int BUFFER_SIZE = 4096;


        public Relay(InputStream in, OutputStream out, String name) {
            this.in = in;
            this.out = out;
            this.name = name;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            try {
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    out.flush();
                }
            } catch (SocketException e) {
                // Often "Socket closed" or "Connection reset" - normal when one side closes.
                System.out.println("Relay " + name + " socket exception: " + e.getMessage());
            } catch (IOException e) {
                System.err.println("Relay " + name + " error: " + e.getMessage());
                // e.printStackTrace();
            } finally {
                // System.out.println("Relay " + name + " finished.");
                // Closing streams here can sometimes cause issues if the other relay is still active
                // or if the main handler tries to close them. Sockets are closed by ClientHandler.
            }
        }
    }

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port number: " + args[0] + ". Using default port " + DEFAULT_PORT);
            }
        }
        SimpleSocks5ProxyServer server = new SimpleSocks5ProxyServer(port);
        server.start();
    }
}
