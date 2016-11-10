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

package com.qiscus.sdk.presenter;

import android.net.Uri;
import android.webkit.MimeTypeMap;

import com.qiscus.sdk.Qiscus;
import com.qiscus.sdk.data.model.QiscusAccount;
import com.qiscus.sdk.data.model.QiscusChatRoom;
import com.qiscus.sdk.data.model.QiscusComment;
import com.qiscus.sdk.data.remote.QiscusApi;
import com.qiscus.sdk.data.remote.QiscusPusherApi;
import com.qiscus.sdk.event.QiscusChatRoomEvent;
import com.qiscus.sdk.event.QiscusCommentReceivedEvent;
import com.qiscus.sdk.util.QiscusFileUtil;
import com.qiscus.sdk.util.QiscusImageUtil;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

import java.io.File;
import java.util.List;

import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class QiscusChatPresenter extends QiscusPresenter<QiscusChatPresenter.View> {

    private QiscusChatRoom room;
    private int currentTopicId;
    private QiscusAccount qiscusAccount;
    private int lastDeliveredCommentId;
    private int lastReadCommentId;

    public QiscusChatPresenter(View view, QiscusChatRoom room) {
        super(view);
        this.room = room;
        this.currentTopicId = room.getLastTopicId();
        qiscusAccount = Qiscus.getQiscusAccount();

        updateLastReadComment(Qiscus.getDataStore().getLatestReadComment(currentTopicId));
        updateLastDeliveredComment(Qiscus.getDataStore().getLatestDeliveredComment(currentTopicId));

        listenRoomEvent();
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    private void commentSuccess(QiscusComment qiscusComment) {
        qiscusComment.setState(QiscusComment.STATE_ON_QISCUS);
        QiscusComment savedQiscusComment = Qiscus.getDataStore().getComment(qiscusComment.getId(), qiscusComment.getUniqueId());
        if (savedQiscusComment != null) {
            if (savedQiscusComment.getState() < qiscusComment.getState()) {
                Qiscus.getDataStore().addOrUpdate(qiscusComment);
                if (qiscusComment.getTopicId() == currentTopicId) {
                    view.onSuccessSendComment(qiscusComment);
                }
            }
        } else {
            Qiscus.getDataStore().addOrUpdate(qiscusComment);
            if (qiscusComment.getTopicId() == currentTopicId) {
                view.onSuccessSendComment(qiscusComment);
            }
        }
    }

    private void commentFail(QiscusComment qiscusComment) {
        qiscusComment.setState(QiscusComment.STATE_FAILED);
        QiscusComment savedQiscusComment = Qiscus.getDataStore().getComment(qiscusComment.getId(), qiscusComment.getUniqueId());
        if (savedQiscusComment != null) {
            if (savedQiscusComment.getState() < qiscusComment.getState()) {
                qiscusComment.setState(QiscusComment.STATE_FAILED);
                Qiscus.getDataStore().addOrUpdate(qiscusComment);
                if (qiscusComment.getTopicId() == currentTopicId) {
                    view.onFailedSendComment(qiscusComment);
                }
            }
        } else {
            qiscusComment.setState(QiscusComment.STATE_FAILED);
            Qiscus.getDataStore().addOrUpdate(qiscusComment);
            if (qiscusComment.getTopicId() == currentTopicId) {
                view.onFailedSendComment(qiscusComment);
            }
        }
    }

    public void sendComment(String content) {
        QiscusComment qiscusComment = QiscusComment.generateMessage(content, room.getId(), currentTopicId);
        view.onSendingComment(qiscusComment);
        QiscusApi.getInstance().postComment(qiscusComment)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .subscribe(this::commentSuccess, throwable -> commentFail(qiscusComment));
    }

    public void sendFile(File file) {
        File compressedFile = file;
        if (file.getName().endsWith(".gif")) {
            compressedFile = QiscusFileUtil.saveFile(compressedFile, currentTopicId);
        } else if (QiscusImageUtil.isImage(file)) {
            compressedFile = QiscusImageUtil.compressImage(Uri.fromFile(file), currentTopicId);
        } else {
            compressedFile = QiscusFileUtil.saveFile(compressedFile, currentTopicId);
        }

        final QiscusComment qiscusComment = QiscusComment.generateMessage(String.format("[file] %s [/file]", compressedFile.getPath()),
                room.getId(), currentTopicId);
        qiscusComment.setDownloading(true);
        view.onSendingComment(qiscusComment);

        final File finalCompressedFile = compressedFile;
        QiscusApi.getInstance().uploadFile(compressedFile, percentage -> qiscusComment.setProgress((int) percentage))
                .flatMap(uri -> {
                    qiscusComment.setMessage(String.format("[file] %s [/file]", uri.toString()));
                    return QiscusApi.getInstance().postComment(qiscusComment);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .subscribe(commentSend -> {
                    qiscusComment.setDownloading(false);
                    Qiscus.getDataStore()
                            .addOrUpdateLocalPath(commentSend.getTopicId(), commentSend.getId(), finalCompressedFile.getAbsolutePath());
                    commentSuccess(commentSend);
                    view.refreshComment(qiscusComment);
                }, throwable -> {
                    qiscusComment.setDownloading(false);
                    commentFail(qiscusComment);
                });
    }

    public void resendComment(QiscusComment qiscusComment) {
        if (qiscusComment.isAttachment()) {
            resendFile(qiscusComment);
        } else {
            qiscusComment.setState(QiscusComment.STATE_SENDING);
            view.onNewComment(qiscusComment);
            QiscusApi.getInstance().postComment(qiscusComment)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(bindToLifecycle())
                    .subscribe(this::commentSuccess, throwable -> commentFail(qiscusComment));
        }
    }

    private void resendFile(QiscusComment qiscusComment) {
        File file = new File(qiscusComment.getAttachmentUri().toString());
        qiscusComment.setDownloading(true);
        qiscusComment.setState(QiscusComment.STATE_SENDING);
        view.onNewComment(qiscusComment);
        if (!file.exists()) { //Not exist because the uri is not local
            qiscusComment.setProgress(100);
            QiscusApi.getInstance().postComment(qiscusComment)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(bindToLifecycle())
                    .subscribe(commentSend -> {
                        qiscusComment.setDownloading(false);
                        Qiscus.getDataStore()
                                .addOrUpdateLocalPath(commentSend.getTopicId(), commentSend.getId(), file.getAbsolutePath());
                        commentSuccess(commentSend);
                    }, throwable -> {
                        qiscusComment.setDownloading(false);
                        commentFail(qiscusComment);
                    });
        } else {
            qiscusComment.setProgress(0);
            QiscusApi.getInstance().uploadFile(file, percentage -> qiscusComment.setProgress((int) percentage))
                    .flatMap(uri -> {
                        qiscusComment.setMessage(String.format("[file] %s [/file]", uri.toString()));
                        return QiscusApi.getInstance().postComment(qiscusComment);
                    })
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(bindToLifecycle())
                    .subscribe(commentSend -> {
                        qiscusComment.setDownloading(false);
                        Qiscus.getDataStore()
                                .addOrUpdateLocalPath(commentSend.getTopicId(), commentSend.getId(), file.getAbsolutePath());
                        commentSuccess(commentSend);
                    }, throwable -> {
                        qiscusComment.setDownloading(false);
                        commentFail(qiscusComment);
                    });
        }
    }

    public void deleteComment(QiscusComment qiscusComment) {
        Qiscus.getDataStore().delete(qiscusComment);
        view.onCommentDeleted(qiscusComment);
    }

    private Observable<List<QiscusComment>> getCommentsFromNetwork(int lastCommentId) {
        return QiscusApi.getInstance().getComments(currentTopicId, lastCommentId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .doOnNext(qiscusComment -> {
                    qiscusComment.setRoomId(room.getId());
                    if (qiscusComment.getId() > lastDeliveredCommentId) {
                        qiscusComment.setState(QiscusComment.STATE_ON_QISCUS);
                    } else if (qiscusComment.getId() > lastReadCommentId) {
                        qiscusComment.setState(QiscusComment.STATE_DELIVERED);
                    } else {
                        qiscusComment.setState(QiscusComment.STATE_READ);
                    }
                    Qiscus.getDataStore().addOrUpdate(qiscusComment);
                })
                .toList();
    }

    public void loadComments(int count) {
        view.showLoading();
        Qiscus.getDataStore().getObservableComments(currentTopicId, count)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .doOnNext(comments -> {
                    for (QiscusComment qiscusComment : comments) {
                        if (qiscusComment.getState() != QiscusComment.STATE_READ) {
                            if (qiscusComment.getId() > lastDeliveredCommentId) {
                                qiscusComment.setState(QiscusComment.STATE_ON_QISCUS);
                            } else if (qiscusComment.getId() > lastReadCommentId) {
                                qiscusComment.setState(QiscusComment.STATE_DELIVERED);
                            } else {
                                qiscusComment.setState(QiscusComment.STATE_READ);
                            }
                            Qiscus.getDataStore().addOrUpdate(qiscusComment);
                        }
                    }
                })
                .flatMap(comments -> isValidComments(comments) ? Observable.from(comments).toList() : getCommentsFromNetwork(0))
                .subscribe(comments -> {
                    if (view != null) {
                        if (!comments.isEmpty()) {
                            QiscusComment lastComment = comments.get(0);
                            QiscusPusherApi.getInstance().setUserRead(room.getId(), currentTopicId, lastComment.getId());
                        }
                        view.showComments(comments);
                        view.dismissLoading();
                    }
                }, throwable -> {
                    throwable.printStackTrace();
                    if (view != null) {
                        view.showError("Failed to load comments!");
                        view.dismissLoading();
                    }
                });
    }

    private boolean isValidComments(List<QiscusComment> qiscusComments) {
        boolean containsLastValidComment = false;
        int size = qiscusComments.size();

        if (size == 1) {
            return qiscusComments.get(0).getId() == room.getLastCommentId();
        }

        for (int i = 0; i < size - 1; i++) {
            if (qiscusComments.get(i).getId() == -1 || qiscusComments.get(i + 1).getId() == -1) {
                return true;
            }

            if (!containsLastValidComment && qiscusComments.get(i).getId() == room.getLastCommentId()) {
                containsLastValidComment = true;
            }

            if (qiscusComments.get(i).getCommentBeforeId() != qiscusComments.get(i + 1).getId()) {
                return false;
            }
        }
        return containsLastValidComment;
    }

    private boolean isValidOlderComments(List<QiscusComment> qiscusComments, QiscusComment lastQiscusComment) {
        boolean containsLastValidComment = false;
        int size = qiscusComments.size();

        if (size == 1) {
            return qiscusComments.get(0).getCommentBeforeId() == 0;
        }

        for (int i = 0; i < size - 1; i++) {
            if (qiscusComments.get(i).getId() == -1 || qiscusComments.get(i + 1).getId() == -1) {
                return true;
            }

            if (!containsLastValidComment && qiscusComments.get(i).getId() == lastQiscusComment.getCommentBeforeId()) {
                containsLastValidComment = true;
            }

            if (qiscusComments.get(i).getCommentBeforeId() != qiscusComments.get(i + 1).getId()) {
                return false;
            }
        }
        return containsLastValidComment;
    }

    public void loadOlderCommentThan(QiscusComment qiscusComment) {
        view.showLoadMoreLoading();
        Qiscus.getDataStore().getObservableOlderCommentsThan(qiscusComment, currentTopicId, 20)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .compose(bindToLifecycle())
                .doOnNext(comments -> {
                    for (QiscusComment comment : comments) {
                        if (comment.getState() != QiscusComment.STATE_READ) {
                            if (comment.getId() > lastDeliveredCommentId) {
                                comment.setState(QiscusComment.STATE_ON_QISCUS);
                            } else if (comment.getId() > lastReadCommentId) {
                                comment.setState(QiscusComment.STATE_DELIVERED);
                            } else {
                                comment.setState(QiscusComment.STATE_READ);
                            }
                            Qiscus.getDataStore().addOrUpdate(comment);
                        }
                    }
                })
                .flatMap(comments -> isValidOlderComments(comments, qiscusComment) ?
                        Observable.from(comments).toList() : getCommentsFromNetwork(qiscusComment.getId()))
                .subscribe(comments -> {
                    if (view != null) {
                        view.onLoadMore(comments);
                        view.dismissLoading();
                    }
                }, throwable -> {
                    throwable.printStackTrace();
                    if (view != null) {
                        view.showError("Failed to load comments!");
                        view.dismissLoading();
                    }
                });
    }

    private void listenRoomEvent() {
        QiscusPusherApi.getInstance().listenRoom(room);
    }

    @Subscribe
    public void onRoomEvent(QiscusChatRoomEvent event) {
        if (event.getTopicId() == currentTopicId) {
            switch (event.getEvent()) {
                case TYPING:
                    view.onUserTyping(event.getUser(), event.isTyping());
                    break;
                case DELIVERED:
                    QiscusComment deliveredComment = Qiscus.getDataStore()
                            .getComment(event.getCommentId(), String.valueOf(event.getCommentId()));
                    updateCommentState(deliveredComment, QiscusComment.STATE_DELIVERED);
                    updateLastDeliveredComment(deliveredComment);
                    break;
                case READ:
                    QiscusComment readComment = Qiscus.getDataStore()
                            .getComment(event.getCommentId(), String.valueOf(event.getCommentId()));
                    updateCommentState(readComment, QiscusComment.STATE_READ);
                    updateLastReadComment(readComment);
                    break;
            }
        }
    }

    private void updateCommentState(QiscusComment qiscusComment, int state) {
        if (qiscusComment != null && qiscusComment.getState() < state) {
            qiscusComment.setState(state);
            Qiscus.getDataStore().addOrUpdate(qiscusComment);
        }
    }

    private void updateLastReadComment(QiscusComment qiscusComment) {
        if (qiscusComment != null && qiscusComment.getId() > lastReadCommentId) {
            lastReadCommentId = qiscusComment.getId();
            lastDeliveredCommentId = lastReadCommentId;
            view.updateLastReadComment(lastReadCommentId);
        }
    }

    private void updateLastDeliveredComment(QiscusComment qiscusComment) {
        if (qiscusComment != null && qiscusComment.getId() > lastDeliveredCommentId) {
            lastDeliveredCommentId = qiscusComment.getId();
            view.updateLastDeliveredComment(lastDeliveredCommentId);
        }
    }

    @Subscribe
    public void onCommentReceivedEvent(QiscusCommentReceivedEvent event) {
        if (event.getQiscusComment().getTopicId() == currentTopicId) {
            onGotNewComment(event.getQiscusComment());
        }
    }

    private void onGotNewComment(QiscusComment qiscusComment) {
        updateLastReadComment(qiscusComment);
        qiscusComment.setState(QiscusComment.STATE_READ);
        Qiscus.getDataStore().addOrUpdate(qiscusComment);

        if (qiscusComment.isAttachment()) {
            if (QiscusFileUtil.isContains(qiscusComment.getTopicId(), qiscusComment.getAttachmentName())) {
                Qiscus.getDataStore()
                        .addOrUpdateLocalPath(qiscusComment.getTopicId(), qiscusComment.getId(),
                                QiscusFileUtil.generateFilePath(qiscusComment.getAttachmentName(),
                                        qiscusComment.getTopicId()));
            }
        }

        if (qiscusComment.getTopicId() == currentTopicId) {
            if (!qiscusComment.getSenderEmail().equalsIgnoreCase(qiscusAccount.getEmail())) {
                QiscusPusherApi.getInstance().setUserRead(room.getId(), currentTopicId, qiscusComment.getId());
            }
            view.onNewComment(qiscusComment);
        }
    }

    public void downloadFile(final QiscusComment qiscusComment) {
        if (qiscusComment.isDownloading()) {
            return;
        }

        File file = Qiscus.getDataStore().getLocalPath(qiscusComment.getId());
        if (file == null) {
            qiscusComment.setDownloading(true);
            QiscusApi.getInstance()
                    .downloadFile(qiscusComment.getTopicId(), qiscusComment.getAttachmentUri().toString(), qiscusComment.getAttachmentName(),
                            percentage -> qiscusComment.setProgress((int) percentage))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .compose(bindToLifecycle())
                    .doOnNext(file1 -> {
                        if (QiscusImageUtil.isImage(file1)) {
                            QiscusImageUtil.addImageToGallery(file1);
                        }
                    })
                    .subscribe(file1 -> {
                        qiscusComment.setDownloading(false);
                        Qiscus.getDataStore().addOrUpdateLocalPath(qiscusComment.getTopicId(), qiscusComment.getId(),
                                file1.getAbsolutePath());
                        view.refreshComment(qiscusComment);
                    }, throwable -> {
                        throwable.printStackTrace();
                        qiscusComment.setDownloading(false);
                        view.showError("Failed to download file!");
                    });
        } else {
            view.onFileDownloaded(file, MimeTypeMap.getSingleton().getMimeTypeFromExtension(qiscusComment.getExtension()));
        }
    }

    @Override
    public void detachView() {
        super.detachView();
        QiscusPusherApi.getInstance().unListenRoom(room);
        room = null;
        EventBus.getDefault().unregister(this);
    }

    public interface View extends QiscusPresenter.View {

        void showLoadMoreLoading();

        void showComments(List<QiscusComment> qiscusComments);

        void onLoadMore(List<QiscusComment> qiscusComments);

        void onSendingComment(QiscusComment qiscusComment);

        void onSuccessSendComment(QiscusComment qiscusComment);

        void onFailedSendComment(QiscusComment qiscusComment);

        void onNewComment(QiscusComment qiscusComment);

        void onCommentDeleted(QiscusComment qiscusComment);

        void refreshComment(QiscusComment qiscusComment);

        void updateLastDeliveredComment(int lastDeliveredCommentId);

        void updateLastReadComment(int lastReadCommentId);

        void onFileDownloaded(File file, String mimeType);

        void onUserTyping(String user, boolean typing);
    }
}
