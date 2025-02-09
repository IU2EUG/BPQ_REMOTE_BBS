package retro;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class BPQTelnetGateway {

    // Comando per terminare la sessione (digitato da BPQ32)
    private static final String EXIT_COMMAND = "exit";  

    // Charset per la comunicazione tra BPQ32 e il programma Java: UTF-8
    private static final Charset BPQ_CHARSET = Charset.forName("UTF-8");
    
    // Charset per l'output della BBS remota (da convertire in UTF-8);
    // in questo esempio si assume che la BBS invii i dati in Cp437
    private static final Charset BBS_CHARSET = Charset.forName("Cp437");

    public static void main(String[] args) {
        // Avvia la GUI nell'EDT
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowGUI();
            }
        });
        // Avvia il server in un thread separato
        new Thread(new Runnable() {
            public void run() {
                startServer();
            }
        }).start();
    }

    /**
     * Crea la finestra principale con una TextArea dedicata al log e un semplice menù con l'opzione Exit.
     * La TextArea viene configurata con sfondo nero, testo verde e font monospaziato.
     */
    private static void createAndShowGUI() {
        JFrame frame = new JFrame("BPQTelnetGateway");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(800, 600);

        // TextArea per il log (non modificabile)
        JTextArea textArea = new JTextArea();
        textArea.setEditable(false);
        // Imposta lo stile "console"
        textArea.setBackground(Color.BLACK);
        textArea.setForeground(Color.GREEN);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 14));
        JScrollPane scrollPane = new JScrollPane(textArea);

        // Crea il menù "File" con l'opzione "Exit"
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("File");
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });
        fileMenu.add(exitItem);
        menuBar.add(fileMenu);
        frame.setJMenuBar(menuBar);

        frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
        frame.setVisible(true);

        // Reindirizza System.out e System.err verso la TextArea
        GUIOutputStream guiOut = new GUIOutputStream(textArea);
        PrintStream printStream = new PrintStream(guiOut, true);
        System.setOut(printStream);
        System.setErr(printStream);
    }

    /**
     * Avvia il server che, in ascolto sulla porta 12345, accetta le connessioni da BPQ32
     * e gestisce ciascuna sessione.
     */
    public static void startServer() {
        int portBPQ = 12345;
        try (ServerSocket serverSocket = new ServerSocket(portBPQ)) {
            System.out.println("Server in ascolto sulla porta " + portBPQ);
            while (true) {
                Socket bpqSocket = serverSocket.accept();
                System.out.println("Connessione BPQ32 accettata da: " + bpqSocket.getRemoteSocketAddress());
                new Thread(new BPQHandler(bpqSocket)).start();
            }
        } catch (IOException e) {
            System.err.println("Errore nel server: " + e.getMessage());
        }
    }

    /**
     * Gestisce la sessione per ciascuna connessione in ingresso da BPQ32.
     * Una volta stabilita la connessione:
     * - Apre la connessione Telnet verso la BBS remota,
     * - Inoltra i messaggi in entrambe le direzioni,
     * - Se l'utente invia "exit", la sessione termina.
     */
    static class BPQHandler implements Runnable {
        private Socket bpqSocket;

        public BPQHandler(Socket bpqSocket) {
            this.bpqSocket = bpqSocket;
        }

        @Override
        public void run() {
            Socket telnetSocket = null;
            BufferedWriter telnetOut = null;
            PrintWriter bpqOut = null;

            try {
                // Stream verso BPQ32 in UTF-8
                bpqOut = new PrintWriter(new OutputStreamWriter(bpqSocket.getOutputStream(), BPQ_CHARSET), true);

                // Connessione Telnet verso la BBS remota
                try {
                    telnetSocket = new Socket("bbs.retrocampus.com", 23);
                    telnetOut = new BufferedWriter(new OutputStreamWriter(telnetSocket.getOutputStream(), BBS_CHARSET));
                    System.out.println("Connessione Telnet stabilita con bbs.retrocampus.com");
                    bpqOut.println("Connessione con BBS stabilita.");
                } catch (IOException e) {
                    System.err.println("Errore durante la connessione Telnet: " + e.getMessage());
                    bpqOut.println("Errore: Impossibile connettersi alla BBS.");
                    return;
                }
                
                // Avvia il thread per leggere l'output dalla BBS in modalità carattere per carattere
                TelnetReader telnetReader = new TelnetReader(telnetSocket.getInputStream(), bpqSocket);
                new Thread(telnetReader).start();
                
                // Legge i messaggi provenienti da BPQ32 (in UTF-8) e li inoltra alla BBS
                BufferedReader bpqIn = new BufferedReader(new InputStreamReader(bpqSocket.getInputStream(), BPQ_CHARSET));
                String line;
                while ((line = bpqIn.readLine()) != null) {
                    System.out.println("Ricevuto da BPQ32: " + line);
                    if (line.trim().equalsIgnoreCase(EXIT_COMMAND)) {
                        bpqOut.println("Disconnessione dalla BBS in corso...");
                        break;
                    }
                    try {
                        synchronized (telnetOut) {
                            telnetOut.write(line + "\n");
                            telnetOut.flush();
                        }
                    } catch (IOException e) {
                        System.err.println("Errore nell'invio verso Telnet: " + e.getMessage());
                        bpqOut.println("Errore: Problema nella comunicazione con la BBS.");
                    }
                }
                System.out.println("Chiusura della connessione Telnet.");
            } catch (IOException e) {
                System.err.println("Errore nella comunicazione con BPQ32: " + e.getMessage());
            } finally {
                try {
                    if (telnetSocket != null && !telnetSocket.isClosed()) {
                        telnetSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Errore durante la chiusura della connessione Telnet: " + e.getMessage());
                }
                try {
                    if (bpqSocket != null && !bpqSocket.isClosed()) {
                        bpqSocket.close();
                    }
                } catch (IOException e) {
                    System.err.println("Errore durante la chiusura della connessione BPQ32: " + e.getMessage());
                }
                System.out.println("Sessione terminata con BPQ32.");
            }
        }
    }

    /**
     * Legge continuamente i dati dalla connessione Telnet (BBS remota) in modalità carattere per carattere,
     * converte l'output in UTF-8 e lo "sanitizza" eliminando:
     * - Le sequenze ANSI
     * - Le sequenze di negoziazione Telnet
     * - I caratteri di controllo non supportati (eccetto newline, carriage return e tab)
     * - E il carattere "♀" (Unicode U+2640)
     *
     * In questo modo anche i prompt non terminati da newline (ad esempio, la richiesta del nome)
     * vengono visualizzati immediatamente.
     *
     * Quando la connessione con la BBS viene persa, il thread chiude la connessione verso BPQ32,
     * facendo tornare il sistema al prompt di BPQ32, come se l'utente avesse digitato il comando "exit".
     */
    static class TelnetReader implements Runnable {
        // Utilizza l'InputStream direttamente per poter leggere carattere per carattere
        private InputStream telnetInputStream;
        private Socket bpqSocket;

        public TelnetReader(InputStream telnetInputStream, Socket bpqSocket) {
            this.telnetInputStream = telnetInputStream;
            this.bpqSocket = bpqSocket;
        }
        
        private String sanitizeOutput(String input) {
            if (input == null) {
                return null;
            }
            // 1. Rimuove le sequenze ANSI (iniziano con ESC [ e terminano con un carattere compreso tra @ e ~)
            String cleaned = input.replaceAll("\u001B\\[[;\\d]*[ -/]*[@-~]", "");
            // 2. Rimuove le sequenze di subnegoziazione Telnet:
            //    Pattern: IAC (0xFF) SB ... IAC SE
            cleaned = cleaned.replaceAll("\u00FF\u00FA.*?\u00FF\u00F0", "");
            // 3. Rimuove le sequenze brevi Telnet (3 byte: IAC + comando + opzione)
            cleaned = cleaned.replaceAll("\u00FF[\u00FB-\u00FE].", "");
            // 4. Rimuove eventuali residui di IAC seguiti da un carattere
            cleaned = cleaned.replaceAll("\u00FF.", "");
            
            // 5. Costruisce la stringa finale includendo solo i caratteri supportati:
            //    - Accetta newline (\n), carriage return (\r) e tab (\t)
            //    - Accetta i caratteri stampabili (codice >= 32)
            //    - Esclude il carattere "♀" (Unicode U+2640)
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '\u2640') { // Esclude il carattere "♀"
                    continue;
                }
                if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 127) {
                    continue;
                }
                sb.append(c);
            }
            return sb.toString();
        }

        @Override
        public void run() {
            PrintWriter bpqOut = null;
            try {
                bpqOut = new PrintWriter(new OutputStreamWriter(bpqSocket.getOutputStream(), BPQ_CHARSET), true);
                StringBuilder buffer = new StringBuilder();
                // Creiamo un InputStreamReader per poter interpretare i caratteri secondo il charset della BBS
                InputStreamReader reader = new InputStreamReader(telnetInputStream, BBS_CHARSET);
                while (!bpqSocket.isClosed()) {
                    // Se sono disponibili dati, leggiamo un carattere
                    if (reader.ready()) {
                        int c = reader.read();
                        if (c == -1) { // Fine dello stream
                            break;
                        }
                        buffer.append((char) c);
                    } else {
                        // Se non ci sono dati immediati, ma il buffer contiene qualcosa,
                        // lo inviamo anche se non termina con newline
                        if (buffer.length() > 0) {
                            String text = buffer.toString();
                            String sanitizedText = sanitizeOutput(text);
                            System.out.print("Ricevuto dalla BBS (pulito): " + sanitizedText);
                            bpqOut.print(sanitizedText);
                            bpqOut.flush();
                            buffer.setLength(0);
                        }
                        // Breve pausa per evitare busy-waiting
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
                System.out.println("Connessione BBS persa. Chiudo la connessione verso BPQ32 per tornare al prompt.");
            } catch (IOException e) {
                System.err.println("Errore nella lettura dalla connessione Telnet: " + e.getMessage());
            } finally {
                try {
                    bpqSocket.close();
                } catch (IOException ex) {
                    System.err.println("Errore nella chiusura della connessione BPQ: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * Una classe di OutputStream che redirige ogni scrittura su una JTextArea.
     * Utilizza SwingUtilities.invokeLater per aggiornare la GUI in modo thread-safe.
     */
    static class GUIOutputStream extends OutputStream {
        private JTextArea textArea;
        private StringBuilder sb = new StringBuilder();
        
        public GUIOutputStream(JTextArea textArea) {
            this.textArea = textArea;
        }
        
        @Override
        public void write(int b) throws IOException {
            if (b == '\r') {
                return;
            }
            if (b == '\n') {
                final String text = sb.toString() + "\n";
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        textArea.append(text);
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                    }
                });
                sb.setLength(0);
            } else {
                sb.append((char) b);
            }
        }
        
        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            final String text = new String(b, off, len);
            SwingUtilities.invokeLater(new Runnable() {
                public void run() {
                    textArea.append(text);
                    textArea.setCaretPosition(textArea.getDocument().getLength());
                }
            });
        }
    }
}
