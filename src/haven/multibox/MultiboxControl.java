package haven.multibox;

import auto.Bot;
import haven.*;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/** Minimal authenticated loopback adapter for Haven Multibox Client. */
public final class MultiboxControl {
    private static final MultiboxControl INSTANCE = new MultiboxControl();
    private static final int PREVIEW_W = 320, PREVIEW_H = 180;
    private volatile UILoop loop;
    private volatile ServerSocket server;
    private volatile Runnable closeAction;
    private volatile byte[] preview;
    private volatile long previewAt;
    private final AtomicBoolean captureRequested = new AtomicBoolean();
    private final AtomicBoolean captureRunning = new AtomicBoolean();
    private final Object previewWaiter = new Object();
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "multibox-preview-encoder"); t.setDaemon(true); return t;
    });

    private MultiboxControl() {}

    public static void attach(UILoop loop) { INSTANCE.loop = loop; INSTANCE.start(); }
    public static void onClose(Runnable action) { INSTANCE.closeAction = action; }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if(value == null || value.trim().isEmpty()) value = System.getenv(env);
        return value == null ? "" : value.trim();
    }

    private void start() {
        if(server != null) return;
        String configured = setting("haven.multibox.port", "HAVEN_MULTIBOX_PORT");
        if(configured.isEmpty()) return;
        final int port;
        try { port = Integer.parseInt(configured); } catch(NumberFormatException e) { return; }
        synchronized(this) {
            if(server != null) return;
            try { server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")); }
            catch(IOException e) { System.err.println("[multibox] bind failed: " + e.getMessage()); return; }
            Thread t = new Thread(this::acceptLoop, "multibox-control"); t.setDaemon(true); t.start();
            System.err.println("[multibox] Thunder adapter on 127.0.0.1:" + port);
        }
    }

    private void acceptLoop() {
        while(server != null && !server.isClosed()) {
            try {
                Socket socket = server.accept();
                Thread t = new Thread(() -> { try { handle(socket); } catch(Exception ignored) {} finally { try { socket.close(); } catch(IOException ignored) {} } }, "multibox-request");
                t.setDaemon(true); t.start();
            } catch(IOException e) { break; }
        }
    }

    private void handle(Socket socket) throws Exception {
        socket.setSoTimeout(1500);
        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String request = input.readLine();
        if(request == null) return;
        String token = "";
        for(String line; (line = input.readLine()) != null && !line.isEmpty();) {
            int colon = line.indexOf(':');
            if(colon > 0 && line.substring(0, colon).equalsIgnoreCase("X-Haven-Multibox-Token")) token = line.substring(colon + 1).trim();
        }
        String expected = setting("haven.multibox.token", "HAVEN_MULTIBOX_TOKEN");
        if(expected.isEmpty() || !constantTimeEquals(expected, token)) { reply(socket, 401, "application/json", bytes("{\"ok\":false,\"error\":\"unauthorized\"}")); return; }
        String[] parts = request.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "";
        if(method.equals("GET") && path.equals("/multibox/v1/state")) reply(socket, 200, "application/json", bytes(state()));
        else if(method.equals("GET") && path.equals("/multibox/v1/preview.jpg")) preview(socket);
        else if(method.equals("POST") && path.equals("/multibox/v1/task/cancel")) { Bot.cancelCurrent("Cancelled from Haven Multibox"); reply(socket, 200, "application/json", bytes("{\"ok\":true}")); }
        else if(method.equals("POST") && path.equals("/multibox/v1/client/close")) { reply(socket, 200, "application/json", bytes("{\"ok\":true}")); Runnable close = closeAction; if(close != null) close.run(); }
        else reply(socket, 404, "application/json", bytes("{\"ok\":false,\"error\":\"not found\"}"));
    }

    private String state() {
        UILoop lp = loop;
        UI ui = lp == null ? null : lp.ui;
        String screen = "none", cid = "", name = "";
        boolean connected = false, combat = false;
        double shp = -1, hhp = -1, stamina = -1, energy = -1;
        int inventory = -1;
        if(ui != null) synchronized(ui) {
            connected = ui.sess != null;
            GameUI gui = ui.gui;
            if(gui != null && gui.map != null) screen = "game";
            else if(find(ui.root, Charlist.class) != null) screen = "chars";
            else if(find(ui.root, LoginScreen.class) != null) screen = "login";
            else screen = "unknown";
            if(gui != null) {
                cid = gui.chrid == null ? "" : gui.chrid;
                name = Config.getPlayerName(); if(name == null) name = cid;
                combat = gui.isInCombat();
                IMeter hp = gui.getIMeter("hp"), stam = gui.getIMeter("stam"), nrj = gui.getIMeter("nrj");
                if(hp != null) { shp = hp.meter(0); hhp = hp.meter(1); }
                if(stam != null) stamina = stam.meter(0);
                if(nrj != null) energy = nrj.meter(0);
                if(gui.maininv != null) inventory = gui.maininv.filled();
            }
        }
        boolean running = Bot.hasCurrent();
        return "{\"schema_version\":1,\"client\":\"thunder\",\"profile_id\":\"" + esc(setting("haven.multibox.profile", "HAVEN_MULTIBOX_PROFILE")) +
            "\",\"generated_at_ms\":" + System.currentTimeMillis() + ",\"connected\":" + connected + ",\"screen\":\"" + screen +
            "\",\"character\":{\"id\":\"" + esc(cid) + "\",\"name\":\"" + esc(name) + "\"},\"combat\":" + combat +
            ",\"meters\":{\"shp\":" + num(shp) + ",\"hhp\":" + num(hhp) + ",\"stamina\":" + num(stamina) + ",\"energy\":" + num(energy) +
            "},\"inventory_used\":" + inventory + ",\"active_task\":{\"running\":" + running + ",\"state\":\"" + (running ? "running" : "idle") + "\",\"name\":\"Thunder task\"}}";
    }

    private void preview(Socket socket) throws IOException {
        captureRequested.set(true);
        long deadline = System.currentTimeMillis() + 650;
        synchronized(previewWaiter) {
            long old = previewAt;
            while(previewAt == old && System.currentTimeMillis() < deadline) try { previewWaiter.wait(80); } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        byte[] data = preview;
        if(data == null) reply(socket, 503, "application/json", bytes("{\"ok\":false,\"error\":\"preview unavailable\"}"));
        else reply(socket, 200, "image/jpeg", data);
    }

    public static void capture(GOut g) {
        MultiboxControl self = INSTANCE;
        if(!self.captureRequested.compareAndSet(true, false) || !self.captureRunning.compareAndSet(false, true)) return;
        g.getimage(img -> self.encoder.execute(() -> self.encode(img)));
    }

    private void encode(BufferedImage source) {
        try {
            BufferedImage scaled = new BufferedImage(PREVIEW_W, PREVIEW_H, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, PREVIEW_W, PREVIEW_H, null); graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768); ImageIO.write(scaled, "jpg", out);
            preview = out.toByteArray(); previewAt = System.currentTimeMillis();
            synchronized(previewWaiter) { previewWaiter.notifyAll(); }
        } catch(IOException ignored) {} finally { captureRunning.set(false); }
    }

    private static <T extends Widget> T find(Widget root, Class<T> type) {
        if(root == null) return null; if(type.isInstance(root)) return type.cast(root);
        for(Widget child = root.child; child != null; child = child.next) { T found = find(child, type); if(found != null) return found; }
        return null;
    }

    private static String num(double n) { return n < 0 || Double.isNaN(n) ? "null" : Double.toString(n); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String esc(String s) { if(s == null) return ""; return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private static boolean constantTimeEquals(String a, String b) { byte[] x = bytes(a), y = bytes(b); if(x.length == 0 || y.length == 0) return false; int d = x.length ^ y.length; for(int i = 0; i < Math.max(x.length, y.length); i++) d |= x[i % x.length] ^ y[i % y.length]; return d == 0; }
    private static void reply(Socket socket, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found" : "Service Unavailable";
        OutputStream out = socket.getOutputStream();
        out.write(bytes("HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: " + type + "\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n"));
        out.write(body); out.flush();
    }
}
