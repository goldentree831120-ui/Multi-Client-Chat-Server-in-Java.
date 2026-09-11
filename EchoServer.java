import java.io.*;
import java.net.*;

/**
 * v1: Single-client echo server.
 * Accepts ONE client, reads lines of text, echoes them back.
 * No threading yet -- this is intentional, so you can see the
 * raw blocking socket behavior before we add concurrency.
 */
public class EchoServer {
    public static void main(String[] args) throws IOException {
        int port = 5000;

        // ServerSocket = the "receptionist" that listens on a port
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port + " ...");

            // accept() BLOCKS until a client connects.
            // It returns a new Socket representing that one connection.
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connected: " + clientSocket.getInetAddress());

            // Wrap the raw byte streams in text-friendly readers/writers.
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
            PrintWriter out = new PrintWriter(
                    clientSocket.getOutputStream(), true); // true = auto-flush

            String line;
            // readLine() blocks until a full line (terminated by \n) arrives,
            // or returns null if the client closed the connection.
            while ((line = in.readLine()) != null) {
                System.out.println("Received: " + line);
                out.println("Echo: " + line);

                if (line.equalsIgnoreCase("quit")) {
                    break;
                }
            }

            System.out.println("Client disconnected.");
            clientSocket.close();
        }
    }
}
