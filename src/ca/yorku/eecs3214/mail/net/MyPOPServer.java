package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailMessage;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.List;

public class MyPOPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;

    // POP3 state management
    private enum State {
        AUTHORIZATION, // Client needs to authenticate
        TRANSACTION,   // Client can access mailbox
        UPDATE         // About to close connection
    }

    private State currentState = State.AUTHORIZATION;
    private String username = null;
    private Mailbox mailbox = null;

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's
     *                     information.
     */
    public MyPOPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the
     * initial welcome message, and then repeatedly read requests, process the
     * individual operation, and return a response, according to the POP3
     * protocol. Empty request lines should be ignored. Only returns if the
     * connection is terminated or if the QUIT command is issued. Must close the
     * socket connection before returning.
     */
    @Override
    public void run() {
        // Use a try-with-resources block to ensure that the socket is closed
        // when the method returns
        try (this.socket) {
            // Send welcome message
            socketOut.println("+OK POP3 server ready");

            String line;
            while ((line = socketIn.readLine()) != null) {
                if (line.isEmpty()) {
                    continue; // Ignore empty lines
                }

                processCommand(line);

                if (currentState == State.UPDATE) {
                    // We're done, close the connection
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    /**
     * Processes a command from the client.
     *
     * @param commandLine The command line to process
     */
    private void processCommand(String commandLine) {
        // Split command and arguments
        String[] parts = commandLine.split(" ", 2);
        String command = parts[0].toUpperCase();
        String arguments = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "USER":
                handleUser(arguments);
                break;
            case "PASS":
                handlePass(arguments);
                break;
            case "STAT":
                handleStat(arguments);
                break;
            case "LIST":
                handleList(arguments);
                break;
            case "RETR":
                handleRetr(arguments);
                break;
            case "DELE":
                handleDele(arguments);
                break;
            case "RSET":
                handleRset(arguments);
                break;
            case "NOOP":
                handleNoop(arguments);
                break;
            case "QUIT":
                handleQuit();
                break;
            default:
                socketOut.println("-ERR Unknown command");
                break;
        }
    }

    /**
     * Handles the USER command.
     *
     * @param arguments The user's email address
     */
    private void handleUser(String arguments) {
        if (currentState != State.AUTHORIZATION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (arguments.isEmpty()) {
            socketOut.println("-ERR Missing username");
            return;
        }

        // As per RFC 1939 section 13, always accept the USER command
        // even if user doesn't exist (for security reasons)
        username = arguments.trim();
        socketOut.println("+OK User accepted");
    }

    /**
     * Handles the PASS command.
     *
     * @param arguments The user's password
     */
    private void handlePass(String arguments) {
        if (currentState != State.AUTHORIZATION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (username == null) {
            socketOut.println("-ERR USER command must be issued first");
            return;
        }

        if (arguments.isEmpty()) {
            socketOut.println("-ERR Missing password");
            return;
        }

        String password = arguments.trim();

        try {
            // Validate user exists
            mailbox = new Mailbox(username);

            // Authenticate user
            try {
                mailbox.loadMessages(password);
                currentState = State.TRANSACTION;
                socketOut.println("+OK Mailbox locked and ready");
            } catch (Mailbox.MailboxNotAuthenticatedException e) {
                socketOut.println("-ERR Authentication failed");
                username = null;
                mailbox = null;
            }
        } catch (Mailbox.InvalidUserException e) {
            socketOut.println("-ERR Authentication failed");
            username = null;
        }
    }

    /**
     * Handles the STAT command.
     *
     * @param arguments Should be empty
     */
    private void handleStat(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (!arguments.isEmpty()) {
            socketOut.println("-ERR No arguments needed");
            return;
        }

        try {
            int count = mailbox.size(false); // Don't include deleted messages
            long totalSize = mailbox.getTotalUndeletedFileSize(false); // Don't include deleted messages
            socketOut.println("+OK " + count + " " + totalSize);
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR Mailbox not authenticated");
        }
    }

    /**
     * Handles the LIST command.
     *
     * @param arguments Optional message number
     */
    private void handleList(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        try {
            if (arguments.isEmpty()) {
                // List all messages
                int count = mailbox.size(false); // Don't include deleted messages
                long totalSize = mailbox.getTotalUndeletedFileSize(false);
                socketOut.println("+OK " + count + " messages (" + totalSize + " octets)");

                int messageCount = mailbox.size(true); // Include deleted to get the total count
                for (int i = 1; i <= messageCount; i++) {
                    MailMessage message = mailbox.getMailMessage(i);
                    if (!message.isDeleted()) {
                        socketOut.println(i + " " + message.getFileSize());
                    }
                }
                socketOut.println(".");
            } else {
                // List a specific message
                try {
                    int messageNumber = Integer.parseInt(arguments.trim());
                    MailMessage message = mailbox.getMailMessage(messageNumber);

                    if (message.isDeleted()) {
                        socketOut.println("-ERR Message " + messageNumber + " has been deleted");
                    } else {
                        socketOut.println("+OK " + messageNumber + " " + message.getFileSize());
                    }
                } catch (NumberFormatException e) {
                    socketOut.println("-ERR Invalid message number");
                } catch (IndexOutOfBoundsException e) {
                    socketOut.println("-ERR No such message");
                }
            }
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR Mailbox not authenticated");
        }
    }

    /**
     * Handles the RETR command.
     *
     * @param arguments Message number to retrieve
     */
    private void handleRetr(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (arguments.isEmpty()) {
            socketOut.println("-ERR Missing message number");
            return;
        }

        try {
            int messageNumber = Integer.parseInt(arguments.trim());

            try {
                MailMessage message = mailbox.getMailMessage(messageNumber);

                if (message.isDeleted()) {
                    socketOut.println("-ERR Message " + messageNumber + " has been deleted");
                    return;
                }

                File messageFile = message.getFile();
                long fileSize = message.getFileSize();

                socketOut.println("+OK " + fileSize + " octets");

                // Read and send the message content
                try (BufferedReader fileReader = new BufferedReader(new FileReader(messageFile))) {
                    String line;
                    while ((line = fileReader.readLine()) != null) {
                        // Handle dot-stuffing: Lines starting with . need another . prefix
                        if (line.startsWith(".")) {
                            socketOut.println("." + line);
                        } else {
                            socketOut.println(line);
                        }
                    }
                }


            } catch (IndexOutOfBoundsException e) {
                socketOut.println("-ERR No such message");
            } catch (IOException e) {
                socketOut.println("-ERR Error reading message");
            }

        } catch (NumberFormatException e) {
            socketOut.println("-ERR Invalid message number");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR Mailbox not authenticated");
        }
    }

    /**
     * Handles the DELE command.
     *
     * @param arguments Message number to delete
     */
    private void handleDele(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (arguments.isEmpty()) {
            socketOut.println("-ERR Missing message number");
            return;
        }

        try {
            int messageNumber = Integer.parseInt(arguments.trim());

            try {
                MailMessage message = mailbox.getMailMessage(messageNumber);

                if (message.isDeleted()) {
                    socketOut.println("-ERR Message " + messageNumber + " already deleted");
                } else {
                    message.tagForDeletion();
                    socketOut.println("+OK Message " + messageNumber + " deleted");
                }

            } catch (IndexOutOfBoundsException e) {
                socketOut.println("-ERR No such message");
            }

        } catch (NumberFormatException e) {
            socketOut.println("-ERR Invalid message number");
        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR Mailbox not authenticated");
        }
    }

    /**
     * Handles the RSET command.
     *
     * @param arguments Should be empty
     */
    private void handleRset(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (!arguments.isEmpty()) {
            socketOut.println("-ERR No arguments needed");
            return;
        }

        try {
            int messageCount = mailbox.size(true); // Include deleted to get total count

            for (int i = 1; i <= messageCount; i++) {
                try {
                    MailMessage message = mailbox.getMailMessage(i);
                    if (message.isDeleted()) {
                        message.undelete();
                    }
                } catch (IndexOutOfBoundsException e) {
                    // This shouldn't happen but if it does, continue
                }
            }

            socketOut.println("+OK Maildrop has " + mailbox.size(false) + " messages");

        } catch (Mailbox.MailboxNotAuthenticatedException e) {
            socketOut.println("-ERR Mailbox not authenticated");
        }
    }

    /**
     * Handles the NOOP command.
     *
     * @param arguments Should be empty
     */
    private void handleNoop(String arguments) {
        if (currentState != State.TRANSACTION) {
            socketOut.println("-ERR Command not valid in this state");
            return;
        }

        if (!arguments.isEmpty()) {
            socketOut.println("-ERR No arguments needed");
            return;
        }

        socketOut.println("+OK");
    }

    /**
     * Handles the QUIT command.
     */
    private void handleQuit() {
        if (currentState == State.TRANSACTION) {
            // We need to perform the UPDATE operations
            currentState = State.UPDATE;

            // Actually delete files marked for deletion
            if (mailbox != null) {
                mailbox.deleteMessagesTaggedForDeletion();
            }
        }

        socketOut.println("+OK POP3 server signing off");
        currentState = State.UPDATE; // Ensure we exit the loop
    }

    /**
     * Main process for the POP3 server. Handles the argument parsing and
     * creates a listening server socket. Repeatedly accepts new connections
     * from individual clients, creating a new server instance that handles
     * communication with that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or
     *                     accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException(
                    "This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);

            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            // noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MyPOPServer handler = new MyPOPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}