package com.palmgrade.rns.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * RNodeBluetoothManager
 *
 * Manages a Bluetooth Classic (RFCOMM) connection to an RNode LoRa radio device.
 * RNode communicates via the KISS protocol over a serial-like BT SPP channel.
 *
 * Thread model:
 *   - ConnectThread: performs blocking connect()
 *   - ConnectedThread: reads incoming bytes, writes outgoing frames
 *   - Callbacks dispatched on the calling thread via listener interface
 */
public class RNodeBluetoothManager {

    private static final String TAG = "RNodeBT";

    // Standard SPP UUID for RFCOMM serial profile
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // KISS framing constants (used by RNode serial protocol)
    public static final byte KISS_FEND  = (byte) 0xC0;
    public static final byte KISS_FESC  = (byte) 0xDB;
    public static final byte KISS_TFEND = (byte) 0xDC;
    public static final byte KISS_TFESC = (byte) 0xDD;

    // RNode KISS command bytes
    public static final byte CMD_DATA      = 0x00;
    public static final byte CMD_FREQUENCY = 0x01;
    public static final byte CMD_BANDWIDTH = 0x02;
    public static final byte CMD_SF        = 0x03;
    public static final byte CMD_CR        = 0x04;
    public static final byte CMD_RADIO_STATE = 0x05;
    public static final byte CMD_TXPOWER   = 0x07;

    public enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    public interface RNodeListener {
        void onConnectionStateChanged(ConnectionState state, String deviceName);
        void onDataReceived(byte[] data);
        void onError(String message);
    }

    private final Context context;
    private final BluetoothAdapter btAdapter;
    private RNodeListener listener;

    private BluetoothSocket socket;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;

    private final BlockingQueue<byte[]> sendQueue = new LinkedBlockingQueue<>(64);

    // Current radio config
    private RadioConfig radioConfig = new RadioConfig();

    public RNodeBluetoothManager(Context context) {
        this.context = context.getApplicationContext();
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void setListener(RNodeListener listener) {
        this.listener = listener;
    }

    /** Returns paired BT devices for UI display */
    public List<BluetoothDevice> getPairedDevices() {
        List<BluetoothDevice> devices = new ArrayList<>();
        if (btAdapter == null) return devices;
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        if (paired != null) devices.addAll(paired);
        return devices;
    }

    /** Initiate async connection to device */
    public void connect(BluetoothDevice device) {
        if (state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) {
            disconnect();
        }
        setState(ConnectionState.CONNECTING, device.getName());
        connectThread = new ConnectThread(device);
        connectThread.start();
    }

    public void disconnect() {
        if (connectedThread != null) { connectedThread.cancel(); connectedThread = null; }
        if (connectThread != null) { connectThread.cancel(); connectThread = null; }
        setState(ConnectionState.DISCONNECTED, "");
    }

    /** Queue raw bytes to be KISS-framed and sent */
    public boolean sendFrame(byte commandByte, byte[] payload) {
        if (state != ConnectionState.CONNECTED) return false;
        byte[] frame = kissFrame(commandByte, payload);
        return sendQueue.offer(frame);
    }

    /** Send an LXMF message payload over RNS DATA channel */
    public boolean sendLxmfPacket(byte[] lxmfPayload) {
        return sendFrame(CMD_DATA, lxmfPayload);
    }

    /** Apply radio configuration to RNode */
    public void applyRadioConfig(RadioConfig config) {
        this.radioConfig = config;
        if (state != ConnectionState.CONNECTED) return;

        // Frequency: 4 bytes big-endian Hz
        long freqHz = (long)(config.frequencyMhz * 1_000_000);
        byte[] freqBytes = new byte[]{
            (byte)(freqHz >> 24), (byte)(freqHz >> 16),
            (byte)(freqHz >> 8),  (byte)(freqHz)
        };
        sendFrame(CMD_FREQUENCY, freqBytes);

        // Bandwidth index
        sendFrame(CMD_BANDWIDTH, new byte[]{(byte) config.bandwidthIndex});

        // Spreading factor (7–12)
        sendFrame(CMD_SF, new byte[]{(byte) config.spreadingFactor});

        // Coding rate (5–8, stored as 4-bit offset: 5=1, 6=2, 7=3, 8=4)
        sendFrame(CMD_CR, new byte[]{(byte)(config.codingRate - 4)});

        // Enable radio
        sendFrame(CMD_RADIO_STATE, new byte[]{0x01});

        Log.d(TAG, "Radio config applied: " + config);
    }

    public ConnectionState getState() { return state; }
    public RadioConfig getRadioConfig() { return radioConfig; }

    // -------------------------------------------------------------------------
    // KISS framing
    // -------------------------------------------------------------------------

    private byte[] kissFrame(byte cmd, byte[] data) {
        // FEND + cmd + escaped(data) + FEND
        // Over-allocate: worst case 2x data length
        byte[] buf = new byte[data.length * 2 + 4];
        int pos = 0;
        buf[pos++] = KISS_FEND;
        buf[pos++] = cmd;
        for (byte b : data) {
            if (b == KISS_FEND) {
                buf[pos++] = KISS_FESC;
                buf[pos++] = KISS_TFEND;
            } else if (b == KISS_FESC) {
                buf[pos++] = KISS_FESC;
                buf[pos++] = KISS_TFESC;
            } else {
                buf[pos++] = b;
            }
        }
        buf[pos++] = KISS_FEND;
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    private byte[] kissUnescape(byte[] frame) {
        byte[] buf = new byte[frame.length];
        int pos = 0;
        boolean escaping = false;
        for (byte b : frame) {
            if (escaping) {
                buf[pos++] = (b == KISS_TFEND) ? KISS_FEND : KISS_FESC;
                escaping = false;
            } else if (b == KISS_FESC) {
                escaping = true;
            } else if (b != KISS_FEND) {
                buf[pos++] = b;
            }
        }
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    // -------------------------------------------------------------------------
    // State helpers
    // -------------------------------------------------------------------------

    private void setState(ConnectionState newState, String deviceName) {
        state = newState;
        if (listener != null) listener.onConnectionStateChanged(newState, deviceName);
    }

    // -------------------------------------------------------------------------
    // Connect thread
    // -------------------------------------------------------------------------

    private class ConnectThread extends Thread {
        private final BluetoothSocket btSocket;
        private final BluetoothDevice device;

        ConnectThread(BluetoothDevice device) {
            this.device = device;
            BluetoothSocket tmp = null;
            try {
                tmp = device.createRfcommSocketToServiceRecord(SPP_UUID);
            } catch (IOException e) {
                Log.e(TAG, "Socket create failed", e);
            }
            btSocket = tmp;
        }

        @Override
        public void run() {
            btAdapter.cancelDiscovery();
            try {
                btSocket.connect();
                socket = btSocket;
                setState(ConnectionState.CONNECTED, device.getName());
                // Start I/O thread
                connectedThread = new ConnectedThread(btSocket);
                connectedThread.start();
                // Apply stored config
                applyRadioConfig(radioConfig);
            } catch (IOException e) {
                Log.e(TAG, "Connect failed", e);
                try { btSocket.close(); } catch (IOException ignored) {}
                setState(ConnectionState.ERROR, device.getName());
                if (listener != null) listener.onError("Connection failed: " + e.getMessage());
            }
        }

        void cancel() {
            try { btSocket.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Connected I/O thread
    // -------------------------------------------------------------------------

    private class ConnectedThread extends Thread {
        private final BluetoothSocket btSocket;
        private final InputStream inStream;
        private final OutputStream outStream;
        private volatile boolean running = true;

        ConnectedThread(BluetoothSocket socket) {
            this.btSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Stream open failed", e);
            }
            inStream = tmpIn;
            outStream = tmpOut;

            // Writer thread: drains sendQueue
            Thread writer = new Thread(() -> {
                while (running) {
                    try {
                        byte[] frame = sendQueue.take();
                        outStream.write(frame);
                        outStream.flush();
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Write error", e);
                        break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "RNode-Writer");
            writer.setDaemon(true);
            writer.start();
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            byte[] accumulator = new byte[8192];
            int accPos = 0;

            while (running) {
                try {
                    int n = inStream.read(buf);
                    if (n < 0) break;
                    for (int i = 0; i < n; i++) {
                        byte b = buf[i];
                        if (b == KISS_FEND) {
                            if (accPos > 1) {
                                // We have a complete frame
                                byte[] raw = new byte[accPos];
                                System.arraycopy(accumulator, 0, raw, 0, accPos);
                                // raw[0] is command byte; raw[1..] is payload
                                if (raw[0] == CMD_DATA && listener != null) {
                                    byte[] payload = new byte[raw.length - 1];
                                    System.arraycopy(raw, 1, payload, 0, payload.length);
                                    byte[] unescaped = kissUnescape(payload);
                                    listener.onDataReceived(unescaped);
                                }
                            }
                            accPos = 0;
                        } else if (accPos < accumulator.length) {
                            accumulator[accPos++] = b;
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "Read error", e);
                        setState(ConnectionState.ERROR, "");
                        if (listener != null) listener.onError("Connection lost");
                    }
                    break;
                }
            }
        }

        void cancel() {
            running = false;
            try { btSocket.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Radio configuration model
    // -------------------------------------------------------------------------

    public static class RadioConfig {
        public double frequencyMhz = 433.025;
        public int bandwidthIndex  = 0; // 0=125kHz, 1=62.5kHz, 2=31.25kHz, 3=15.625kHz
        public int spreadingFactor = 9;
        public int codingRate      = 5;

        public static final String[] BANDWIDTH_LABELS = {
            "125 kHz", "62.5 kHz", "31.25 kHz", "15.625 kHz"
        };

        public String getBandwidthLabel() {
            return BANDWIDTH_LABELS[Math.max(0, Math.min(bandwidthIndex, 3))];
        }

        @Override
        public String toString() {
            return String.format("%.3f MHz BW=%s SF=%d CR=%d",
                frequencyMhz, getBandwidthLabel(), spreadingFactor, codingRate);
        }
    }
}
