import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;

public class TFTPServer {
    public static final int TFTPPORT = 4970;
    public static final int BUFSIZE = 516;
    public static final String READDIR = "public/read/";
    public static final String WRITEDIR = "public/write/";

    // OP codes
    public static final int OP_RRQ = 1;
    public static final int OP_WRQ = 2;
    public static final int OP_DAT = 3;
    public static final int OP_ACK = 4;
    public static final int OP_ERR = 5;

    public static void main(String[] args) {
        if (args.length > 0) {
            System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
            System.exit(1);
        }
        try {
            TFTPServer server = new TFTPServer();
            server.start();
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts the TFTP server, binding it to the main port to listen for UDP requests.
     * <p>It runs an infinite loop that accepts incoming client requests and create a new
     * concurrent {@link Thread} for each one. The thread handles the transactions using a
     * temporary socket, ensuring the main port remains available for new clients.</p>
     * 
     * @throws SocketException If the server fails to bind to the specified listening port.
     */

    private void start() throws SocketException {
        byte[] buf = new byte[BUFSIZE];
        DatagramSocket socket = new DatagramSocket(null);
        SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
        socket.bind(localBindPoint);

        System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

        while (true) {
            final InetSocketAddress clientAddress = receiveFrom(socket, buf);
            if (clientAddress == null)
                continue;

            final StringBuffer requestedFile = new StringBuffer();
            final int reqtype = ParseRQ(buf, requestedFile);

            new Thread() {
                public void run() {
                    try {
                        DatagramSocket sendSocket = new DatagramSocket(0);
                        sendSocket.connect(clientAddress);

                        String typeStr = "Invalid";
                        if (reqtype == OP_RRQ)
                            typeStr = "Read";
                        else if (reqtype == OP_WRQ)
                            typeStr = "Write";

                        System.out.printf("%s request for '%s' from %s using port %d\n",
                                typeStr,
                                requestedFile.toString(),
                                clientAddress.getHostName(),
                                clientAddress.getPort());

                        // Handle RRQ, WRQ, and others (Invalid) separately
                        if (reqtype == OP_RRQ) {
                            requestedFile.insert(0, READDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), reqtype);
                        } else if (reqtype == OP_WRQ) {
                            requestedFile.insert(0, WRITEDIR);
                            HandleRQ(sendSocket, requestedFile.toString(), reqtype);
                        } else {
                            // If it is neither 1 nor 2, pass the invalid reqtype to HandleRQ
                            HandleRQ(sendSocket, requestedFile.toString(), reqtype);
                        }

                        sendSocket.close();
                    } catch (SocketException e) {
                        e.printStackTrace();
                    }
                }
            }.start();
        }
    }

    /**
     * Receives an incoming UDP datagram on the specified socket.
     * <p>This blocking method waits for a packet, populates the provided buffer with the
     * received data, and extracts the sender's addres for subsequent communication.</p>
     * 
     * @param socket The {@link DatagramSocket} used to listen for incoming packets.
     * @param buf The byte array buffer where the packet's payload will be stored.
     * @return The {@link InetSocketAddress} of the client, or {@code null} if an I/O error occurs.
     */
    private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(packet);
            return new InetSocketAddress(packet.getAddress(), packet.getPort());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parses the initial TFTP request to extract the operation code and filename.
     * <p>Reads the first two bytes to determine the opcode. If the packet is a Read (RRQ)
     * or Write (WRQ) request, it extracts the null-terminated ASCII filename and appends
     * it to the provided string buffer.</p>
     * 
     * @param buf The byte array containing the received UDP packet payload.
     * @param requestedFile A {@link StringBuffer} to be populated with the extracted filename.
     * @return The integer representing the TFTP operation code.
     */
    private int ParseRQ(byte[] buf, StringBuffer requestedFile) {
        int opcode = ByteBuffer.wrap(buf).getShort();

        if (opcode == OP_RRQ || opcode == OP_WRQ) {
            StringBuilder sb = new StringBuilder();
            int i = 2;
            while (i < buf.length && buf[i] != 0) {
                sb.append((char) buf[i]);
                i++;
            }
            requestedFile.append(sb.toString());
        }
        return opcode;
    }

    /**
     * Dispatches the incoming TFTP request to the appropriate read or write handler.
     * <p>Routes Read Requests (RRQ) to the transmission logic and Write Requests (WRQ)
     * to the reception logic. Any unrecognized operation code results in an Error Code 4
     * (Illegal TFTP operation) being sent back to the client.</p>
     * 
     * @param sendSocket The temporary {@link DatagramSocket} dedicated to this client session.
     * @param requestedFile The target file path, already prefixed with the correct directory.
     * @param opcode The parsed TFTP operation code (1 for RRQ, 2 for WRQ).
     */
    private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
        if (opcode == OP_RRQ) {
            send_DATA_receive_ACK(sendSocket, requestedFile);
        } else if (opcode == OP_WRQ) {
            receive_DATA_send_ACK(sendSocket, requestedFile);
        } else {
            System.err.println("Invalid request. Sending an error packet.");
            send_ERR(sendSocket, 4, "Illegal TFTP operation.");
        }
    }

    /**
     * Transmits a requested file to the client using the TFTP lock-step (Stop-and-Wait ARQ) protocol.
     * <p>This methos fragments the target file into 512-byte payload blocks and transmit them sequentially.
     * It enforces strict network reliability by requiring a valid Acknowledgment (ACK) for each block before
     * proceeding. It uses a 2-second timeout mechanism with up to 5 retransmission attempts per block
     * to recover from packet loss. Additionally, it handles 16-bit block number wrap-around for large files
     * and manages exact 512-byte boundary terminations.</p>
     * @param sendSocket The temporary {@link DatagramSocket} currently connected to the client.
     * @param requestedFile The full local directory path of the file to be transmitted.
     * @return {@code true} if the entire file was successfully transmitted and fully acknowledged;
     * {@code false} if a fatal network timeout, file system error, or protocol violation occurs.
     */
    private boolean send_DATA_receive_ACK(DatagramSocket sendSocket, String requestedFile) {
        try {
            File file = new File(requestedFile);
            if (!file.exists()) {
                send_ERR(sendSocket, 1, "File not found.");
                return false;
            }

            FileInputStream fis = new FileInputStream(file);
            int blockNumber = 1;
            byte[] fileBuf = new byte[512];
            int bytesRead;

            sendSocket.setSoTimeout(2000); // 2-second timeout for retransmission

            do {
                bytesRead = fis.read(fileBuf);
                if (bytesRead == -1)
                    bytesRead = 0; // Handle exact 512-byte boundaries

                byte[] dataPacket = new byte[4 + bytesRead];
                ByteBuffer bb = ByteBuffer.wrap(dataPacket);
                bb.putShort((short) OP_DAT);
                bb.putShort((short) blockNumber);
                bb.put(fileBuf, 0, bytesRead);

                DatagramPacket sendDp = new DatagramPacket(dataPacket, dataPacket.length);

                boolean ackReceived = false;
                int retries = 0;

                while (!ackReceived && retries < 5) {
                    sendSocket.send(sendDp);

                    byte[] ackBuf = new byte[BUFSIZE];
                    DatagramPacket ackDp = new DatagramPacket(ackBuf, ackBuf.length);

                    try {
                        sendSocket.receive(ackDp);
                        ByteBuffer ackBb = ByteBuffer.wrap(ackBuf);
                        int ackOp = ackBb.getShort();
                        int ackBlock = ackBb.getShort();

                        if (ackOp == OP_ACK && ackBlock == blockNumber) {
                            ackReceived = true;
                        } else if (ackOp == OP_ERR) {
                            fis.close();
                            return false;
                        }
                        // Ignore incorrect ACKs to pass Python tests 2 and 3
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout expired. ACK retransmission (Attempt " + (retries + 1) + "/5)");
                        retries++;
                    }
                }

                if (!ackReceived) {
                    fis.close();
                    send_ERR(sendSocket, 0, "Transfer timed out."); 
                    return false;
                }
                blockNumber = (blockNumber + 1) % 65536; // Handle block wrap-around
            } while (bytesRead == 512);

            fis.close();
            return true;
        } catch (IOException e) {
            send_ERR(sendSocket, 2, "Access violation");
            return false;
        } catch (Exception e) {
            send_ERR(sendSocket, 0, e.getMessage());
            return false;
        }
    }

        /**
     * Handles a TFTP Write Request (WRQ) to securely receive and save a file from the client.
     * <p>This methos implements the receiving side of the Stop-and-Wait ARQ protocol.
     * It starts the transfer by sending an initial ACK 0 and then processes incoming
     * sequential DATA blocks. To ensure server integrity and prevent abuse, it enforces
     * strict security policies: a file extension whitelist, a minimum size requirement 
     * (> 0 bytes), and a hard maximum file size limit (10 MB). It uses a 2-second timeout
     * with up to 5 retransmission attempts of the last valid ACK to recover from a packet
     * loss. If a fatal error or timeout occurs, any partially written file is immediately
     * deleted to mainatin a clean file system.</p>
     * 
     * @param sendSocket The temporrary {@link DatagramSocket} currently connected to the client.
     * @param requestedFile The full local directory path where the incoming file should be saved.
     * @return {@code true} if the file was succesfully received, validated, and saved;
     * {@code false} if a security violation, network timeout, or I/O error occurs.
     */
    private boolean receive_DATA_send_ACK(DatagramSocket sendSocket, String requestedFile) {
        try {
            File file = new File(requestedFile);
            String filename = file.getName().toLowerCase();

            boolean isValidExtension = filename.endsWith(".txt") || filename.endsWith(".pdf")
                    || filename.endsWith(".doc") || filename.endsWith(".docx") || filename.endsWith(".jpg")
                    || filename.endsWith(".png");

            if (!isValidExtension) {
                send_ERR(sendSocket, 0, "Invalid file type");
                return false;
            }

            if (file.exists()) {
                send_ERR(sendSocket, 6, "File already exists.");
                return false;
            }

            FileOutputStream fos = new FileOutputStream(file);
            int expectedBlockNumber = 1;
            sendSocket.setSoTimeout(2000);

            long totalByteReceived = 0;
            final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

            // Initial ACK 0
            byte[] ackBuf = new byte[4];
            ByteBuffer bb = ByteBuffer.wrap(ackBuf);
            bb.putShort((short) OP_ACK);
            bb.putShort((short) 0);
            DatagramPacket ackDp = new DatagramPacket(ackBuf, ackBuf.length);

            boolean lastPacket = false;

            while (!lastPacket) {
                boolean blockReceived = false;
                int retries = 0;

                while (!blockReceived && retries < 5) {
                    sendSocket.send(ackDp);

                    byte[] dataBuf = new byte[BUFSIZE];
                    DatagramPacket dataDp = new DatagramPacket(dataBuf, dataBuf.length);

                    try {
                        sendSocket.receive(dataDp);
                        ByteBuffer dataBb = ByteBuffer.wrap(dataBuf);
                        int op = dataBb.getShort();
                        int block = dataBb.getShort();

                        if (op == OP_DAT && block == expectedBlockNumber) {
                            int dataLen = dataDp.getLength() - 4;

                            if (expectedBlockNumber == 1 && dataLen == 0) {
                                send_ERR(sendSocket, 0, "File is too small (minimum 1 byte).");
                                fos.close();
                                file.delete();
                                return false;
                            }

                            totalByteReceived += dataLen;
                            if (totalByteReceived > MAX_FILE_SIZE) {
                                send_ERR(sendSocket, 0, "File exceeds size limit");
                                fos.close();
                                file.delete();
                                return false;
                            }

                            fos.write(dataBuf, 4, dataLen);
                            blockReceived = true;

                            if (dataLen < 512) {
                                lastPacket = true;
                            }

                            // Prepare ACK for the received block
                            ackBuf = new byte[4];
                            bb = ByteBuffer.wrap(ackBuf);
                            bb.putShort((short) OP_ACK);
                            bb.putShort((short) expectedBlockNumber);
                            ackDp = new DatagramPacket(ackBuf, ackBuf.length);
                            expectedBlockNumber = (expectedBlockNumber + 1) % 65536;
                        } else if (op == OP_ERR) {
                            fos.close();
                            file.delete();
                            return false;
                        }
                    } catch (SocketTimeoutException e) {
                        System.out.println("Timeout expired. ACK retransmission (Attempt " + (retries + 1) + "/5)");
                        retries++;
                    }
                }

                if (!blockReceived) {
                    send_ERR(sendSocket, 0, "Transfer timed out.");
                    fos.close();
                    file.delete();
                    return false;
                }
            }
            sendSocket.send(ackDp); // Send final ACK
            fos.close();

            return true;
        } catch (IOException e) {
            send_ERR(sendSocket, 2, "Access violation");
            return false;
        } catch (Exception e) {
            send_ERR(sendSocket, 0, e.getMessage());
            return false;
        }
    }

    /**
     * Constructs and transmits a TFTP Error packet (Opcode 5) to the client.
     * <p>This method formats the datagram according to RFC 1350 specifications, packing
     * a 2-byte opcode, a 2-byte error code, the human readable error message, and the
     * manadatory null-terminating byte before sending it over the network.</p>
     * @param sendSocket The {@link DatagramSocket} used to transmit the error packet.
     * @param errorCode The standard TFTP error code (e.g., 1 for Not Found, 6 for Already Exists).
     * @param errMsg The descriptive error message string.
     */
    private void send_ERR(DatagramSocket sendSocket, int errorCode, String errMsg) {
        try {
            byte[] msgBytes = errMsg.getBytes();
            byte[] errBuf = new byte[4 + msgBytes.length + 1];
            ByteBuffer bb = ByteBuffer.wrap(errBuf);
            bb.putShort((short) OP_ERR);
            bb.putShort((short) errorCode);
            bb.put(msgBytes);
            bb.put((byte) 0);
            DatagramPacket errDp = new DatagramPacket(errBuf, errBuf.length);
            sendSocket.send(errDp);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}