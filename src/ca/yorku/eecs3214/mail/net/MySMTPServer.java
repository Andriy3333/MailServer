package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailWriter;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

public class MySMTPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;

    // State tracking for the SMTP session
    private boolean clientIdentified = false;
    private String sender = null;
    private List<Mailbox> recipients = new ArrayList<>();
    private boolean inDataMode = false;
    private StringBuilder messageBuffer = new StringBuilder();

    /**
     * Initializes an object responsible for a connection to an individual client.
     *
     * @param socket The socket associated to the accepted connection.
     * @throws IOException If there is an error attempting to retrieve the socket's information.
     */
    public MySMTPServer(Socket socket) throws IOException {
        this.socket = socket;
        this.socketIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.socketOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
    }

    /**
     * Handles the communication with an individual client. Must send the initial welcome message, and then repeatedly
     * read requests, process the individual operation, and return a response, according to the SMTP protocol. Empty
     * request lines should be ignored. Only returns if the connection is terminated or if the QUIT command is issued.
     * Must close the socket connection before returning.
     */
    @Override
    public void run() {
        try (this.socket) {
            // Send welcome message
            socketOut.println("220 " + getHostName() + " SMTP Server Ready");

            String line;
            while ((line = socketIn.readLine()) != null) {
                if (line.isEmpty()) {
                    continue; // Ignore empty lines
                }

                if (inDataMode) {
                    handleDataContent(line);
                } else {
                    processCommand(line);
                }
            }
        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
    }

    /**
     * Processes a command line from the client and routes it to the appropriate handler.
     *
     * @param commandLine The command line from the client.
     */
    private void processCommand(String commandLine) {
        // Split the command and arguments
        String[] parts = commandLine.split(" ", 2);
        String command = parts[0].toUpperCase();
        String arguments = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "HELO":
            case "EHLO":
                handleHelo(command, arguments);
                break;
            case "MAIL":
                handleMail(arguments);
                break;
            case "RCPT":
                handleRcpt(arguments);
                break;
            case "DATA":
                handleData(arguments);
                break;
            case "RSET":
                handleRset(arguments);
                break;
            case "VRFY":
                handleVrfy(arguments);
                break;
            case "NOOP":
                handleNoop(arguments);
                break;
            case "QUIT":
                handleQuit(arguments);
                break;
            default:
                // Check if it's an unsupported command
                if (isKnownUnsupportedCommand(command)) {
                    socketOut.println("502 Command not implemented");
                } else {
                    socketOut.println("500 Syntax error, command unrecognized");
                }
                break;
        }
    }

    /**
     * Checks if a command is a known SMTP command that is not implemented in this server.
     *
     * @param command The command to check.
     * @return true if the command is a known but unimplemented SMTP command.
     */
    private boolean isKnownUnsupportedCommand(String command) {
        // List of commands defined in RFC 5321 but not implemented
        return command.equals("EXPN") ||
                command.equals("HELP") ||
                command.equals("TURN") ||
                command.equals("ATRN") ||
                command.equals("SIZE") ||
                command.equals("ETRN") ||
                command.equals("STARTTLS") ||
                command.equals("AUTH") ||
                command.equals("BDAT") ||
                command.equals("CHUNKING");
    }

    /**
     * Handles the HELO and EHLO commands.
     *
     * @param command The command (HELO or EHLO).
     * @param arguments The arguments for the command.
     */
    private void handleHelo(String command, String arguments) {
        if (arguments.isEmpty()) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        // Reset state for a new mail transaction
        sender = null;
        recipients.clear();
        clientIdentified = true;

        socketOut.println("250 " + getHostName() + " greets " + arguments);
    }

    /**
     * Handles the MAIL FROM command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleMail(String arguments) {
        if (!arguments.toUpperCase().startsWith("FROM:")) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        if (!clientIdentified) {
            socketOut.println("503 Bad sequence of commands");
            return;
        }

        if (sender != null) {
            socketOut.println("503 Bad sequence of commands");
            return;
        }

        String fromAddress = arguments.substring(5).trim();
        // Extract the email address from <email@example.com>
        if (fromAddress.startsWith("<") && fromAddress.endsWith(">")) {
            fromAddress = fromAddress.substring(1, fromAddress.length() - 1);
        }

        // Accept any sender
        sender = fromAddress;
        recipients.clear();

        socketOut.println("250 OK");
    }

    /**
     * Handles the RCPT TO command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleRcpt(String arguments) {
        if (!arguments.toUpperCase().startsWith("TO:")) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        if (sender == null) {
            socketOut.println("503 Bad sequence of commands");
            return;
        }

        String toAddress = arguments.substring(3).trim();
        // Extract the email address from <email@example.com>
        if (toAddress.startsWith("<") && toAddress.endsWith(">")) {
            toAddress = toAddress.substring(1, toAddress.length() - 1);
        }

        // Check if the recipient is valid
        if (!Mailbox.isValidUser(toAddress)) {
            socketOut.println("550 No such user here");
            return;
        }

        try {
            Mailbox mailbox = new Mailbox(toAddress);
            recipients.add(mailbox);
            socketOut.println("250 OK");
        } catch (Mailbox.InvalidUserException e) {
            socketOut.println("550 No such user here");
        }
    }

    /**
     * Handles the DATA command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleData(String arguments) {
        if (!arguments.isEmpty()) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        if (sender == null || recipients.isEmpty()) {
            socketOut.println("503 Bad sequence of commands");
            return;
        }

        inDataMode = true;
        messageBuffer.setLength(0); // Clear any previous message
        socketOut.println("354 Start mail input; end with <CRLF>.<CRLF>");
    }

    /**
     * Handles the content of the message during DATA mode.
     *
     * @param line A line of the message content.
     * @throws IOException If there is an error writing the message.
     */
    private void handleDataContent(String line) throws IOException {
        if (line.equals(".")) {
            // End of data
            inDataMode = false;

            // Create a new message for all recipients
            try (MailWriter writer = new MailWriter(recipients)) {
                writer.write("From: " + sender + "\r\n");
                for (Mailbox recipient : recipients) {
                    writer.write("To: " + recipient.getUsername() + "\r\n");
                }
                writer.write("Date: " + new java.util.Date() + "\r\n");
                writer.write("\r\n"); // Empty line separates headers from body
                writer.write(messageBuffer.toString());
            }

            // Reset state for next mail transaction but keep client identified
            sender = null;
            recipients.clear();
            messageBuffer.setLength(0);

            socketOut.println("250 OK");
        } else {
            // Handle dot-stuffing (RFC 5321, 4.5.2)
            if (line.startsWith(".")) {
                line = line.substring(1);
            }
            messageBuffer.append(line).append("\r\n");
        }
    }

    /**
     * Handles the RSET command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleRset(String arguments) {
        if (!arguments.isEmpty()) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        // Reset the state but keep client identification
        sender = null;
        recipients.clear();
        inDataMode = false;
        messageBuffer.setLength(0);

        socketOut.println("250 OK");
    }

    /**
     * Handles the VRFY command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleVrfy(String arguments) {
        if (arguments.isEmpty()) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        // Extract the email address
        String email = arguments.trim();
        if (email.startsWith("<") && email.endsWith(">")) {
            email = email.substring(1, email.length() - 1);
        }

        // Accept USER even if invalid, but return error on PASS for security reasons
        if (Mailbox.isValidUser(email)) {
            socketOut.println("250 " + email + " is a valid mailbox");
        } else {
            socketOut.println("550 User not found");
        }
    }

    /**
     * Handles the NOOP command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleNoop(String arguments) {
        if (!arguments.isEmpty()) {
            socketOut.println("501 Syntax error in parameters or arguments");
            return;
        }

        socketOut.println("250 OK");
    }

    /**
     * Handles the QUIT command.
     *
     * @param arguments The arguments for the command.
     */
    private void handleQuit(String arguments) {
        socketOut.println("221 " + getHostName() + " Service closing transmission channel");
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore as its closing anyway
        }
    }

    /**
     * Retrieves the name of the current host. Used in the response of commands like HELO and EHLO.
     * @return A string corresponding to the name of the current host.
     */
    private static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            try (BufferedReader reader = Runtime.getRuntime().exec(new String[] {"hostname"}).inputReader()) {
                return reader.readLine();
            } catch (IOException ex) {
                return "unknown_host";
            }
        }
    }

    /**
     * Main process for the SMTP server. Handles the argument parsing and creates a listening server socket. Repeatedly
     * accepts new connections from individual clients, creating a new server instance that handles communication with
     * that client in a separate thread.
     *
     * @param args The command-line arguments.
     * @throws IOException In case of an exception creating the server socket or accepting new connections.
     */
    public static void main(String[] args) throws IOException {

        if (args.length != 1) {
            throw new RuntimeException("This application must be executed with exactly one argument, the listening port.");
        }

        try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
            serverSocket.setReuseAddress(true);
            System.out.println("Waiting for connections on port " + serverSocket.getLocalPort() + "...");
            //noinspection InfiniteLoopStatement
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Accepted a connection from " + socket.getRemoteSocketAddress());
                try {
                    MySMTPServer handler = new MySMTPServer(socket);
                    handler.start();
                } catch (IOException e) {
                    System.err.println("Error setting up an individual client's handler.");
                    e.printStackTrace();
                }
            }
        }
    }
}