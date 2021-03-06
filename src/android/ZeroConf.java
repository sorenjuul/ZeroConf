/**
 * ZeroConf plugin for Cordova/Phonegap
 *
 * Copyright (c) 2013-2014 Vlad Stirbu <vlad.stirbu@ieee.org>
 * Converted to Cordova 3.x
 * Refactored initialization
 * MIT License
 *
 * @author Matt Kane
 * Copyright (c) Triggertrap Ltd. 2012. All Rights Reserved.
 * Available under the terms of the MIT License.
 * 
 */

package com.triggertrap;

import java.io.IOException;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.net.wifi.WifiManager;
import android.util.Log;
import android.net.wifi.WifiInfo;

import java.lang.IllegalStateException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.MulticastSocket;
import java.net.DatagramPacket;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import javax.jmdns.ServiceTypeListener;

public class ZeroConf extends CordovaPlugin {
	WifiManager.MulticastLock lock;
	private JmDNS jmdns = null;
	private ServiceListener listener;
    private ServiceTypeListener typeListener;
	private CallbackContext callback;
    private WifiManager wifi;
    private InetAddress deviceIpAddress;

	@Override
	public void initialize(CordovaInterface cordova, CordovaWebView webView) {
		super.initialize(cordova, webView);

		wifi = (WifiManager) this.cordova.getActivity()
				.getSystemService(android.content.Context.WIFI_SERVICE);
        deviceIpAddress = getDeviceIpAddress(wifi);


        Log.v("ZeroConf", "Initialized");
	}

	@Override
	public boolean execute(String action, JSONArray args,
			CallbackContext callbackContext) {
		this.callback = callbackContext;

		if (action.equals("watch")) {
			final String type = args.optString(0);
			if (type != null) {
				cordova.getThreadPool().execute(new Runnable() {
					public void run() {
						watch(type); // Thread-safe.
					}
				});
			} else {
				callbackContext.error("Service type not specified.");
				return false;
			}
		} else if (action.equals("unwatch")) {
			final String type = args.optString(0);
			if (type != null) {
				cordova.getThreadPool().execute(new Runnable() {
					public void run() {
						unwatch(type);
					}
				});
			} else {
				callbackContext.error("Service type not specified.");
				return false;
			}
		} else if (action.equals("register")) {
			JSONObject obj = args.optJSONObject(0);
			if (obj != null) {
				final String type = obj.optString("type");
				final String name = obj.optString("name");
				final int port = obj.optInt("port");
				final String text = obj.optString("text");
				if (type == null) {
					callbackContext.error("Missing required service info.");
					return false;
				}
				cordova.getThreadPool().execute(new Runnable() {
					public void run() {
						register(type, name, port, text);
					}
				});
			} else {
				callbackContext.error("Missing required service info.");
				return false;

			}

		} else if (action.equals("close")) {
			if (jmdns != null) {

                    cordova.getThreadPool().execute(new Runnable() {
                        public void run() {
                            try {
                                jmdns.close(); // Thread-safe.
                                jmdns = null;
                            } catch (IOException e) {
                                e.printStackTrace();
                                return;
                            }
                        }
                    });
                    lock.release();
            }
            return true;
		} else if (action.equals("unregister")) {
			if (jmdns != null) {
				jmdns.unregisterAllServices();
			}

		} else {
			Log.e("ZeroConf", "Invalid action: " + action);
			callbackContext.error("Invalid action.");
			return false;
		}
        PluginResult result = new PluginResult(Status.NO_RESULT);
        result.setKeepCallback(true);
		return true;
	}

	private void watch(String type) {
		if (jmdns == null) {
			try {
                lock = wifi.createMulticastLock("ZeroConfPluginLock");
                lock.setReferenceCounted(true);
                lock.acquire();
                jmdns = JmDNS.create(deviceIpAddress, "Finder");
                setupWatcher();
                //setupTypeWatcher();
                //jmdns.addServiceTypeListener(typeListener);

			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}
		Log.d("ZeroConf", "Watch " + type);
		Log.d("ZeroConf",
				"Name: " + jmdns.getName() + " host: " + jmdns.getHostName());
		try {
            jmdns.addServiceListener(type, listener);
        } catch (IllegalStateException e) {
            e.printStackTrace();
            return;
        }
        //sendDatagramPacket();

	}

    private void sendDatagramPacket(){
        // join a Multicast group and send the group salutations
        try {
            String msg = "Hello";
            InetAddress group = InetAddress.getByName("228.5.6.7");
            MulticastSocket s = new MulticastSocket(6789);
            s.joinGroup(group);
            DatagramPacket hi = new DatagramPacket(msg.getBytes(), msg.length(),
                    group, 6789);
            s.send(hi);
            Log.d("ZeroConf", "DatagramPacket send");
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
    }

    private void unwatch(String type) {
		if (jmdns == null) {
			return;
		}
		jmdns.removeServiceListener(type, listener);
	}

	private void register(String type, String name, int port, String text) {
		if (name == null) {
			name = "";
		}

		if (text == null) {
			text = "";
		}

		try {
			ServiceInfo service = ServiceInfo.create(type, name, port, text);
			jmdns.registerService(service);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

    private InetAddress getDeviceIpAddress(WifiManager wifi) {
        InetAddress result = null;
        try {
            // default to Android localhost
            result = InetAddress.getByName("10.0.0.2");

            // figure out our wifi address, otherwise bail
            WifiInfo wifiinfo = wifi.getConnectionInfo();
            int intaddr = wifiinfo.getIpAddress();
            byte[] byteaddr = new byte[] { (byte) (intaddr & 0xff), (byte) (intaddr >> 8 & 0xff), (byte) (intaddr >> 16 & 0xff), (byte) (intaddr >> 24 & 0xff) };
            result = InetAddress.getByAddress(byteaddr);
        } catch (UnknownHostException ex) {
            Log.w("ZeroConf", String.format("getDeviceIpAddress Error: %s", ex.getMessage()));
        }

        return result;
    }


	private void getList(String type){
		
		Log.d("ZeroConf", "refresh services...");
		try {
			final ServiceInfo[] services = jmdns.list(type, 6000);
			
			Log.d("ZeroConf", "List service..." + type);
			Log.d("ZeroConf", services.length + " services (" + type + ") is found.");
			for (ServiceInfo service : services) {
				sendCallback("found", service);
			}
		} catch (Exception ex) {
			ex.printStackTrace();
			Log.e("ZeroConf", ex.toString());
		}
	}
	
	private void setupWatcher() {
		Log.d("ZeroConf", "Setup watcher");
		
		listener = new ServiceListener() {

			public void serviceResolved(ServiceEvent ev) {
				Log.d("ZeroConf", "Resolved");

				sendCallback("added", ev.getInfo());
			}

			public void serviceRemoved(ServiceEvent ev) {
				Log.d("ZeroConf", "Removed");

				sendCallback("removed", ev.getInfo());
			}

			public void serviceAdded(ServiceEvent event) {
				Log.d("ZeroConf", "Added");

				// Force serviceResolved to be called again
				jmdns.requestServiceInfo(event.getType(), event.getName(), 1);
			}
		};
		
	}

    private void setupTypeWatcher() {
        Log.d("ZeroConf", "Setup type watcher");

        typeListener = new ServiceTypeListener() {

            /**
             * Delegate method from mDNS when a new service type is discovered.
             */
            public void serviceTypeAdded(final ServiceEvent event) {
                Log.i("ZeroConf", String.format("ZeroConf serviceTypeAdded(event=\n%s\n)", event.toString()));
                //jmdns.addServiceListener(event.getType(), listener);
            }

            /**
             * Delegate method from mDNS when a subtype is discovered.
             */
            public void subTypeForServiceTypeAdded(ServiceEvent event) {
                Log.i("ZeroConf", String.format("ZeroConf subTypeForServiceTypeAdded(event=\n%s\n)", event.toString()));
            }
        };

    }



	public void sendCallback(String action, ServiceInfo info) {
		JSONObject status = new JSONObject();
		try {
			status.put("action", action);
			status.put("service", jsonifyService(info));
			Log.d("ZeroConf", "Sending result: " + status.toString());
			
			PluginResult result = new PluginResult(PluginResult.Status.OK,
					status);
			result.setKeepCallback(true);
			
			this.callback.sendPluginResult(result);
                		
			Log.d("ZeroConf", "Sending done");

		} catch (JSONException e) {

			e.printStackTrace();
			Log.e("ZeroConf", "Send callback error: " + e.toString());
		}

	}

	public static JSONObject jsonifyService(ServiceInfo info) {
		JSONObject obj = new JSONObject();
		try {
			obj.put("application", info.getApplication());
			obj.put("domain", info.getDomain());
			obj.put("port", info.getPort());
			obj.put("name", info.getName());
			obj.put("server", info.getServer());
			obj.put("description", info.getNiceTextString());
			obj.put("protocol", info.getProtocol());
			obj.put("qualifiedname", info.getQualifiedName());
			obj.put("type", info.getType());

			JSONArray addresses = new JSONArray();
			String[] add = info.getHostAddresses();
			for (int i = 0; i < add.length; i++) {
				addresses.put(add[i]);
			}
			obj.put("addresses", addresses);
			JSONArray urls = new JSONArray();

			String[] url = info.getURLs();
			for (int i = 0; i < url.length; i++) {
				urls.put(url[i]);
			}
			obj.put("urls", urls);

		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}

		return obj;

	}

}
