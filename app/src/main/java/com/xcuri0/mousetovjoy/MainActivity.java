package com.xcuri0.mousetovjoy;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintSet;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.SparseArray;
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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.min;

public class MainActivity extends AppCompatActivity {
    WifiManager wm;
    WifiManager.WifiLock perfLock;
    WifiManager.WifiLock latencyLock;

    DatagramSocket socket;
    InetAddress cAddress;
    int cPort;

    int MAX_TOUCHES = 10;
    ByteBuffer tbuf;
    final byte[] buffer = new byte[1 + (MAX_TOUCHES * 8)];
    SparseArray<PointF> pointers = new SparseArray<>();

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

    private void serverLoop() throws IOException {
        DatagramPacket request = new DatagramPacket(new byte[1], 1);
        while (true) {
            socket.receive(request);

            if (!isTetheringIP(getOutboundAddress(request.getSocketAddress())))
                continue;

            cAddress = request.getAddress();
            cPort = request.getPort();

            tbuf = ByteBuffer.allocate(136);
            tbuf.order(ByteOrder.LITTLE_ENDIAN);

            tbuf.putInt(Resources.getSystem().getDisplayMetrics().heightPixels); // right
            tbuf.putInt(Resources.getSystem().getDisplayMetrics().widthPixels); // top
            tbuf.put((Build.MANUFACTURER + " " + Build.MODEL).getBytes());
            tbuf.rewind();
            tbuf.get(buffer);

            tbuf = ByteBuffer.allocate(1 + (MAX_TOUCHES * 8));
            tbuf.order(ByteOrder.LITTLE_ENDIAN);

            DatagramPacket response = new DatagramPacket(buffer, buffer.length, cAddress, cPort);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        byte[] pbuffer = new byte[1 + (MAX_TOUCHES * 8)];
        ByteBuffer pbuf = ByteBuffer.allocate(1 + (MAX_TOUCHES * 8));
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        try {
            socket = new DatagramSocket(7270);
        } catch (SocketException e) {
            e.printStackTrace();
        }

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

        Thread thread = new Thread(() -> {
            try {
                serverLoop();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        Thread thread2 = new Thread(() -> {
            while (true) {
                synchronized (buffer) {
                    try {
                        buffer.wait();
                        DatagramPacket response = new DatagramPacket(buffer, buffer.length, cAddress, cPort);
                        socket.send(response);
                    } catch (IOException | InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        Runnable pinger = () -> {
            if (cAddress == null) return;

            DatagramPacket response = new DatagramPacket(pbuffer, pbuffer.length, cAddress, cPort);
            try {
                socket.send(response);
            } catch (IOException ioException) {
                ioException.printStackTrace();
            }
        };

        wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        perfLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"MouseToVjoyLockPerf");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            latencyLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY,"MouseToVjoyLockLatency");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        Objects.requireNonNull(getSupportActionBar()).hide();

        sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        byte[] ibytes = Base64.decode(sharedPref.getString("bg", "").getBytes(), Base64.DEFAULT);

        ((ImageView)findViewById(R.id.imageView)).setImageBitmap(BitmapFactory.decodeByteArray(ibytes, 0, ibytes.length));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        thread.start();
        thread2.start();

        pbuf.order(ByteOrder.LITTLE_ENDIAN);
        pbuf.put((byte) -1); // length
        pbuf.rewind();
        pbuf.get(pbuffer);

        scheduler.scheduleAtFixedRate(pinger, 5, 5, TimeUnit.SECONDS);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (perfLock.isHeld())
            perfLock.release();
        if (latencyLock.isHeld())
            latencyLock.release();
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
                pointers.put(pointerId, f);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int size = e.getPointerCount(), i = 0; i < size; i++) {
                    PointF point = pointers.get(e.getPointerId(i));
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
                pointers.remove(pointerId);
                break;
            }
        }

        tbuf.position(0);
        tbuf.put((byte) pointers.size());
        for (int i = 0; i < min(pointers.size(), 10); i++) {
            tbuf.putFloat(pointers.valueAt(i).x);
            tbuf.putFloat(pointers.valueAt(i).y);
        }
        tbuf.rewind();
        tbuf.get(buffer);

        synchronized (buffer) {
            buffer.notifyAll();
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
    public void onBackPressed() { }
}