# TFTP Server
Developed as part of the Computer Networks course during my Erasmus at Linnaeus University (Växjö, Sweden).

## Project Context
The goal was to build a fully functional, concurrent TFTP server from scratch using Java, adhering to the RFC 1350 standard.

## Overview
A concurrent TFTP (Trivial File Transfer Protocol) server implemented in Java according to the RFC 1350 specification. The server supports read (RRQ) and write (WRQ) requests in `octet` mode, handles large multi-block file transfers, and features a robust lock-step timeout and retransmission mechanism.

## Compilation
You can compile the server source code using:

```bash
find . -name "*.java" > sources.txt && javac @sources.txt 
```
or

```bash
javac *.java
```

## Running
To start the server, use the following command:

```bash
java TFTPServer
```

*Note: The server listend on port `4970` by default. Ensure that your working directory contains the following folder structure before starting the server:


* `public/read/`: directory containing files tha clients can download.
* `public/write/`: directory where client uploads will be stored. 

## How to test

### 1. Using a TFTP Client (macOS/Linux)
Launch the client from your terminal, forcing an IPV4 connection to the server:

```bash
tftp localhost 4970
```

Enable binary transfer mode: 

```bash
tftp> mode octet
```

#### Standard Requests

- **Read Request (Download a file)**: ensure the file exists in the `public/read/` directory first.

```bash
tftp> get <filename>
```

- **Write Request (Upload a file)**: ensure the file is present in your client's current directory.

```bash
tftp> put <filename>
```

### 2. Triggering HTTP-style Error Codes (RFC 1350)
The server implements strict error handling and custom file validation. You can test the security logic using the client:

- **Error Code 1 (File not found)**: attempt to read a non-existent file.

```bash
tftp> get missing_file.txt
```

- **Error Code 6 (File already exists)**: attempt to upload a file that already exists in the `public/write/` directory.

```bash
tftp> put existing_file.txt
```

- **Error Code 0 (Invalid file type)**: attempt to upload a file with an unauthorized extension. Allowed extensions are: `.txt`, `.pdf`, `.doc`, `.docx`, `.jpg`, `.png`.

```bash
tftp> put malware.exe
```

- **Error Code 0 (File size is not enough)**: attempt upload a file that is completely empty (0 bytes).

```bash
tftp> put empty_file.txt
```

- **Error Code 0 (File size limit exceeded)**: attempt to upload a file that exceeds the 10MB limit.

```bash
tftp> put bigger_file.txt
```

### 3. Using Automated Python Test Suite

#### 3.1 Basic Functionality
You can test the server's basic functionality, including large file transfers, timeouts, and retransmissions, using the provided Python scripts.

**Step 1: configure and fix `test_tftp.py`** -> Open the `test_tftp.py` file and make the following necessary adjustments:
* 1. Change the port number to match the server: `PORT = 4970`
* 2. Add the missing `@pytest.fixture(scope="module")` decorator above the `putClient()` function (the original test script omits this, causing a fixture error).
* 3. Update the directory paths to match the absolute paths of your local `read` and `write` directories.

The top of your `test_tftp.py` should look like this:

```python
import pytest

HOST = '127.0.0.1'
PORT = 4970

# Init client
@pytest.fixture(scope="module")
def getClient():
    import tftpclient
    return tftpclient.TFTPClient((HOST, PORT), '/YOUR/ABSOLUTE/PATH/TO/public/read/')
    
@pytest.fixture(scope="module") # Ensure this decorator is added!
def putClient():
    import tftpclient
    return tftpclient.TFTPClient((HOST, PORT), '/YOUR/ABSOLUTE/PATH/TO/public/write/')
```

**Step 2: disable the extension check in `TFTPServer.java`** -> The Python test script verifies write requests by uploading files with `.ul` extensions. SInce our server implements a custom file validation security layer (Problem 4), it will automatically rejects these tests. \\
To allow the tests to pass, open `TFTPServer.java`, locate the `receive_DATA_send_ACK` method, and temporarily comment out the extension validation block:

```Java
// String filename = file.getName().toLowerCase();
// boolean isValidExtension = filename.endsWith(".txt") || filename.endsWith(".pdf") ||filename.endsWith(".doc") ||filename.endsWith(".docx") ||filename.endsWith(".jpg") ||filename.endsWith(".png") || filename.endsWith(".pdf");
// if(!isValidExtension) {
//     send_ERR(sendSocket, 0, "Invalid file type");
//     return false;
// }
```

After commenting out these lines, save the file, recompile the server (`javac TFTPServer.java`), and start it (`java TFTPServer`).

**Step 3: generate the binary test files** -> Navigate to the directory containing the test scripts and run:

```bash
chmod +x genfiles.sh
./genfiles.sh
```

*Important: once generated, phyisically move all the resulting `.bin` files (`f50b.bin`, `f500b.bin`, `f3blks.bin`, `f512blks.bin`) into your server's `public/read/` directory so the server can serve them during the download tests*.

**Step 4: install dependencies** -> Install the required Python packages:

```bash
pip3 install -r requirements.txt
```

**Step 5: run the test suite** -> Execute the complete automated test suite (14 tests) using pytest:

```bash
python3 -m pytest
```

If configured correctly, all 14 items should pass successfully (100%).

#### 3.2 Retransmissions and Wrong Block Numbers
A secondary Python script is provided to rigorously test edge cases involving packet loss, timeouts, and out-of-sequence acknowledgments. *This script does not require `pytest`*.

**Step 1: prepare the test environment** -> *before running the tests, ensure your server is active and configure the directories as follows*:
- Copy the provided `2kb.png` file into your server's `public/read/` directory.
- *Ensure that the file `test_upload.txt`* does **NOT** already exists in your `public/write/` directory, otherwise Test 4 (WRQ retransmission) will fail due to our custom Error Code 6 validation.

**Step 2: run the test scripts** -> Execute the script by passing the server's port number (`4970`), the read filename, and the write filename as command_line arguments:

```bash
python3 test_tftp.py 4970 2kb.png test_upload.txt
```

**What these text cover**:
- **Test 1 (RRQ no-ACK)** -> client never sends ACKs; the server must retransmit the `DATA #1` packet multiple times and eventually give up.
- **Test 2 (RRQ wrong ACK 99)** -> client replies with an incorrect block number (99); the server must drop the invalid packet and not advance to the next block.
- **Test 3 (RRQ wrong ACK 0)** -> client replies with a mismatched block number (0); the server must not advance to block 2.
- **Test 4 (WRQ no-DATA)** -> client requests an upload but never sends data; the server must retransmit `ACK #0` multiple times before terminating the connection.
