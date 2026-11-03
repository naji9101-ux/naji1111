package com.sitr.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Local DNS-only VPN. Routes only the virtual DNS server through the TUN interface,
 * leaving normal TCP/UDP app traffic on the existing network.
 * This is NOT an IP-anonymizing VPN and it cannot inspect HTTPS app content.
 */
public class ShieldVpnService extends VpnService {
    public static final String ACTION_START = "com.sitr.guard.START";
    public static final String ACTION_STOP = "com.sitr.guard.STOP";
    public static final String CHANNEL = "sitr_protection";
    public static volatile boolean isRunning = false;

    private final AtomicBoolean alive = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private ParcelFileDescriptor tunnel;
    private FileOutputStream output;
    private Thread reader;
    private ThreadPoolExecutor pool;

    public static void requestStart(Context context) {
        Intent intent = new Intent(context, ShieldVpnService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void requestStop(Context context) {
        context.startService(new Intent(context, ShieldVpnService.class).setAction(ACTION_STOP));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopProtection();
            return START_NOT_STICKY;
        }
        SharedPreferences pref = getSharedPreferences("sitr", MODE_PRIVATE);
        if (!pref.getBoolean("ads", false) && !pref.getBoolean("privacy", false)) {
            stopProtection();
            return START_NOT_STICKY;
        }
        startForeground(1001, ongoingNotification());
        if (!alive.get()) {
            try {
                BlockRules.load(this);
                VpnService.Builder builder = new VpnService.Builder()
                        .setSession("سِتر - حماية DNS محلية")
                        .setMtu(1500)
                        .addAddress("10.222.0.2", 32)
                        .addDnsServer("10.222.0.1")
                        .addRoute("10.222.0.1", 32);
                builder.allowFamily(OsConstants.AF_INET6);
                tunnel = builder.establish();
                if (tunnel == null) {
                    stopProtection();
                    return START_NOT_STICKY;
                }
                output = new FileOutputStream(tunnel.getFileDescriptor());
                pool = new ThreadPoolExecutor(4, 6, 30, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(256), new ThreadPoolExecutor.DiscardPolicy());
                alive.set(true);
                isRunning = true;
                pref.edit().putBoolean("connected", true).apply();
                reader = new Thread(this::readPackets, "SITR-DNS-TUN");
                reader.start();
                DailyReminder.schedule(this);
            } catch (Exception e) {
                pref.edit().putBoolean("connected", false).apply();
                stopProtection();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private Notification ongoingNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "حماية سِتر",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        PendingIntent pending = PendingIntent.getActivity(this, 10,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("سِتر: الحماية تعمل")
                .setContentText("حجب نطاقات الإعلانات والتتبع — اضغط لفتح التحكم")
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void readPackets() {
        try (FileInputStream input = new FileInputStream(tunnel.getFileDescriptor())) {
            byte[] buffer = new byte[32767];
            while (alive.get()) {
                int n = input.read(buffer);
                if (n <= 0) continue;
                byte[] copy = Arrays.copyOf(buffer, n);
                if (pool != null && !pool.isShutdown()) {
                    try { pool.execute(() -> handle(copy)); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) { }
                }
            }
        } catch (Exception ignored) { }
        if (alive.get()) {
            new android.os.Handler(getMainLooper()).post(this::stopProtection);
        }
    }

    private static int u16(byte[] data, int p) {
        return ((data[p] & 255) << 8) | (data[p + 1] & 255);
    }

    private static void w16(byte[] data, int p, int value) {
        data[p] = (byte) (value >>> 8);
        data[p + 1] = (byte) value;
    }

    private void handle(byte[] packet) {
        if (!alive.get() || packet.length < 28 || (packet[0] >> 4 & 15) != 4) return;
        int header = (packet[0] & 15) * 4;
        if (header < 20 || packet.length < header + 8) return;
        if ((packet[9] & 255) != 17) return;
        if ((u16(packet, 6) & 0x1FFF) != 0) return; // IPv4 fragments not handled
        int ipLength = u16(packet, 2);
        if (ipLength > packet.length || ipLength < header + 8) return;
        int udpOffset = header;
        int sourcePort = u16(packet, udpOffset);
        int destPort = u16(packet, udpOffset + 2);
        if (destPort != 53) return;
        int udpLength = u16(packet, udpOffset + 4);
        if (udpLength < 8 || udpOffset + udpLength > ipLength) return;
        byte[] question = Arrays.copyOfRange(packet, udpOffset + 8,
                udpOffset + udpLength);
        String host = BlockRules.queryHost(question);
        SharedPreferences pref = getSharedPreferences("sitr", MODE_PRIVATE);
        boolean ads = pref.getBoolean("ads", false);
        boolean privacy = pref.getBoolean("privacy", false);
        byte[] response;
        if (host != null && BlockRules.shouldBlock(this, host, ads, privacy)) {
            response = BlockRules.blockedDnsReply(question);
            synchronized (writeLock) {
                long count = pref.getLong("blocked", 0) + 1;
                pref.edit().putLong("blocked", count).apply();
            }
        } else {
            response = resolve(question, privacy);
        }
        if (response == null || response.length + 28 > 1500 || !alive.get()) return;
        byte[] reply = makeIpv4UdpReply(packet, header, sourcePort, response);
        synchronized (writeLock) {
            try { if (output != null && alive.get()) output.write(reply); }
            catch (Exception ignored) { }
        }
    }

    private byte[] resolve(byte[] query, boolean privacy) {
        if (privacy) {
            try {
                byte[] result = dnsOverTls(query);
                getSharedPreferences("sitr", MODE_PRIVATE).edit()
                        .putBoolean("dns_fallback", false).apply();
                return result;
            } catch (Exception ex) {
                // Explicitly report downgrade in the user interface.
                getSharedPreferences("sitr", MODE_PRIVATE).edit()
                        .putBoolean("dns_fallback", true).apply();
            }
        }
        return udpResolve(query, "1.1.1.1");
    }

    private byte[] udpResolve(byte[] query, String server) {
        String[] servers = {server, "8.8.8.8"};
        for (String address : servers) {
            try (DatagramSocket socket = new DatagramSocket()) {
                protect(socket); // Prevent resolver traffic entering the VPN.
                socket.setSoTimeout(2300);
                byte[] remote = InetAddress.getByName(address).getAddress();
                DatagramPacket request = new DatagramPacket(query, query.length,
                        InetAddress.getByAddress(remote), 53);
                socket.send(request);
                byte[] buffer = new byte[1472];
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                socket.receive(reply);
                if (reply.getLength() >= 12
                        && reply.getData()[0] == query[0]
                        && reply.getData()[1] == query[1]) {
                    return Arrays.copyOf(reply.getData(), reply.getLength());
                }
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** DNS-over-TLS to a fixed resolver IP; TLS verifies one.one.one.one. */
    private byte[] dnsOverTls(byte[] query) throws Exception {
        Socket raw = new Socket();
        try {
            protect(raw);
            raw.connect(new InetSocketAddress("1.1.1.1", 853), 3500);
            raw.setSoTimeout(3500);
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault()
                    .createSocket(raw, "one.one.one.one", 853, true);
            try {
                SSLParameters parameters = ssl.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.setSoTimeout(3500);
                ssl.startHandshake();
                DataOutputStream writer = new DataOutputStream(ssl.getOutputStream());
                writer.writeShort(query.length);
                writer.write(query);
                writer.flush();
                DataInputStream reader = new DataInputStream(ssl.getInputStream());
                int len = reader.readUnsignedShort();
                if (len < 12 || len > 4096) throw new Exception("Invalid DoT response");
                byte[] result = new byte[len];
                reader.readFully(result);
                if (result[0] != query[0] || result[1] != query[1])
                    throw new Exception("DoT ID mismatch");
                return result;
            } finally { ssl.close(); }
        } finally { try { raw.close(); } catch (Exception ignored) { } }
    }

    private static int checksum(byte[] p, int offset, int length) {
        long sum = 0;
        int i = offset;
        int end = offset + length;
        while (i + 1 < end) {
            sum += ((p[i] & 255) << 8) | (p[i + 1] & 255);
            i += 2;
        }
        if (i < end) sum += (p[i] & 255) << 8;
        while ((sum >> 16) != 0) sum = (sum & 65535) + (sum >> 16);
        return (int) (~sum) & 65535;
    }

    private static byte[] makeIpv4UdpReply(byte[] request, int header, int clientPort,
                                            byte[] dns) {
        byte[] reply = new byte[28 + dns.length];
        reply[0] = 0x45;
        w16(reply, 2, reply.length);
        reply[8] = 64;
        reply[9] = 17; // UDP
        System.arraycopy(request, 16, reply, 12, 4); // server source address
        System.arraycopy(request, 12, reply, 16, 4); // client destination
        w16(reply, 10, checksum(reply, 0, 20));
        w16(reply, 20, 53);
        w16(reply, 22, clientPort);
        w16(reply, 24, 8 + dns.length);
        // IPv4 UDP checksum may legally be zero.
        System.arraycopy(dns, 0, reply, 28, dns.length);
        return reply;
    }

    @Override public void onRevoke() {
        getSharedPreferences("sitr", MODE_PRIVATE).edit()
                .putBoolean("ads", false).putBoolean("privacy", false).apply();
        stopProtection();
        super.onRevoke();
    }

    private void stopProtection() {
        if (alive.getAndSet(false)) {
            if (pool != null) pool.shutdownNow();
        }
        isRunning = false;
        getSharedPreferences("sitr", MODE_PRIVATE).edit()
                .putBoolean("connected", false).apply();
        try { if (tunnel != null) tunnel.close(); } catch (Exception ignored) { }
        tunnel = null;
        output = null;
        DailyReminder.cancel(this);
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        alive.set(false);
        isRunning = false;
        super.onDestroy();
    }
}
