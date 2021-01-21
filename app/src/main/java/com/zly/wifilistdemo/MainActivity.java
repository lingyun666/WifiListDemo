package com.zly.wifilistdemo;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * 参考https://juejin.cn/post/6844903875263250445#heading-1
 */
public class MainActivity extends AppCompatActivity {
    private WifiManager wifiManager;
    private NetworkInfo lastNetworkInfo;
    private WifiInfo lastWifiInfo;
    private WifiConfiguration lastWifiConfiguration;
    private List<AccessPoint> lastAccessPoints = new CopyOnWriteArrayList<>();
    private Network currentNetwork;
    private int lastPortalNetworkId = AccessPoint.INVALID_NETWORK_ID;

    private Disposable scanWifi;

    private WifiListAdapter adapter;

    // I.获取 WIFI 列表(1,2,...)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 1.通过 Application 的 Context 获取到 WifiManager，调用 setWifiEnable(true) 开启 wifi
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            finish();
            return;
        }
        initView();
        int permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permission == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        wifiManager.setWifiEnabled(true);
        lastNetworkInfo = getActiveNetworkInfo();
        lastWifiInfo = wifiManager.getConnectionInfo();
    }

    private void initView() {
        RecyclerView rvList = findViewById(R.id.rvList);
        adapter = new WifiListAdapter();
        adapter.setItemClickListener(this::showDialog);
        rvList.setLayoutManager(new LinearLayoutManager(this));
        rvList.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvList.setAdapter(adapter);
    }

    // II.WIFI 列表刷新(a,b,...) :获取到 WIFI 列表后，需要定时刷新，并且在 WIFI 连接发生变化时也需要刷新列表
    @Override
    protected void onStart() {
        super.onStart();
        // a.定时刷新WIFI列表:每隔 10 秒钟调用一次 startScan() 方法，然后在广播接收者中更新列表
        scanWifi = Observable.interval(0, 10, TimeUnit.SECONDS)
                .observeOn(Schedulers.io())
                .doOnNext(aLong -> wifiManager.startScan()) // 2.使用 startScan() 方法开始扫描附近的 WIFI 信号
                .subscribe();
        // b.监听网络变化: 注册广播
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction("android.net.wifi.CONFIGURED_NETWORKS_CHANGE");
        filter.addAction("android.net.wifi.LINK_CONFIGURATION_CHANGED");
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        registerReceiver(broadcastReceiver, filter);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkRequest.Builder request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            // d.注册网络回调
            cm.registerNetworkCallback(request.build(), callback);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (scanWifi != null && scanWifi.isDisposed()) {
            scanWifi.dispose();
        }
        unregisterReceiver(broadcastReceiver);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.unregisterNetworkCallback(callback);
        }
    }

    // 3.广播
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                // c.接收广播并更新 wifi 列表
                switch (action) {
                    case ConnectivityManager.CONNECTIVITY_ACTION:
                    case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION:
                    case "android.net.wifi.CONFIGURED_NETWORKS_CHANGE":
                    case "android.net.wifi.LINK_CONFIGURATION_CHANGED":
                        // 4.获取扫描结果
                        updateAccessPoints();
                        break;
                    case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                        updateAccessPoints();
                        NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                        updateNetworkInfo(info);
                        break;
                    case WifiManager.SUPPLICANT_STATE_CHANGED_ACTION:
                        int error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                        if (error == WifiManager.ERROR_AUTHENTICATING) {
                            // 处理密码错误
                            handlePasswordError();
                        }
                        break;
                }
            }
        }
    };

    private ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {

        @Override
        // 网络可用时
        public void onAvailable(Network network) {
            super.onAvailable(network);
            setCurrentNetwork(network);
            portalCurrentWifi();
        }

        @Override
        // 联网能力发生变化时
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            if (network.equals(getCurrentNetwork())) {
                updateNetworkInfo(null);
            }
        }
    };

    public Network getCurrentNetwork() {
        return currentNetwork;
    }

    public void setCurrentNetwork(Network currentNetwork) {
        this.currentNetwork = currentNetwork;
    }

    // 获取扫描结果
    private void updateAccessPoints() {
        Single.create((SingleOnSubscribe<List<AccessPoint>>) emitter -> {
            // 7.创建 AccessPoint 来保存 WIFI 的信息和状态:
            // 分别获取到附近的 WIFI 列表和已保存的 WIFI 列表后，需要匹配附近的 WIFI 和已保存的 WIFI，
            // 对已匹配上的 WIFI 需要同时保存其信息和状态。
            // 通过查看源码可以知道，Settings 中 WIFI 设置的列表是将 WIFI 信息和状态保存到了 AccessPoint 中。
            // 但是 AccessPoint 无法直接使用，所以需要自己创建一个 AccessPoint 类。
            List<AccessPoint> accessPoints = new ArrayList<>();
            List<ScanResult> scanResults = wifiManager.getScanResults(); // 扫描结果,ScanResult 中保存了 WIFI 的名称、加密方式、信号强度等信息
            if (lastWifiInfo != null && lastWifiInfo.getNetworkId() != AccessPoint.INVALID_NETWORK_ID) {
                lastWifiConfiguration = getWifiConfigurationForNetworkId(lastWifiInfo.getNetworkId());
            }
            if (scanResults != null) {
                for (ScanResult scanResult : scanResults) {
                    if (TextUtils.isEmpty(scanResult.SSID)) {
                        continue;
                    }
                    AccessPoint accessPoint = new AccessPoint(this.getApplicationContext(), scanResult);
                    if (accessPoints.contains(accessPoint)) {
                        continue;
                    }
                    // 获取设备中已保存的 WIFI 列表的配置信息,WifiConfiguration 中除了有 WIFI 的名称、加密方式外，还有 networkId、状态及相关配置信息
                    List<WifiConfiguration> wifiConfigurations = wifiManager.getConfiguredNetworks();
                    if (wifiConfigurations != null) {
                        for (WifiConfiguration config : wifiConfigurations) {
                            if (accessPoint.getQuotedSSID().equals(config.SSID)) {
                                accessPoint.setWifiConfiguration(config);
                            }
                        }
                    }
                    if (lastWifiInfo != null && lastNetworkInfo != null) {
                        accessPoint.update(lastWifiConfiguration, lastWifiInfo, lastNetworkInfo);
                    }
                    accessPoints.add(accessPoint);
                }
            }
            Collections.sort(accessPoints);
            emitter.onSuccess(accessPoints);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<List<AccessPoint>>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(List<AccessPoint> accessPoints) {
                        lastAccessPoints = accessPoints;
                        adapter.setAccessPoints(lastAccessPoints);
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });
    }

    // e.更新当前连接 wifi 的状态
    public void updateNetworkInfo(NetworkInfo networkInfo) {
        Single.create((SingleOnSubscribe<List<AccessPoint>>) emitter -> {
            if (networkInfo != null) {
                lastNetworkInfo = networkInfo;
            }
            lastWifiInfo = wifiManager.getConnectionInfo();
            if (lastWifiInfo.getNetworkId() == AccessPoint.INVALID_NETWORK_ID) {
                // 表示没有 wifi 连接，lastPortalNetworkId 置为无效
                lastPortalNetworkId = AccessPoint.INVALID_NETWORK_ID;
            }
            if (lastWifiInfo != null && lastWifiInfo.getNetworkId() != AccessPoint.INVALID_NETWORK_ID) {
                lastWifiConfiguration = getWifiConfigurationForNetworkId(lastWifiInfo.getNetworkId());
            }
            boolean reorder = false;
            for (AccessPoint accessPoint : lastAccessPoints) {
                if (accessPoint.update(lastWifiConfiguration, lastWifiInfo, lastNetworkInfo)) {
                    reorder = true;
                }
            }
            if (reorder) {
                Collections.sort(lastAccessPoints);
            }
            emitter.onSuccess(lastAccessPoints);
        }).subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<List<AccessPoint>>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onSuccess(List<AccessPoint> accessPoints) {
                        // 更新列表
                        adapter.setAccessPoints(accessPoints);
                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }
                });

    }

    public void showDialog(AccessPoint accessPoint) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(accessPoint.ssid);
        if (accessPoint.isSaved()) {
            builder.setPositiveButton("取消保存", (dialog, which) -> forgetWifi(accessPoint))
                    .setNegativeButton("取消", null)
                    .show();

        } else {
            if (!accessPoint.isSaved() && accessPoint.isSecured) {
                EditText editText = new EditText(this);
                builder.setView(editText)
                        .setPositiveButton("连接", (dialog, which) -> {
                            if (editText.getText() != null) {
                                String password = editText.getText().toString();
                                if (!TextUtils.isEmpty(password)) {
                                    accessPoint.setPassword(password);
                                    connect(accessPoint);
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                connect(accessPoint);
            }
        }
    }

    private void connect(AccessPoint accessPoint) {
        accessPoint.generateNetworkConfig();
        int networkId = wifiManager.addNetwork(accessPoint.wifiConfiguration);
        wifiManager.enableNetwork(networkId, true);
    }

    // IV.取消保存
    // (PS 权限问题：对于普通应用只能取消保存自己添加的 wifi，无法取消保存其它应用添加的 wifi；
    // 如果需要取消保存其它应用添加的 wifi，需要添加如下权限，并作为系统应用。
    //<!--覆盖wifi配置 需要是 system app:<uses-permission android:name="android.permission.OVERRIDE_WIFI_CONFIG" />-->
    public void forgetWifi(AccessPoint accessPoint) {
        boolean result = wifiManager.removeNetwork(accessPoint.wifiConfiguration.networkId);
        Toast.makeText(this, result ? "取消保存成功" : "取消保存失败", Toast.LENGTH_LONG).show();
    }

    // V.处理密码错误
    public void handlePasswordError() {
        if (lastWifiConfiguration != null) {
            AccessPoint accessPoint = new AccessPoint(lastWifiConfiguration);
            accessPoint.setPasswordError(true);
        }

    }

    // B.判断 wifi 是否需要登录:
    // 网络可用时，连接指定网址根据返回码是否是 204 判断 wifi 是否需要登录
    public void portalCurrentWifi() {
        if (lastWifiInfo.getNetworkId() != lastPortalNetworkId) {
            lastPortalNetworkId = lastWifiInfo.getNetworkId();
            Single.create((SingleOnSubscribe<Boolean>) emitter -> {
                Network currentNetwork = getCurrentNetwork();
                HttpURLConnection urlConnection = null;
                try {
                    // 使用当前的网络打开链接
                    urlConnection = (HttpURLConnection) currentNetwork.openConnection(new URL("http://connect.rom.miui.com/generate_204"));
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setConnectTimeout(10000);
                    urlConnection.setReadTimeout(10000);
                    urlConnection.setUseCaches(false);
                    urlConnection.getInputStream();
                    int responseCode = urlConnection.getResponseCode();
                    if (responseCode == 200 && urlConnection.getContentLength() == 0) {
                        responseCode = 204;
                    }
                    emitter.onSuccess(responseCode != 204 && responseCode >= 200 && responseCode <= 399);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
                }
            })
                    .retry(throwable -> throwable instanceof UnknownHostException)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new SingleObserver<Boolean>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                        }

                        @Override
                        public void onSuccess(Boolean aBoolean) {
                            if (aBoolean) {
                                // 调用网络登录界面
                                new AlertDialog.Builder(MainActivity.this)
                                        .setTitle("需要登录")
                                        .setPositiveButton("登录", null)
                                        .setNegativeButton("取消", null)
                                        .show();
                            }
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                        }
                    });
        }
    }

    /**
     * 6.根据 NetworkId 获取 WifiConfiguration 信息
     *
     * @param networkId 需要获取 WifiConfiguration 信息的 networkId
     * @return 指定 networkId 的 WifiConfiguration 信息
     */
    private WifiConfiguration getWifiConfigurationForNetworkId(int networkId) {
        final List<WifiConfiguration> configs = wifiManager.getConfiguredNetworks();
        if (configs != null) {
            for (WifiConfiguration config : configs) {
                if (lastWifiInfo != null && networkId == config.networkId) {
                    return config;
                }
            }
        }
        return null;
    }

    /**
     * 5.获取当前连接的 WIFI 信息和状态:
     * 通过 ConnectivityManager 的 getActiveNetworkInfo() 方法获取到当前连接的网络信息；
     * 通过 WifiManager 的 getConnectionInfo() 方法获取当前连接的 WIFI 信息；
     * 根据当前 WIFI 的 networkId 获取到当前 WIFI 的配置和状态。
     * <p>
     * 链接：https://juejin.cn/post/6844903875263250445
     * 来源：掘金
     */
    public NetworkInfo getActiveNetworkInfo() {
        ConnectivityManager cm = (ConnectivityManager) this.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            return cm.getActiveNetworkInfo();
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
