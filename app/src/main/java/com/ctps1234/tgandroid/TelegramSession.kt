package com.ctps1234.tgandroid

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class AuthStep { CONFIG, CONNECTING, PHONE, CODE, PASSWORD, EMAIL, EMAIL_CODE, REGISTRATION, QR_CONFIRMATION, READY }

internal data class TelegramChat(
    val id: Long,
    val title: String,
    val preview: String,
    val unreadCount: Int,
    val date: Int,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)

internal data class TelegramContact(
    val userId: Long,
    val name: String
)

internal data class TelegramSticker(
    val fileId: Int,
    val emoji: String,
    val width: Int,
    val height: Int
)

internal data class TelegramSearchResult(
    val chatId: Long,
    val chatTitle: String,
    val messageId: Long,
    val text: String,
    val date: Int
)

internal data class TelegramMessage(
    val id: Long,
    val text: String,
    val outgoing: Boolean,
    val date: Int,
    val isText: Boolean = false,
    val fileId: Int? = null,
    val fileName: String? = null,
    val mediaType: String? = null,
    val reactions: List<String> = emptyList(),
    val chosenReactions: Set<String> = emptySet(),
    val localPath: String? = null,
    val downloadProgress: Float? = null
)

internal data class TelegramUiState(
    val authStep: AuthStep = AuthStep.CONFIG,
    val chats: List<TelegramChat> = emptyList(),
    val contacts: List<TelegramContact> = emptyList(),
    val loadingContacts: Boolean = false,
    val showContacts: Boolean = false,
    val stickers: List<TelegramSticker> = emptyList(),
    val showArchivedChats: Boolean = false,
    val messagesByChat: Map<Long, List<TelegramMessage>> = emptyMap(),
    val messageSearchResults: List<TelegramSearchResult> = emptyList(),
    val searchingMessages: Boolean = false,
    val messageSearchCompleted: Boolean = false,
    val activeChatId: Long? = null,
    val loadingChatId: Long? = null,
    val loadingOlderChatId: Long? = null,
    val canLoadOlderByChat: Map<Long, Boolean> = emptyMap(),
    val error: String? = null,
    val status: String = ""
)

/** A small TDLib adapter: keeps the UI independent of TDLib objects and JNI. */
internal class TelegramSession private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("telegram_config", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        TelegramUiState(authStep = if (savedApiId() != null && savedApiHash().isNotBlank()) AuthStep.CONNECTING else AuthStep.CONFIG)
    )
    val state: StateFlow<TelegramUiState> = _state.asStateFlow()

    @Volatile private var client: Client? = null
    @Volatile private var appForeground: Boolean = false
    @Volatile private var apiId: Int = savedApiId() ?: 0
    @Volatile private var apiHash: String = savedApiHash()
    @Volatile private var searchGeneration: Int = 0
    @Volatile private var stickersRequested: Boolean = false
    @Volatile private var contactsRequested: Boolean = false
    private val chats = linkedMapOf<Long, TdApi.Chat>()
    private val pinnedChatIds = mutableSetOf<Long>()
    private val archivedChatIds = mutableSetOf<Long>()
    private val messages = linkedMapOf<Long, MutableList<TelegramMessage>>()
    private val lock = Any()

    fun startIfConfigured() {
        if (apiId > 0 && apiHash.isNotBlank()) startClient()
    }

    fun setAppForeground(isForeground: Boolean) {
        appForeground = isForeground
    }

    fun configureAndConnect(rawApiId: String, rawApiHash: String) {
        val parsedId = rawApiId.trim().toIntOrNull()
        val hash = rawApiHash.trim()
        if (parsedId == null || parsedId <= 0 || hash.isBlank()) {
            _state.update { it.copy(error = "Informe um API ID válido e o API hash obtidos em my.telegram.org/apps.") }
            return
        }
        apiId = parsedId
        apiHash = hash
        prefs.edit().putInt(KEY_API_ID, parsedId).putString(KEY_API_HASH, hash).apply()
        _state.update { it.copy(authStep = AuthStep.CONNECTING, error = null, status = "Conectando ao Telegram…") }
        startClient()
    }

    private fun startClient() {
        if (client != null) return
        try {
            cleanOldUploadCopies()
            ensureNotificationChannel()
            File(context.filesDir, "tdlib").mkdirs()
            File(context.filesDir, "tdlib_files").mkdirs()
            val created = Client.create(
                { update -> onUpdate(update) },
                { _ -> Unit },
                { throwable -> _state.update { it.copy(error = throwable.message ?: "Erro interno do TDLib") } }
            )
            client = created
            created.send(TdApi.SetLogVerbosityLevel(1), null)
            _state.update { it.copy(authStep = AuthStep.CONNECTING, error = null, status = "Inicializando sessão segura…") }
        } catch (t: Throwable) {
            client = null
            _state.update { it.copy(authStep = AuthStep.CONFIG, error = "Não foi possível iniciar o TDLib: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private fun onUpdate(obj: TdApi.Object) {
        when (obj) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(obj.authorizationState)
            is TdApi.UpdateNewChat -> synchronized(lock) {
                chats[obj.chat.id] = obj.chat
                if (obj.chat.positions.any { it.list is TdApi.ChatListMain && it.isPinned }) pinnedChatIds.add(obj.chat.id)
                if (obj.chat.positions.any { it.list is TdApi.ChatListArchive }) archivedChatIds.add(obj.chat.id)
                else archivedChatIds.remove(obj.chat.id)
                publishChats()
            }
            is TdApi.UpdateChatPosition -> synchronized(lock) {
                when (obj.position.list) {
                    is TdApi.ChatListMain -> {
                        if (obj.position.isPinned) pinnedChatIds.add(obj.chatId) else pinnedChatIds.remove(obj.chatId)
                        archivedChatIds.remove(obj.chatId)
                        publishChats()
                    }
                    is TdApi.ChatListArchive -> {
                        if (obj.position.order > 0) {
                            archivedChatIds.add(obj.chatId)
                            pinnedChatIds.remove(obj.chatId)
                        } else archivedChatIds.remove(obj.chatId)
                        publishChats()
                    }
                    else -> Unit
                }
            }
            is TdApi.UpdateChatTitle -> synchronized(lock) { chats[obj.chatId]?.title = obj.title; publishChats() }
            is TdApi.UpdateChatLastMessage -> synchronized(lock) {
                chats[obj.chatId]?.let { chat -> chat.lastMessage = obj.lastMessage }
                publishChats()
            }
            is TdApi.UpdateChatReadInbox -> synchronized(lock) {
                chats[obj.chatId]?.unreadCount = obj.unreadCount
                publishChats()
            }
            is TdApi.UpdateNewMessage -> {
                addMessage(obj.message)
                if (!obj.message.isOutgoing) {
                    if (_state.value.activeChatId == obj.message.chatId) {
                        send(TdApi.ViewMessages(obj.message.chatId, longArrayOf(obj.message.id), null, true))
                    } else if (!appForeground) {
                        notifyIncomingMessage(obj.message.chatId)
                    }
                }
            }
            is TdApi.UpdateMessageSendSucceeded -> addMessage(obj.message)
            is TdApi.UpdateFile -> updateDownloadedFile(obj.file)
            is TdApi.UpdateMessageContent -> synchronized(lock) {
                val list = messages[obj.chatId]
                val index = list?.indexOfFirst { it.id == obj.messageId } ?: -1
                if (index >= 0 && list != null) {
                    val old = list[index]
                    list[index] = old.copy(text = contentText(obj.newContent))
                    publishMessages(obj.chatId)
                }
            }
            is TdApi.UpdateMessageReactions -> synchronized(lock) {
                val list = messages[obj.chatId]
                val index = list?.indexOfFirst { it.id == obj.messageId } ?: -1
                if (index >= 0 && list != null) {
                    val values = obj.reactions.mapNotNull { reaction ->
                        val emoji = (reaction.type as? TdApi.ReactionTypeEmoji)?.emoji ?: return@mapNotNull null
                        emoji to reaction.totalCount
                    }
                    list[index] = list[index].copy(
                        reactions = values.map { "${it.first} ${it.second}" },
                        chosenReactions = values.filter { (emoji, _) ->
                            obj.reactions.any { it.type is TdApi.ReactionTypeEmoji && it.isChosen && (it.type as TdApi.ReactionTypeEmoji).emoji == emoji }
                        }.map { it.first }.toSet()
                    )
                    publishMessages(obj.chatId)
                }
            }
            is TdApi.UpdateDeleteMessages -> synchronized(lock) {
                messages[obj.chatId]?.removeAll { it.id in obj.messageIds.toSet() }
                publishMessages(obj.chatId)
            }
            else -> Unit
        }
    }

    private fun onAuthorizationState(auth: TdApi.AuthorizationState) {
        when (auth) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                _state.update { it.copy(authStep = AuthStep.CONNECTING, error = null, status = "Configurando o banco local criptografado…") }
                val dbKey = try { encryptedDatabaseKey() } catch (t: Throwable) {
                    _state.update { it.copy(error = "Não foi possível proteger o banco local: ${t.message}") }
                    return
                }
                val db = File(context.filesDir, "tdlib").absolutePath
                val files = File(context.filesDir, "tdlib_files").absolutePath
                val parameters = TdApi.SetTdlibParameters(
                    false, db, files, dbKey,
                    true, true, true, true,
                    apiId, apiHash, "pt-BR", Build.MODEL ?: "Android",
                    "Android ${Build.VERSION.RELEASE ?: ""}", "0.2.0"
                )
                send(parameters)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> setStep(AuthStep.PHONE, "Digite seu número com código do país (ex.: +55…).")
            is TdApi.AuthorizationStateWaitCode -> setStep(AuthStep.CODE, "Digite o código enviado pelo Telegram.")
            is TdApi.AuthorizationStateWaitPassword -> setStep(AuthStep.PASSWORD, "Sua conta usa a verificação em duas etapas.")
            is TdApi.AuthorizationStateWaitEmailAddress -> setStep(AuthStep.EMAIL, "Digite o e-mail solicitado pelo Telegram.")
            is TdApi.AuthorizationStateWaitEmailCode -> setStep(AuthStep.EMAIL_CODE, "Digite o código enviado por e-mail.")
            is TdApi.AuthorizationStateWaitRegistration -> setStep(AuthStep.REGISTRATION, "Informe seu nome para concluir o cadastro.")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> setStep(AuthStep.QR_CONFIRMATION, "Confirme a entrada em outro dispositivo: ${auth.link}")
            is TdApi.AuthorizationStateReady -> {
                _state.update { it.copy(authStep = AuthStep.READY, error = null, status = "Conectado") }
                loadChats()
                loadInstalledStickers()
                loadContacts()
            }
            is TdApi.AuthorizationStateLoggingOut -> setStep(AuthStep.CONNECTING, "Encerrando sessão…")
            is TdApi.AuthorizationStateClosing -> setStep(AuthStep.CONNECTING, "Fechando sessão…")
            is TdApi.AuthorizationStateClosed -> {
                client = null
                if (apiId > 0 && apiHash.isNotBlank()) {
                    _state.update { it.copy(authStep = AuthStep.CONNECTING, error = null, status = "Preparando uma nova sessão…") }
                    startClient()
                } else {
                    setStep(AuthStep.CONFIG, "Sessão encerrada.")
                }
            }
            else -> setStep(AuthStep.CONNECTING, "Aguardando resposta do Telegram…")
        }
    }

    fun submitPhone(phone: String) = send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null))
    fun submitCode(code: String) = send(TdApi.CheckAuthenticationCode(code.trim()))
    fun submitPassword(password: String) = send(TdApi.CheckAuthenticationPassword(password))
    fun submitEmail(email: String) = send(TdApi.SetAuthenticationEmailAddress(email.trim()))
    fun submitEmailCode(code: String) = send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code.trim())))
    fun submitRegistration(firstName: String, lastName: String) = send(TdApi.RegisterUser(firstName.trim(), lastName.trim(), false))

    fun openChat(chatId: Long, aroundMessageId: Long = 0) {
        send(TdApi.OpenChat(chatId))
        synchronized(lock) { messages[chatId] = mutableListOf() }
        _state.update {
            it.copy(
                activeChatId = chatId,
                loadingChatId = chatId,
                loadingOlderChatId = null,
                canLoadOlderByChat = it.canLoadOlderByChat + (chatId to true),
                messagesByChat = it.messagesByChat + (chatId to emptyList()),
                error = null
            )
        }
        send(TdApi.GetChatHistory(chatId, aroundMessageId, 0, 50, false)) { result ->
            if (result is TdApi.Messages) {
                synchronized(lock) {
                    messages[chatId] = mergeMessages(messages[chatId].orEmpty(), result.messages.map(::toMessage))
                    publishMessages(chatId)
                }
                if (result.messages.isNotEmpty()) {
                    send(TdApi.ViewMessages(chatId, result.messages.map { it.id }.toLongArray(), null, true))
                }
                _state.update { it.copy(canLoadOlderByChat = it.canLoadOlderByChat + (chatId to (result.messages.size >= 50))) }
            }
            _state.update { it.copy(loadingChatId = null) }
        }
    }

    fun loadOlderMessages(chatId: Long) {
        val currentState = _state.value
        if (currentState.loadingOlderChatId == chatId || currentState.canLoadOlderByChat[chatId] == false) return
        val firstId = synchronized(lock) { messages[chatId]?.minByOrNull { it.date }?.id }
        if (firstId == null) {
            _state.update { it.copy(canLoadOlderByChat = it.canLoadOlderByChat + (chatId to false)) }
            return
        }
        _state.update { it.copy(loadingOlderChatId = chatId) }
        send(TdApi.GetChatHistory(chatId, firstId, 0, 50, false)) { result ->
            if (result is TdApi.Messages) {
                var loadedAnyNew = false
                synchronized(lock) {
                    val existing = messages[chatId].orEmpty()
                    val merged = mergeMessages(existing, result.messages.map(::toMessage))
                    loadedAnyNew = merged.size > existing.size
                    messages[chatId] = merged
                    publishMessages(chatId)
                }
                val canContinue = result.messages.size >= 50 && loadedAnyNew
                _state.update { it.copy(canLoadOlderByChat = it.canLoadOlderByChat + (chatId to canContinue)) }
            }
            _state.update { it.copy(loadingOlderChatId = null) }
        }
    }

    private fun mergeMessages(existing: List<TelegramMessage>, incoming: List<TelegramMessage>): MutableList<TelegramMessage> =
        (existing.associateBy { it.id } + incoming.associateBy { it.id }).values.sortedBy { it.date }.toMutableList()

    fun closeChat() {
        _state.value.activeChatId?.let { send(TdApi.CloseChat(it)) }
        _state.update { it.copy(activeChatId = null, loadingChatId = null, error = null) }
    }

    fun sendText(chatId: Long, text: String, replyToMessageId: Long? = null) {
        if (text.isBlank()) return
        val content = TdApi.InputMessageText(TdApi.FormattedText(text.trim(), emptyArray()), null, true)
        send(TdApi.SendMessage(chatId, null, createReplyTo(replyToMessageId), null, null, content)) { result ->
            if (result is TdApi.Error) setError(result.message)
        }
    }

    private fun createReplyTo(messageId: Long?): TdApi.InputMessageReplyTo? =
        messageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }

    fun deleteMessage(chatId: Long, messageId: Long) {
        send(TdApi.DeleteMessages(chatId, longArrayOf(messageId), true))
    }

    fun editMessageText(chatId: Long, messageId: Long, text: String) {
        if (text.isBlank()) { setError("A mensagem não pode ficar vazia."); return }
        val content = TdApi.InputMessageText(TdApi.FormattedText(text.trim(), emptyArray()), null, true)
        send(TdApi.EditMessageText(chatId, messageId, null, content))
    }

    fun forwardMessage(fromChatId: Long, messageId: Long, toChatId: Long) {
        send(TdApi.ForwardMessages(toChatId, null, fromChatId, longArrayOf(messageId), null, false, false))
    }

    fun toggleReaction(chatId: Long, messageId: Long, emoji: String) {
        val alreadyChosen = synchronized(lock) {
            messages[chatId]?.firstOrNull { it.id == messageId }?.chosenReactions?.contains(emoji) == true
        }
        if (alreadyChosen) {
            send(TdApi.RemoveMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji(emoji)))
        } else {
            send(TdApi.AddMessageReaction(chatId, messageId, TdApi.ReactionTypeEmoji(emoji), false, true))
        }
    }

    private fun loadInstalledStickers() {
        if (stickersRequested) return
        stickersRequested = true
        send(TdApi.GetInstalledStickerSets(TdApi.StickerTypeRegular())) { result ->
            if (result is TdApi.StickerSets) {
                result.sets.take(8).forEach { setInfo ->
                    send(TdApi.GetStickerSet(setInfo.id)) { setResult ->
                        if (setResult is TdApi.StickerSet) {
                            val options = setResult.stickers.map { sticker ->
                                TelegramSticker(sticker.sticker.id, sticker.emoji, sticker.width, sticker.height)
                            }
                            _state.update { current ->
                                current.copy(stickers = (current.stickers + options).distinctBy { it.fileId }.take(160))
                            }
                        }
                    }
                }
            } else {
                stickersRequested = false
            }
        }
    }

    fun sendSticker(chatId: Long, sticker: TelegramSticker, replyToMessageId: Long? = null) {
        val inputSticker = TdApi.InputSticker(TdApi.InputFileId(sticker.fileId), null, sticker.width, sticker.height)
        val content = TdApi.InputMessageSticker(inputSticker, sticker.emoji)
        send(TdApi.SendMessage(chatId, null, createReplyTo(replyToMessageId), null, null, content))
    }

    private fun loadContacts() {
        if (contactsRequested) return
        contactsRequested = true
        _state.update { it.copy(loadingContacts = true) }
        send(TdApi.GetContacts()) { result ->
            if (result is TdApi.Users) {
                val userIds = result.userIds
                if (userIds.isEmpty()) {
                    _state.update { it.copy(contacts = emptyList(), loadingContacts = false) }
                } else {
                    val remaining = AtomicInteger(userIds.size)
                    userIds.forEach { userId ->
                        send(TdApi.GetUser(userId)) { userResult ->
                            if (userResult is TdApi.User) {
                                val displayName = listOf(userResult.firstName, userResult.lastName).filter { it.isNotBlank() }.joinToString(" ")
                                val contact = TelegramContact(userResult.id, displayName.ifBlank { "Contato" })
                                _state.update { current ->
                                    current.copy(contacts = (current.contacts.filterNot { it.userId == contact.userId } + contact).sortedBy { it.name.lowercase() })
                                }
                            }
                            if (remaining.decrementAndGet() == 0) _state.update { it.copy(loadingContacts = false) }
                        }
                    }
                }
            } else {
                contactsRequested = false
                _state.update { it.copy(loadingContacts = false) }
            }
        }
    }

    fun toggleContactList() {
        val showContacts = !_state.value.showContacts
        _state.update {
            it.copy(
                showContacts = showContacts,
                showArchivedChats = false,
                messageSearchResults = emptyList(),
                searchingMessages = false,
                messageSearchCompleted = false
            )
        }
        if (showContacts && _state.value.contacts.isEmpty()) loadContacts()
    }

    fun openContact(userId: Long) {
        send(TdApi.CreatePrivateChat(userId, true)) { result ->
            if (result is TdApi.Chat) openChat(result.id)
        }
    }

    fun sendPhoto(chatId: Long, file: File, caption: String, replyToMessageId: Long? = null) {
        if (!file.isFile) { setError("A foto selecionada não está disponível."); return }
        val captionText = caption.trim().takeIf { it.isNotEmpty() }?.let { TdApi.FormattedText(it, emptyArray()) }
        val inputPhoto = TdApi.InputPhoto().apply {
            photo = TdApi.InputFileLocal(file.absolutePath)
            thumbnail = null
            addedStickerFileIds = IntArray(0)
            width = 0
            height = 0
        }
        val photo = TdApi.InputMessagePhoto().apply {
            this.photo = inputPhoto
            this.caption = captionText
            showCaptionAboveMedia = false
            selfDestructType = null
            hasSpoiler = false
        }
        send(TdApi.SendMessage(chatId, null, createReplyTo(replyToMessageId), null, null, photo))
    }

    fun sendVideo(chatId: Long, file: File, caption: String, replyToMessageId: Long? = null) {
        if (!file.isFile) { setError("O vídeo selecionado não está disponível."); return }
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000)?.toInt() ?: 0
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val oldWidth = width; width = height; height = oldWidth }
            Triple(duration, width, height)
        } catch (_: Exception) {
            Triple(0, 0, 0)
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
        val inputVideo = TdApi.InputVideo().apply {
            video = TdApi.InputFileLocal(file.absolutePath)
            thumbnail = null
            cover = null
            startTimestamp = 0
            addedStickerFileIds = IntArray(0)
            duration = metadata.first
            width = metadata.second
            height = metadata.third
            supportsStreaming = true
        }
        val content = TdApi.InputMessageVideo(inputVideo, caption.trim(), false, null, false)
        send(TdApi.SendMessage(chatId, null, createReplyTo(replyToMessageId), null, null, content))
    }

    fun sendDocument(chatId: Long, file: File, caption: String, replyToMessageId: Long? = null) {
        if (!file.isFile) { setError("O arquivo selecionado não está disponível."); return }
        val captionText = caption.trim().takeIf { it.isNotEmpty() }?.let { TdApi.FormattedText(it, emptyArray()) }
        val inputDocument = TdApi.InputDocument().apply {
            document = TdApi.InputFileLocal(file.absolutePath)
            thumbnail = null
            disableContentTypeDetection = false
        }
        val document = TdApi.InputMessageDocument().apply {
            this.document = inputDocument
            this.caption = captionText
        }
        send(TdApi.SendMessage(chatId, null, createReplyTo(replyToMessageId), null, null, document))
    }

    fun searchMessages(query: String) {
        val term = query.trim()
        val requestId = ++searchGeneration
        if (term.isBlank()) {
            _state.update { it.copy(messageSearchResults = emptyList(), searchingMessages = false, messageSearchCompleted = false) }
            return
        }
        _state.update { it.copy(messageSearchResults = emptyList(), searchingMessages = true, messageSearchCompleted = false, error = null) }
        send(TdApi.SearchMessages(TdApi.ChatListMain(), term, "", 50, null, null, 0, 0)) { result ->
            if (requestId != searchGeneration) return@send
            val results = if (result is TdApi.Messages) result.messages.map { message ->
                TelegramSearchResult(
                    chatId = message.chatId,
                    chatTitle = synchronized(lock) { chats[message.chatId]?.title }.orEmpty().ifBlank { "Telegram" },
                    messageId = message.id,
                    text = contentText(message.content),
                    date = message.date
                )
            } else emptyList()
            _state.update { it.copy(messageSearchResults = results, searchingMessages = false, messageSearchCompleted = true) }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mensagens do Telegram",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Avisos de novas mensagens quando o app está em segundo plano"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notifyIncomingMessage(chatId: Long) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val chatTitle = synchronized(lock) { chats[chatId]?.title }.orEmpty().ifBlank { "Telegram" }
        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(chatTitle)
            .setContentText("Nova mensagem")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(NOTIFICATION_GROUP)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(chatId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked while TDLib is receiving updates.
        }
    }

    fun loadMoreChats() {
        val chatList = if (_state.value.showArchivedChats) TdApi.ChatListArchive() else TdApi.ChatListMain()
        send(TdApi.LoadChats(chatList, 100))
    }

    fun setArchivedFolder(showArchived: Boolean) {
        _state.update { it.copy(showArchivedChats = showArchived, showContacts = false) }
        synchronized(lock) { publishChats() }
        val chatList = if (showArchived) TdApi.ChatListArchive() else TdApi.ChatListMain()
        send(TdApi.LoadChats(chatList, 100))
    }

    fun archiveChat(chatId: Long, archive: Boolean) {
        val targetList = if (archive) TdApi.ChatListArchive() else TdApi.ChatListMain()
        send(TdApi.AddChatToList(chatId, targetList)) { result ->
            if (result is TdApi.Ok) synchronized(lock) {
                if (archive) {
                    archivedChatIds.add(chatId)
                    pinnedChatIds.remove(chatId)
                } else archivedChatIds.remove(chatId)
                publishChats()
            }
        }
    }

    fun togglePinned(chatId: Long) {
        val shouldPin = synchronized(lock) { chatId !in pinnedChatIds }
        send(TdApi.ToggleChatIsPinned(TdApi.ChatListMain(), chatId, shouldPin)) { result ->
            if (result is TdApi.Ok) synchronized(lock) {
                if (shouldPin) pinnedChatIds.add(chatId) else pinnedChatIds.remove(chatId)
                publishChats()
            }
        }
    }

    fun markChatUnread(chatId: Long) {
        send(TdApi.ToggleChatIsMarkedAsUnread(chatId, true))
    }

    fun logOut() { send(TdApi.LogOut()) }

    private fun loadChats() {
        synchronized(lock) { chats.clear(); messages.clear(); publishChats() }
        send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
        send(TdApi.GetMe()) { result ->
            if (result is TdApi.Error) setError(result.message)
        }
    }

    private fun addMessage(message: TdApi.Message) = synchronized(lock) {
        val list = messages.getOrPut(message.chatId) { mutableListOf() }
        val converted = toMessage(message)
        val existing = list.indexOfFirst { it.id == converted.id }
        if (existing >= 0) list[existing] = converted else list.add(converted)
        list.sortBy { it.date }
        publishMessages(message.chatId)
        chats[message.chatId]?.let { chat -> val currentLastMessage = chat.lastMessage
            if (currentLastMessage == null || currentLastMessage.id <= message.id) chat.lastMessage = message }
        publishChats()
    }

    private fun toMessage(message: TdApi.Message): TelegramMessage {
        val attachment = attachmentInfo(message.content)
        return TelegramMessage(
            id = message.id,
            text = contentText(message.content),
            outgoing = message.isOutgoing,
            date = message.date,
            isText = message.content is TdApi.MessageText,
            fileId = attachment?.first?.id,
            fileName = attachment?.second,
            mediaType = attachment?.third,
            localPath = attachment?.first?.let { file -> file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() } },
            downloadProgress = attachment?.first?.let { file ->
                if (file.local.isDownloadingActive || file.local.isDownloadingCompleted) {
                    if (file.size > 0) (file.local.downloadedSize.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                } else null
            }
        )
    }

    private fun attachmentInfo(content: TdApi.MessageContent): Triple<TdApi.File, String?, String>? = when (content) {
        is TdApi.MessagePhoto -> content.photo?.sizes?.lastOrNull()?.photo?.let { Triple(it, "photo.jpg", "image") }
        is TdApi.MessageVideo -> content.video?.video?.let { Triple(it, content.video.fileName, "video") }
        is TdApi.MessageDocument -> content.document?.document?.let { Triple(it, content.document.fileName, "document") }
        is TdApi.MessageAudio -> content.audio?.audio?.let { Triple(it, content.audio.fileName, "audio") }
        is TdApi.MessageVoiceNote -> content.voiceNote?.voice?.let { Triple(it, "Mensagem de voz", "audio") }
        is TdApi.MessageSticker -> content.sticker?.sticker?.let { Triple(it, "sticker.webp", "image") }
        is TdApi.MessageAnimation -> content.animation?.animation?.let { Triple(it, content.animation.fileName, "animation") }
        else -> null
    }

    private fun updateDownloadedFile(file: TdApi.File) = synchronized(lock) {
        messages.forEach { (chatId, list) ->
            var changed = false
            list.indices.forEach { index ->
                if (list[index].fileId == file.id) {
                    val local = file.local
                    list[index] = list[index].copy(
                        localPath = local.path.takeIf { local.isDownloadingCompleted && it.isNotBlank() },
                        downloadProgress = if (local.isDownloadingActive || local.isDownloadingCompleted) {
                            if (file.size > 0) (local.downloadedSize.toFloat() / file.size).coerceIn(0f, 1f) else 0f
                        } else null
                    )
                    changed = true
                }
            }
            if (changed) publishMessages(chatId)
        }
    }

    fun downloadAttachment(fileId: Int) {
        send(TdApi.DownloadFile(fileId, 16, 0, 0, false))
    }

    private fun contentText(content: TdApi.MessageContent): String = when (content) {
        is TdApi.MessageText -> content.text.text
        is TdApi.MessagePhoto -> content.caption?.text?.takeIf { it.isNotBlank() } ?: "📷 Foto"
        is TdApi.MessageVideo -> content.caption?.text?.takeIf { it.isNotBlank() } ?: "▶ Vídeo"
        is TdApi.MessageDocument -> content.caption?.text?.takeIf { it.isNotBlank() }
            ?: content.document?.fileName?.takeIf { it.isNotBlank() }?.let { "📎 $it" } ?: "📎 Arquivo"
        is TdApi.MessageAnimation -> content.caption?.text?.takeIf { it.isNotBlank() } ?: "GIF"
        is TdApi.MessageSticker -> content.sticker?.emoji?.takeIf { it.isNotBlank() } ?: "Sticker"
        is TdApi.MessageAudio -> content.caption?.text?.takeIf { it.isNotBlank() } ?: "🎵 Áudio"
        is TdApi.MessageVoiceNote -> content.caption?.text?.takeIf { it.isNotBlank() } ?: "🎤 Mensagem de voz"
        else -> "Mensagem"
    }

    private fun publishChats() {
        val showArchived = _state.value.showArchivedChats
        val snapshot = chats.values
            .filter { (it.id in archivedChatIds) == showArchived }
            .map { chat ->
                val last = chat.lastMessage
                TelegramChat(
                    chat.id,
                    chat.title ?: "",
                    last?.let { contentText(it.content) } ?: "Sem mensagens",
                    chat.unreadCount,
                    last?.date ?: 0,
                    chat.id in pinnedChatIds,
                    chat.id in archivedChatIds
                )
            }
            .sortedWith(compareByDescending<TelegramChat> { it.isPinned }.thenByDescending { it.date }.thenBy { it.title.lowercase() })
        _state.update { it.copy(chats = snapshot) }
    }

    private fun publishMessages(chatId: Long) {
        val snapshot = messages[chatId]?.toList().orEmpty()
        _state.update { it.copy(messagesByChat = it.messagesByChat + (chatId to snapshot)) }
    }

    private fun setStep(step: AuthStep, status: String) = _state.update { it.copy(authStep = step, status = status, error = null) }
    private fun setError(error: String) = _state.update { it.copy(error = error, status = "") }

    private fun send(function: TdApi.Function<*>, onResult: ((TdApi.Object) -> Unit)? = null) {
        val activeClient = client
        if (activeClient == null) {
            setError("Cliente ainda não está conectado.")
            return
        }
        try {
            activeClient.send(function, Client.ResultHandler { result ->
                if (result is TdApi.Error) setError("${result.code}: ${result.message}")
                onResult?.invoke(result)
            })
        } catch (t: Throwable) {
            setError(t.message ?: "Falha ao enviar solicitação ao Telegram.")
        }
    }

    /** Picker sources are copied because TDLib's InputFileLocal requires a stable filesystem path. */
    private fun cleanOldUploadCopies() {
        val uploadDir = File(context.cacheDir, UPLOAD_CACHE_DIR)
        val cutoff = System.currentTimeMillis() - UPLOAD_CACHE_RETENTION_MS
        uploadDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun savedApiId(): Int? = prefs.getInt(KEY_API_ID, 0).takeIf { it > 0 }
    private fun savedApiHash(): String = prefs.getString(KEY_API_HASH, "").orEmpty()

    /** Keep the local TDLib database key encrypted by an AES key held in Android Keystore. */
    private fun encryptedDatabaseKey(): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secretKey = if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey
        } else {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generator.generateKey()
        }
        val saved = prefs.getString(KEY_DB_KEY, null)
        if (saved != null) {
            val all = Base64.decode(saved, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = all.copyOfRange(GCM_IV_BYTES, all.size)
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                doFinal(encrypted)
            }
        }
        val raw = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val stored = cipher.iv + cipher.doFinal(raw)
        prefs.edit().putString(KEY_DB_KEY, Base64.encodeToString(stored, Base64.NO_WRAP)).apply()
        return raw
    }

    companion object {
        private const val KEY_API_ID = "api_id"
        private const val KEY_API_HASH = "api_hash"
        private const val KEY_DB_KEY = "tdlib_db_key_encrypted"
        private const val KEYSTORE_ALIAS = "telegram_tdlib_database_key"
        private const val GCM_IV_BYTES = 12
        private const val UPLOAD_CACHE_DIR = "telegram_uploads"
        private const val UPLOAD_CACHE_RETENTION_MS = 48L * 60L * 60L * 1000L
        private const val NOTIFICATION_CHANNEL_ID = "telegram_messages"
        private const val NOTIFICATION_GROUP = "telegram_conversations"
        @Volatile private var instance: TelegramSession? = null
        fun get(context: Context): TelegramSession = instance ?: synchronized(this) {
            instance ?: TelegramSession(context.applicationContext).also { instance = it }
        }
    }
}
