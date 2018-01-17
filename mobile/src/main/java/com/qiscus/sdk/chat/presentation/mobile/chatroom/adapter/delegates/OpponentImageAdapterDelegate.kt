package com.qiscus.sdk.chat.presentation.mobile.chatroom.adapter.delegates

import android.content.Context
import android.support.v7.util.SortedList
import android.support.v7.widget.RecyclerView
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.qiscus.sdk.chat.domain.common.Patterns
import com.qiscus.sdk.chat.domain.model.FileAttachmentMessage
import com.qiscus.sdk.chat.domain.model.FileAttachmentProgress
import com.qiscus.sdk.chat.presentation.mobile.R
import com.qiscus.sdk.chat.presentation.model.MessageImageViewModel
import com.qiscus.sdk.chat.presentation.model.MessageViewModel
import com.qiscus.sdk.chat.presentation.model.ProgressListener
import com.qiscus.sdk.chat.presentation.model.TransferListener
import com.qiscus.sdk.chat.presentation.uikit.adapter.ItemClickListener
import com.qiscus.sdk.chat.presentation.uikit.adapter.ItemLongClickListener
import com.qiscus.sdk.chat.presentation.uikit.util.ClickSpan
import com.qiscus.sdk.chat.presentation.uikit.util.ClickableMovementMethod
import com.qiscus.sdk.chat.presentation.uikit.util.startCustomTabActivity
import com.qiscus.sdk.chat.presentation.uikit.widget.CircleProgress

/**
 * Created on : December 21, 2017
 * Author     : zetbaitsu
 * Name       : Zetra
 * GitHub     : https://github.com/zetbaitsu
 */
class OpponentImageAdapterDelegate @JvmOverloads constructor(private val context: Context,
                                                             private val itemClickListener: ItemClickListener? = null,
                                                             private val itemLongClickListener: ItemLongClickListener? = null)
    : MessageAdapterDelegate() {

    override fun isForViewType(data: SortedList<MessageViewModel>, position: Int): Boolean {
        val messageViewModel = data[position]
        return messageViewModel is MessageImageViewModel && messageViewModel.message.sender != account.user
    }

    override fun onCreateViewHolder(parent: ViewGroup): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_qiscus_message_img, parent, false)
        return OpponentImageViewHolder(view, itemClickListener, itemLongClickListener)
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        (holder as OpponentImageViewHolder).attach()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as OpponentImageViewHolder).detach()
    }
}

open class OpponentImageViewHolder @JvmOverloads constructor(view: View,
                                                             itemClickListener: ItemClickListener? = null,
                                                             itemLongClickListener: ItemLongClickListener? = null)
    : OpponentMessageViewHolder(view, itemClickListener, itemLongClickListener), ProgressListener, TransferListener {

    private val thumbnailView: ImageView = itemView.findViewById(R.id.thumbnail)
    private val captionView: TextView = itemView.findViewById(R.id.caption)
    private val downloadIconView: ImageView = itemView.findViewById(R.id.iv_download)
    private val progressView: CircleProgress = itemView.findViewById(R.id.progress)

    protected lateinit var messageViewModel: MessageImageViewModel

    init {
        captionView.movementMethod = ClickableMovementMethod.instance
        captionView.isClickable = false
        captionView.isLongClickable = false
    }

    override fun determineDateView(): TextView {
        return itemView.findViewById(R.id.date)
    }

    override fun determineFirstMessageBubbleIndicatorView(): ImageView {
        return itemView.findViewById(R.id.bubble)
    }

    override fun determineBubbleView(): ViewGroup {
        return itemView.findViewById(R.id.message)
    }

    override fun determineTimeView(): TextView {
        return itemView.findViewById(R.id.time)
    }

    override fun determineSenderNameView(): TextView {
        return itemView.findViewById(R.id.name)
    }

    override fun determineSenderAvatarView(): ImageView {
        return itemView.findViewById(R.id.avatar)
    }

    override fun renderMessageContents(messageViewModel: MessageViewModel) {
        this.messageViewModel = messageViewModel as MessageImageViewModel
        messageViewModel.transferListener = this
        messageViewModel.progressListener = this
        renderImage(messageViewModel)
        renderCaption(messageViewModel)
        renderDownloadIcon(messageViewModel)
    }

    open protected fun renderImage(messageViewModel: MessageImageViewModel) {
        if ((messageViewModel.message as FileAttachmentMessage).file != null) {
            Glide.with(thumbnailView)
                    .load((messageViewModel.message as FileAttachmentMessage).file)
                    .apply(RequestOptions().placeholder(R.drawable.qiscus_image_place_holder)
                            .error(R.drawable.qiscus_image_place_holder)
                            .dontAnimate()
                    ).into(thumbnailView)
        } else {
            Glide.with(thumbnailView)
                    .load(messageViewModel.blurryThumbnail)
                    .apply(RequestOptions().placeholder(R.drawable.qiscus_image_place_holder)
                            .error(R.drawable.qiscus_image_place_holder)
                            .dontAnimate()
                    ).into(thumbnailView)
        }
    }

    open protected fun renderCaption(messageViewModel: MessageImageViewModel) {
        if (messageViewModel.spannableCaption.isBlank()) {
            captionView.visibility = View.GONE
        } else {
            captionView.text = messageViewModel.spannableCaption
            captionView.visibility = View.VISIBLE

            val caption = captionView.text.toString()
            val matcher = Patterns.AUTOLINK_WEB_URL.matcher(caption)
            while (matcher.find()) {
                val start = matcher.start()
                if (start > 0 && caption[start - 1] == '@') {
                    continue
                }
                val end = matcher.end()
                clickify(start, end) {
                    var url = caption.substring(start, end)
                    if (!url.startsWith("http")) {
                        url = "http://" + url
                    }
                    captionView.context.startCustomTabActivity(url)
                }
            }
        }
    }

    private fun clickify(start: Int, end: Int, onClick: () -> Unit) {
        val text = captionView.text
        val span = ClickSpan(onClick)

        if (start == -1) {
            return
        }

        if (text is Spannable) {
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            val s = SpannableString.valueOf(text)
            s.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            captionView.text = s
        }
    }

    open protected fun renderDownloadIcon(messageViewModel: MessageImageViewModel) {
        if ((messageViewModel.message as FileAttachmentMessage).file != null) {
            downloadIconView.visibility = View.GONE
            progressView.visibility = View.GONE
        } else {
            onTransfer(messageViewModel.transfer)
            renderProgress(messageViewModel.progress)
        }
    }

    open protected fun renderProgress(attachmentProgress: FileAttachmentProgress?) {
        if (attachmentProgress != null) {
            progressView.progress = attachmentProgress.progress
        }
    }

    override fun onTransfer(transfer: Boolean) {
        if (transfer) {
            progressView.visibility = View.VISIBLE
            downloadIconView.visibility = View.GONE
        } else {
            progressView.visibility = View.GONE
            downloadIconView.visibility = View.VISIBLE
        }
    }

    override fun onProgress(fileAttachmentProgress: FileAttachmentProgress) {
        if (fileAttachmentProgress.fileAttachmentMessage.messageId == messageViewModel.message.messageId) {
            renderProgress(fileAttachmentProgress)
        }
    }

    open fun attach() {
        messageViewModel.listenAttachmentProgress()
    }

    open fun detach() {
        messageViewModel.stopListenAttachmentProgress()
    }
}