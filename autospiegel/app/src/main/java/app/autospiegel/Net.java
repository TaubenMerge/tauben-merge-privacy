package app.autospiegel;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/** Small helpers for the local network. */
final class Net {
    private Net() {}

    /**
     * IPv4 addresses of interfaces that have a broadcast address, i.e. Wi-Fi and hotspot,
     * but not mobile data.
     */
    static List<InterfaceAddress> lanAddresses() {
        List<InterfaceAddress> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            return result;
        }
        if (interfaces == null) {
            return result;
        }
        for (NetworkInterface ni : Collections.list(interfaces)) {
            try {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
            } catch (SocketException e) {
                continue;
            }
            for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                if (address != null
                        && address.getAddress() instanceof Inet4Address
                        && address.getBroadcast() != null) {
                    result.add(address);
                }
            }
        }
        return result;
    }

    static List<String> lanIps() {
        List<String> ips = new ArrayList<>();
        for (InterfaceAddress address : lanAddresses()) {
            ips.add(address.getAddress().getHostAddress());
        }
        return ips;
    }

    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> result = new ArrayList<>();
        for (InterfaceAddress address : lanAddresses()) {
            result.add(address.getBroadcast());
        }
        return result;
    }

    /** Formats an IPv4 address as stored by {@code WifiManager} (little endian). */
    static String intToIp(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "."
                + ((ip >> 24) & 0xff);
    }

    static void close(Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    // Socket, ServerSocket and DatagramSocket only implement Closeable from API 19 on.

    static void close(Socket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    static void close(ServerSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing left to do.
        }
    }

    static void close(DatagramSocket s) {
        if (s != null) {
            s.close();
        }
    }
}
