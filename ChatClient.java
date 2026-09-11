import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * Console chat client.
 *
 * Runs TWO threads:
 * - main thread: reads what YOU type and sends it to the server
 * - listener thread: continuously reads incoming messages from the server
 * and prints them, independent of whether you're mid-typing or not
 *
 * This solves the problem of nc/telnet having no proper input handling,
 * and demonstrates why concurrent I/O matters on the client side too.
 */
public class ChatClient {
    public static void main(String[] args) throws IOException {
        String host = "localhost";
        int port = 5000;

        Socket socket = new Socket(host, port);
        System.out.println("Connected to server at " + host + ":" + port);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

        // --- Listener thread: prints whatever the server sends, whenever it arrives
        // ---
        Thread listenerThread = new Thread(() -> {
            try {
                String serverLine;
                while ((serverLine = in.readLine()) != null) {
                    System.out.println(serverLine);
                }
                System.out.println("Server closed the connection.");
            } catch (IOException e) {
                System.out.println("Disconnected from server.");
            }
        });
        listenerThread.setDaemon(true); // so it doesn't block JVM shutdown
        listenerThread.start();

        // --- Main thread: reads your keyboard input and sends it ---
        Scanner scanner = new Scanner(System.in);
        String userInput;
        while (scanner.hasNextLine()) {
            userInput = scanner.nextLine();
            out.println(userInput);

            if (userInput.equalsIgnoreCase("quit")) {
                break;
            }
        }

        socket.close();
        scanner.close();
        System.out.println("Client shut down.");
    }
}
