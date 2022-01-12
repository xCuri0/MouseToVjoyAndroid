package com.xcuri0.mousetovjoy;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static android.os.Process.THREAD_PRIORITY_LOWEST;
import static android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY;
import static android.os.Process.setThreadPriority;
import static java.lang.Math.min;

public class MainActivity extends AppCompatActivity {
    final char APP_VERSION = 3;

    DatagramSocket socket;
    InetAddress cAddress;
    int cPort;

    Boolean sending = false;
    int MAX_TOUCHES = 10;
    ByteBuffer touchBBuffer = ByteBuffer.allocate(1 + (MAX_TOUCHES * 8));
    final byte[] touchBuffer = new byte[1 + (MAX_TOUCHES * 8)];
    SparseArray<PointF> touchPointers = new SparseArray<>();

    SharedPreferences sharedPref;

    private InetAddress getOutboundAddress(SocketAddress remoteAddress) throws SocketException {
        DatagramSocket sock = new DatagramSocket();
        // connect is needed to bind the socket and retrieve the local address
        // later (it would return 0.0.0.0 otherwise)
        sock.connect(remoteAddress);

        final InetAddress localAddress = sock.getLocalAddress();

        sock.disconnect();
        sock.close();

        return localAddress;
    }

    private static boolean isTetheringIP(InetAddress address){
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!intf.isLoopback()) {
                        if (intf.getName().contains("rndis")) {
                            if (inetAddress.equals(address))
                                return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean isTetheringActive(){
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!intf.isLoopback()) {
                        if (intf.getName().contains("rndis")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void serverLoop() throws IOException {
        byte[] sendBuffer = new byte[136];
        ByteBuffer sendBBuffer = ByteBuffer.allocate(136);

        sendBBuffer.order(ByteOrder.LITTLE_ENDIAN);

        sendBBuffer.putInt(Resources.getSystem().getDisplayMetrics().heightPixels); // right
        sendBBuffer.putInt(Resources.getSystem().getDisplayMetrics().widthPixels); // top
        sendBBuffer.put((byte) APP_VERSION);
        sendBBuffer.put((Build.MANUFACTURER + " " + Build.MODEL).getBytes());
        sendBBuffer.rewind();
        sendBBuffer.get(sendBuffer);

        DatagramPacket request = new DatagramPacket(new byte[1], 1);

        TrafficStats.setThreadStatsTag(7270);
        while (true) {
            socket.receive(request);

            if (!isTetheringIP(getOutboundAddress(request.getSocketAddress())))
                continue;

            cAddress = request.getAddress();
            cPort = request.getPort();

            DatagramPacket response = new DatagramPacket(sendBuffer, sendBuffer.length, cAddress, cPort);
            try {
                socket.send(response);
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
            if (request.getData()[0] >= 2) {
                Log.i("MouseToVjoy", "Opening bg selector");
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(intent, 727);
            }

            Log.i("MouseToVjoy", "Connected from" + cAddress.toString());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 727 && resultCode == Activity.RESULT_OK) {
            try {
                Bitmap img = MediaStore.Images.Media.getBitmap(this.getContentResolver(), data.getData());
                SharedPreferences.Editor editor = sharedPref.edit();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                img.compress(Bitmap.CompressFormat.PNG, 100, bos); //bm is the bitmap object

                editor.putString("bg", Base64.encodeToString(bos.toByteArray(), Base64.DEFAULT));
                editor.apply();

                ((ImageView)findViewById(R.id.imageView)).setImageBitmap(img);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .penaltyDeath()
                    .build());
        }
        super.onCreate(savedInstanceState);

        byte[] pingBuffer = new byte[1];
        ByteBuffer pingBBuffer = ByteBuffer.allocate(1);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        touchBBuffer.order(ByteOrder.LITTLE_ENDIAN);
        pingBBuffer.order(ByteOrder.LITTLE_ENDIAN);
        pingBBuffer.put((byte) -1); // length
        pingBBuffer.rewind();
        pingBBuffer.get(pingBuffer);

        try {
            TrafficStats.setThreadStatsTag(7270);
            socket = new DatagramSocket(7270);
            socket.setReceiveBufferSize(1);
            socket.setSendBufferSize(1);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        // the app runs fullscreen and pinned so this shouldnt be a problem
        setThreadPriority(THREAD_PRIORITY_URGENT_DISPLAY);

        // set activity settings
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // load bg image
        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        byte[] ibytes = Base64.decode(sharedPref.getString("bg", "").getBytes(), Base64.DEFAULT);
        ((ImageView) findViewById(R.id.imageView)).setImageBitmap(BitmapFactory.decodeByteArray(ibytes, 0, ibytes.length));

        if (!isTetheringActive()) {
            AlertDialog.Builder msg = new AlertDialog.Builder(this);
            msg.setMessage("Enable USB Tethering to continue");
            msg.setTitle("MouseToVjoy");
            msg.setPositiveButton("OK", null);
            msg.setCancelable(true);
            msg.setPositiveButton("Ok",
                    (dialog, which) -> {
                        startActivityForResult(new Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS), 0);
                        if (!isTetheringActive()) {
                            this.finishAffinity();
                        }
                    });
            msg.setOnDismissListener((dialog) -> this.finishAffinity());
            msg.create().show();
        }

        if (isTetheringActive()) {
            Thread serverThread = new Thread(() -> {
                setThreadPriority(THREAD_PRIORITY_LOWEST);
                try {
                    serverLoop();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
            Thread touchThread = new Thread(() -> {
                setThreadPriority(-19);
                while (true) {
                    synchronized (touchBuffer) {
                        try {
                            touchBuffer.wait();
                            sending = true;
                            DatagramPacket response = new DatagramPacket(touchBuffer, touchBuffer.length, cAddress, cPort);
                            socket.send(response);
                            sending = false;
                        } catch (IOException | InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
            Thread pingThread = new Thread(() -> {
                setThreadPriority(THREAD_PRIORITY_LOWEST);
                while (true) {
                    if (cAddress == null) continue;
                    DatagramPacket response = new DatagramPacket(pingBuffer, pingBuffer.length, cAddress, cPort);
                    try {
                        socket.send(response);
                    } catch (IOException ioException) {
                        ioException.printStackTrace();
                    }
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            });

            serverThread.start();
            touchThread.start();
            pingThread.start();
        }

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        socket.close();
    }

    private void sendTouch(MotionEvent e) {
        int actionIndex = e.getActionIndex();
        int pointerId = e.getPointerId(actionIndex);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                PointF f = new PointF();
                f.x = e.getX(actionIndex);
                f.y = e.getY(actionIndex);
                touchPointers.put(pointerId, f);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int size = e.getPointerCount(), i = 0; i < size; i++) {
                    PointF point = touchPointers.get(e.getPointerId(i));
                    if (point != null) {
                        point.x = e.getX(i);
                        point.y = e.getY(i);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                touchPointers.remove(pointerId);
                break;
            }
        }

        touchBBuffer.position(0);
        touchBBuffer.put((byte) touchPointers.size());
        for (int i = 0; i < min(touchPointers.size(), 10); i++) {
            touchBBuffer.putFloat(touchPointers.valueAt(i).x);
            touchBBuffer.putFloat(touchPointers.valueAt(i).y);
        }
        touchBBuffer.rewind();
        touchBBuffer.get(touchBuffer);

        if (!sending) {
            synchronized (touchBuffer) {
                touchBuffer.notifyAll();
            }
        }
    }
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // MotionEvent reports input details from the touch screen
        // and other input controls. In this case, you are only
        // interested in events where the touch position changed.

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // disable event batching to get full polling rate if possible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    getWindow().getDecorView().getRootView().requestUnbufferedDispatch(e);

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_MOVE:
                if (cAddress == null) break;
                sendTouch(e);
                break;
        }

        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int btn = 0;
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP: {
                if (action == KeyEvent.ACTION_DOWN)
                    btn = 1;
                else
                    btn = 3;
                break;
            }
            case KeyEvent.KEYCODE_VOLUME_DOWN: {
                if (action == KeyEvent.ACTION_DOWN)
                    btn = 2;
                else
                    btn = 4;
                break;
            }
        }
        if (btn > 0 && cAddress != null) {
            touchBBuffer.position(0);
            touchBBuffer.put((byte) -2);
            touchBBuffer.putFloat(btn);
            touchBBuffer.rewind();
            touchBBuffer.get(touchBuffer);

            synchronized (touchBuffer) {
                touchBuffer.notifyAll();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() { }
}