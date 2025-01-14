/*
 * Copyright (c) 2016 Qiscus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.qiscus.dragonfly;

import androidx.multidex.MultiDexApplication;

import com.facebook.stetho.Stetho;
import com.qiscus.sdk.Qiscus;
import com.qiscus.sdk.data.model.QiscusDeleteCommentConfig;

import static com.qiscus.dragonfly.BuildConfig.QISCUS_SDK_APP_ID;

/**
 * Created on : August 18, 2016
 * Author     : zetbaitsu
 * Name       : Zetra
 * GitHub     : https://github.com/zetbaitsu
 */
public class SampleApps extends MultiDexApplication {

    @Override
    public void onCreate() {
        super.onCreate();

        Qiscus.init(this, QISCUS_SDK_APP_ID);

        Qiscus.getChatConfig()
                .setEnableLog(true)
                .setEnableAddLocation(false)
                .setDeleteCommentConfig(new QiscusDeleteCommentConfig()
                        .setEnableDeleteComment(true)
                        .setEnableHardDelete(true));

        Stetho.initializeWithDefaults(this);
    }
}
