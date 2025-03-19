package ca.yorku.eecs3214.mail.net;

import ca.yorku.eecs3214.mail.mailbox.MailMessage;
import ca.yorku.eecs3214.mail.mailbox.Mailbox;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class MyPOPServer extends Thread {

    private final Socket socket;
    private final BufferedReader socketIn;
    private final PrintWriter socketOut;

    // TODO Additional properties, if needed

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

            // TODO Complete this method

        } catch (IOException e) {
            System.err.println("Error in client's connection handling.");
            e.printStackTrace();
        }
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