/**
 * Copyright (c) 2016 Qiscus.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qiscus.sdk.chat.core;

import android.app.Application;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import androidx.annotation.RestrictTo;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.gson.Gson;
import com.qiscus.sdk.chat.core.data.local.QiscusCacheManager;
import com.qiscus.sdk.chat.core.data.local.QiscusDataBaseHelper;
import com.qiscus.sdk.chat.core.data.local.QiscusDataStore;
import com.qiscus.sdk.chat.core.data.model.QiscusAccount;
import com.qiscus.sdk.chat.core.data.model.QiscusCoreChatConfig;
import com.qiscus.sdk.chat.core.data.remote.QiscusApi;
import com.qiscus.sdk.chat.core.event.QiscusUserEvent;
import com.qiscus.sdk.chat.core.service.QiscusNetworkCheckerJobService;
import com.qiscus.sdk.chat.core.service.QiscusSyncJobService;
import com.qiscus.sdk.chat.core.service.QiscusSyncService;
import com.qiscus.sdk.chat.core.util.BuildVersionUtil;
import com.qiscus.sdk.chat.core.util.QiscusErrorLogger;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

/**
 * @author Yuana andhikayuana@gmail.com
 * @since Jul, Wed 25 2018 15.35
 **/
public class QiscusCore {

    private static Application appInstance;
    private static String appId;
    private static String appServer;
    private static String mqttBrokerUrl;
    private static String baseURLLB;
    private static LocalDataManager localDataManager;
    private static long heartBeat;
    private static QiscusDataStore dataStore;
    private static QiscusCoreChatConfig chatConfig;
    private static Handler appHandler;
    private static ScheduledThreadPoolExecutor taskExecutor;
    private static boolean enableMqttLB = true;

    private QiscusCore() {
    }

    /**
     * The first method you need to be invoke to using qiscus sdk. Call this method from your Application
     * class. You can not using another qiscus feature if you not invoke this method first. Here sample
     * to call this method:
     * <pre>
     * {@code
     * public class SampleApps extends Application {
     *  public void onCreate() {
     *      super.onCreate();
     *      QiscusCore.init(this, "yourQiscusAppId");
     *  }
     * }
     * }
     * </pre>
     *
     * @param application Application instance
     * @param qiscusAppId Your qiscus application Id
     */
    public static void init(Application application, String qiscusAppId) {
        initWithCustomServer(application, qiscusAppId, BuildConfig.BASE_URL_SERVER,
                BuildConfig.BASE_URL_MQTT_BROKER, true, BuildConfig.BASE_URL_MQTT_LB);
    }

    /**
     * The first method you need to be invoke to using qiscus sdk. Call this method from your Application
     * class. You can not using another qiscus feature if you not invoke this method first. Here sample
     * to call this method:
     * <pre>
     * {@code
     * public class SampleApps extends Application {
     *  public void onCreate() {
     *      super.onCreate();
     *      QiscusCore.initWithCustomServer(this, my-app-id, "http://myserver.com/", "ssl://mqtt.myserver.com:1885");
     *  }
     * }
     * }
     * </pre>
     *
     * @param application   Application instance
     * @param qiscusAppId   Your Qiscus App Id
     * @param serverBaseUrl Your qiscus chat engine base url
     * @param mqttBrokerUrl Your Mqtt Broker url
     */
    public static void initWithCustomServer(Application application, String qiscusAppId, String serverBaseUrl,
                                            String mqttBrokerUrl, String baseURLLB) {
        if (baseURLLB == null) {
            initWithCustomServer(application, qiscusAppId, serverBaseUrl, mqttBrokerUrl, false, baseURLLB);
        } else {
            initWithCustomServer(application, qiscusAppId, serverBaseUrl, mqttBrokerUrl, true, baseURLLB);
        }
    }

    /**
     * This method @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
     *
     * @param application   Application instance
     * @param qiscusAppId   Your Qiscus App Id
     * @param serverBaseUrl Your qiscus chat engine base url
     * @param mqttBrokerUrl Your Mqtt Broker url
     * @param enableMqttLB  Qiscus using own MQTT Load Balancer for get mqtt server url
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static void initWithCustomServer(Application application, String qiscusAppId, String serverBaseUrl,
                                            String mqttBrokerUrl, boolean enableMqttLB, String baseURLLB) {
        appInstance = application;
        appId = qiscusAppId;
        appServer = !serverBaseUrl.endsWith("/") ? serverBaseUrl + "/" : serverBaseUrl;

        chatConfig = new QiscusCoreChatConfig();
        appHandler = new Handler(QiscusCore.getApps().getApplicationContext().getMainLooper());
        taskExecutor = new ScheduledThreadPoolExecutor(5);
        localDataManager = new LocalDataManager();
        dataStore = new QiscusDataBaseHelper();
        heartBeat = 5000;

        QiscusCore.enableMqttLB = enableMqttLB;
        QiscusCore.mqttBrokerUrl = mqttBrokerUrl;
        QiscusCore.baseURLLB = baseURLLB;
        localDataManager.setURLLB(baseURLLB);
        startPusherService();
        startNetworkCheckerService();
        QiscusCore.getApps().registerActivityLifecycleCallbacks(QiscusActivityCallback.INSTANCE);

        configureFcmToken();
    }

    public static void startPusherService() {
        checkAppIdSetup();
        Application appInstance = QiscusCore.getApps();
        if (BuildVersionUtil.isOreoLower()) {
            try {
                appInstance.getApplicationContext()
                        .startService(new Intent(appInstance.getApplicationContext(), QiscusSyncService.class));
            } catch (IllegalStateException e) {
                //Prevent crash because trying to start service while application on background
                QiscusErrorLogger.print(e);
            } catch (RuntimeException e) {
                //Prevent crash because trying to start service while application on background
                QiscusErrorLogger.print(e);
            }
        } else {
            try {
                appInstance.getApplicationContext()
                        .startService(new Intent(appInstance.getApplicationContext(), QiscusSyncJobService.class));
            } catch (IllegalStateException e) {
                //Prevent crash because trying to start service while application on background
                QiscusErrorLogger.print(e);
            } catch (RuntimeException e) {
                //Prevent crash because trying to start service while application on background
                QiscusErrorLogger.print(e);
            }
        }
    }

    /**
     * start network checker job service if in oreo or higher
     */
    public static void startNetworkCheckerService() {
        if (BuildVersionUtil.isOreoOrHigher()) {
            QiscusNetworkCheckerJobService.scheduleJob(getApps());
        }
    }

    /**
     * Use this method if we need application context instance
     *
     * @return Your application instance
     */
    public static Application getApps() {
        checkAppIdSetup();
        return appInstance;
    }

    /**
     * AppId checker
     *
     * @throws RuntimeException
     */
    public static void checkAppIdSetup() throws RuntimeException {
        if (appServer == null) {
            throw new RuntimeException("Please init Qiscus with your app id before!");
        }
    }

    /**
     * Accessor to get current qiscus app id
     *
     * @return Current app id
     */
    public static String getAppId() {
        checkAppIdSetup();
        return appId;
    }

    /**
     * Accessor to get current qiscus app server
     *
     * @return Current qiscus app server
     */
    public static String getAppServer() {
        checkAppIdSetup();
        return appServer;
    }

    /**
     * isEnableMqttLB
     * Checker for enable or disable own MQTT Load Balancer
     *
     * @return boolean
     */
    public static boolean isEnableMqttLB() {
        checkAppIdSetup();
        return enableMqttLB;
    }

    /**
     * Accessor to get current mqtt broker url
     *
     * @return Current mqtt broker url
     */
    public static String getMqttBrokerUrl() {
        checkAppIdSetup();

        if (localDataManager.getMqttBrokerUrl() == null) {
            localDataManager.setMqttBrokerUrl(mqttBrokerUrl);
        }

        return isEnableMqttLB() ? localDataManager.getMqttBrokerUrl() : mqttBrokerUrl;
    }

    /**
     * Accessor to get current mqtt broker url
     *
     * @return Current mqtt broker url
     */
    public static String getBaseURLLB() {
        checkAppIdSetup();

        if (localDataManager.getURLLB() == null) {
            localDataManager.setURLLB(baseURLLB);
        }

        return isEnableMqttLB() ? localDataManager.getURLLB() : baseURLLB;
    }

    /**
     * this method is used if isEnableMqttLB() == true
     *
     * @param mqttBaseUrl
     */
    public static void setCacheMqttBrokerUrl(String mqttBaseUrl, boolean everConnected) {
        localDataManager.setMqttBrokerUrl(mqttBaseUrl);
        localDataManager.setWillGetNewNodeMqttBrokerUrl(everConnected);
    }

    /**
     * this is mechanism used by MQTT LB
     *
     * @return boolean
     */
    public static boolean willGetNewNodeMqttBrokerUrl() {
        return localDataManager.willGetNewNodeMqttBrokerUrl();
    }

    /**
     * Accessor to get current LocalDataManager
     *
     * @return current localDataManager
     */
    @Deprecated
    public static LocalDataManager getLocalDataManager() {
        checkAppIdSetup();
        return localDataManager;
    }

    /**
     * For checking is qiscus user has been setup
     *
     * @return true if already setup, false if not yet
     */
    public static boolean hasSetupUser() {
        return appServer != null && localDataManager.isLogged();
    }

    /**
     * Accessor to get current qiscus user account
     *
     * @return Current qiscus user account
     */
    public static QiscusAccount getQiscusAccount() {
        checkUserSetup();
        return localDataManager.getAccountInfo();
    }

    /**
     * User Checker
     *
     * @throws RuntimeException
     */
    public static void checkUserSetup() throws RuntimeException {
        checkAppIdSetup();
        if (!hasSetupUser()) {
            throw new RuntimeException("Please set Qiscus user before start the chatting!");
        }
    }

    /**
     * Accessor to get current qiscus user token
     *
     * @return Current qiscus user token
     */
    public static String getToken() {
        checkUserSetup();
        return localDataManager.getToken();
    }

    /**
     * Get the current qiscus heartbeat duration
     *
     * @return Heartbeat duration in milliseconds
     */
    public static long getHeartBeat() {
        return heartBeat;
    }

    /**
     * Set the heartbeat of qiscus synchronization chat data. Default value is 500ms
     *
     * @param heartBeat Heartbeat duration in milliseconds
     */
    public static void setHeartBeat(long heartBeat) {
        checkAppIdSetup();
        QiscusCore.heartBeat = heartBeat;
    }

    /**
     * Get current qiscus chat data store
     *
     * @return Singleton of qiscus data store
     */
    public static QiscusDataStore getDataStore() {
        return dataStore;
    }

    /**
     * Use this method if you want to use your own data store implementation, e.g using Realm, your own table,
     * your own orm, etc
     *
     * @param dataStore Your own chat datastore
     */
    public static void setDataStore(QiscusDataStore dataStore) {
        QiscusCore.dataStore = dataStore;
    }

    /**
     * Use this method to get current qiscus chatting configuration, you can also modify it.
     *
     * @return Current qiscus chatting configuration
     */
    public static QiscusCoreChatConfig getChatConfig() {
        checkAppIdSetup();
        return chatConfig;
    }

    /**
     * Use this method set qiscus user. If user doesn't exist at your qiscus engine, He/She will be
     * registered automatically.
     *
     * @param userEmail The email or username of qiscus user
     * @param userKey   Qiscus user key
     * @return User builder
     */
    public static SetUserBuilder setUser(String userEmail, String userKey) {
        return new SetUserBuilder(userEmail, userKey);
    }

    /**
     * Use this method to set qiscus user with jwt token from your apps backend
     *
     * @param token the jwt token
     * @return observable of qiscus account
     */
    public static Observable<QiscusAccount> setUserAsObservable(String token) {
        return QiscusApi.getInstance()
                .login(token)
                .doOnNext(qiscusAccount -> {
                    if (QiscusCore.hasSetupUser()) {
                        QiscusCore.localDataManager.saveAccountInfo(qiscusAccount);
                        configureFcmToken();
                    } else {
                        QiscusCore.localDataManager.saveAccountInfo(qiscusAccount);
                        configureFcmToken();
                        EventBus.getDefault().post(QiscusUserEvent.LOGIN);
                    }
                });
    }

    /**
     * Use this method to set qiscus user with jwt token from your apps backend
     *
     * @param token    the jwt token
     * @param listener completion listener
     */
    public static void setUser(String token, SetUserListener listener) {
        setUserAsObservable(token)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(listener::onSuccess, listener::onError);
    }

    /**
     * Use this method to update qiscus user data such as name and avatar
     *
     * @param name      user name
     * @param avatarUrl user avatar url
     * @param extras    user extras
     * @return observable of qiscus account
     */
    public static Observable<QiscusAccount> updateUserAsObservable(String name, String avatarUrl, JSONObject extras) {
        return QiscusApi.getInstance().updateProfile(name, avatarUrl, extras)
                .doOnNext(qiscusAccount -> QiscusCore.localDataManager.saveAccountInfo(qiscusAccount));
    }

    /**
     * Use this method to update qiscus user data such as name and avatar
     *
     * @param name      user name
     * @param avatarUrl user avatar url
     * @return observable of qiscus account
     */
    public static Observable<QiscusAccount> updateUserAsObservable(String name, String avatarUrl) {
        return updateUserAsObservable(name, avatarUrl, null);
    }

    /**
     * Use this method to update qiscus user data such as name and avatar
     *
     * @param name      user name
     * @param avatarUrl user avatar url
     * @param extras    user extras
     * @param listener  completion listener
     */
    public static void updateUser(String name, String avatarUrl, JSONObject extras, SetUserListener listener) {
        checkUserSetup();
        updateUserAsObservable(name, avatarUrl, extras)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(listener::onSuccess, listener::onError);
    }

    /**
     * Use this method to update qiscus user data such as name and avatar
     *
     * @param name      user name
     * @param avatarUrl user avatar url
     * @param listener  completion listener
     */
    public static void updateUser(String name, String avatarUrl, SetUserListener listener) {
        updateUser(name, avatarUrl, null, listener);
    }

    /**
     * Will return the exact apps name
     *
     * @return The apps name.
     */
    public static String getAppsName() {
        checkAppIdSetup();
        return appInstance.getApplicationInfo().loadLabel(appInstance.getPackageManager()).toString();
    }

    /**
     * Needed to run something at main thread handler
     *
     * @return Main thread handler
     */
    public static Handler getAppsHandler() {
        checkAppIdSetup();
        return appHandler;
    }

    /**
     * @return current fcm token, null if not set
     */
    public static String getFcmToken() {
        return localDataManager.getFcmToken();
    }

    /**
     * Set the FCM token to configure push notification with firebase cloud messaging
     *
     * @param fcmToken the token
     */
    public static void setFcmToken(String fcmToken) {
        if (hasSetupUser() && getChatConfig().isEnableFcmPushNotification()) {
            QiscusApi.getInstance().registerFcmToken(fcmToken)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(aVoid -> {
                    }, throwable -> QiscusErrorLogger.print("SetFCMToken", throwable));
        }

        localDataManager.setFcmToken(fcmToken);
    }

    private static void configureFcmToken() {
        if (hasSetupUser() && getChatConfig().isEnableFcmPushNotification()) {
            String fcmToken = getFcmToken();
            if (fcmToken != null) {
                setFcmToken(fcmToken);
            } else {
                Observable.just(null)
                        .doOnNext(o -> {
                            try {
                                FirebaseInstanceId.getInstance().deleteInstanceId();
                            } catch (IOException ignored) {
                                //Do nothing
                            }
                        })
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(aVoid -> {
                        }, throwable -> {
                        });
            }
        }
    }

    /**
     * Check is apps opened on foreground
     *
     * @return true if apps on foreground, and false if on background
     */
    public static boolean isOnForeground() {
        return QiscusActivityCallback.INSTANCE.isForeground();
    }

    /**
     * Needed to run something at background thread handler
     *
     * @return ScheduledExecutorService instance
     */
    public static ScheduledThreadPoolExecutor getTaskExecutor() {
        checkAppIdSetup();
        return taskExecutor;
    }

    /**
     * Clear all current user qiscus data, you can call this method when user logout for example.
     */
    public static void clearUser() {
        if (BuildVersionUtil.isOreoOrHigher()) {
            JobScheduler jobScheduler = (JobScheduler) appInstance.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (jobScheduler != null) {
                jobScheduler.cancelAll();
            }
        }
        localDataManager.clearData();
        dataStore.clear();
        QiscusCacheManager.getInstance().clearData();
        EventBus.getDefault().post(QiscusUserEvent.LOGOUT);
    }

    public interface SetUserListener {
        /**
         * Called if saving user succeed
         *
         * @param qiscusAccount Saved qiscus account
         */
        void onSuccess(QiscusAccount qiscusAccount);

        /**
         * Called if error happened while saving qiscus user account. e.g network error
         *
         * @param throwable The cause of error
         */
        void onError(Throwable throwable);
    }

    private static class LocalDataManager {
        private final SharedPreferences sharedPreferences;
        private final Gson gson;
        private String token;

        LocalDataManager() {
            sharedPreferences = QiscusCore.getApps().getSharedPreferences("qiscus.cfg", Context.MODE_PRIVATE);
            gson = new Gson();
            token = isLogged() ? getAccountInfo().getToken() : null;
        }

        private boolean isLogged() {
            return sharedPreferences.contains("cached_account");
        }

        private void saveAccountInfo(QiscusAccount qiscusAccount) {
            sharedPreferences.edit().putString("cached_account", gson.toJson(qiscusAccount)).apply();
            setToken(qiscusAccount.getToken());
        }

        private QiscusAccount getAccountInfo() {
            return gson.fromJson(sharedPreferences.getString("cached_account", ""), QiscusAccount.class);
        }

        private String getToken() {
            return token;
        }

        private void setToken(String token) {
            this.token = token;
        }

        private String getFcmToken() {
            return sharedPreferences.getString("fcm_token", null);
        }

        private void setFcmToken(String fcmToken) {
            sharedPreferences.edit().putString("fcm_token", fcmToken).apply();
        }

        /**
         * this is used if enableMqttLB = true
         *
         * @return mqttBrokerUrl
         */
        private String getMqttBrokerUrl() {
            return sharedPreferences.getString("mqtt_broker_url", null);
        }

        /**
         * this is used if enableMqttLB = true
         *
         * @param mqttBrokerUrl
         */
        private void setMqttBrokerUrl(String mqttBrokerUrl) {
            sharedPreferences.edit().putString("mqtt_broker_url", mqttBrokerUrl).apply();
        }

        /**
         * this is used if enableMqttLB = true
         *
         * @return UrlLB
         */
        private String getURLLB() {
            return sharedPreferences.getString("lb_url", null);
        }

        /**
         * this is used if enableMqttLB = true
         *
         * @param urlLb
         */
        private void setURLLB(String urlLb) {
            sharedPreferences.edit().putString("lb_url", urlLb).apply();
        }

        /**
         * Mechanism for MQTT LB
         *
         * @return boolean
         */
        private boolean willGetNewNodeMqttBrokerUrl() {
            return sharedPreferences.getBoolean("mqtt_will_get_new", true);
        }

        private void setWillGetNewNodeMqttBrokerUrl(boolean will) {
            sharedPreferences.edit().putBoolean("mqtt_will_get_new", will).apply();
        }

        private void clearData() {
            sharedPreferences.edit().clear().apply();
            setToken(null);
        }
    }

    public static class SetUserBuilder {
        private String email;
        private String password;
        private String username;
        private String avatarUrl;
        private JSONObject extras;

        private SetUserBuilder(String email, String password) {
            this.email = email;
            this.password = password;
            this.username = email;
        }

        /**
         * Set the qiscus user name, if you not call this method, the username will be the same with
         * qiscus user email
         *
         * @param username The name
         * @return builder
         */
        public SetUserBuilder withUsername(String username) {
            this.username = username;
            return this;
        }

        /**
         * Set the avatar url for the qiscus user.
         *
         * @param avatarUrl The string url of avatar
         * @return builder
         */
        public SetUserBuilder withAvatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        /**
         * Set user extras to qiscus
         *
         * @param extras JSONObject
         * @return builder
         */
        public SetUserBuilder withExtras(JSONObject extras) {
            this.extras = extras;
            return this;
        }

        /**
         * Submit to qiscus engine and save the user account
         *
         * @param listener Listener of saving user process
         */
        public void save(SetUserListener listener) {
            save().subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(listener::onSuccess, listener::onError);
        }

        /**
         * Submit to qiscus engine and save the user account with RxJava style.
         *
         * @return Observable of Qiscus account
         */
        public Observable<QiscusAccount> save() {
            return QiscusApi.getInstance()
                    .loginOrRegister(email, password, username, avatarUrl, extras)
                    .doOnNext(qiscusAccount -> {
                        if (QiscusCore.hasSetupUser()) {
                            QiscusCore.localDataManager.saveAccountInfo(qiscusAccount);
                            configureFcmToken();
                        } else {
                            QiscusCore.localDataManager.saveAccountInfo(qiscusAccount);
                            configureFcmToken();
                            EventBus.getDefault().post(QiscusUserEvent.LOGIN);
                        }
                    });
        }
    }
}
