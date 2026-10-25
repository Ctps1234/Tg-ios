package com.ctps1234.tgandroid

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TelegramBlue = Color(0xFF2481CC)
private val AppBackground = Color(0xFFF4F6F8)
private val Muted = Color(0xFF858B93)

class MainActivity : ComponentActivity() {
    private lateinit var session: TelegramSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = TelegramSession.get(applicationContext)
        session.startIfConfigured()
        setContent { TelegramApp(session) }
    }

    override fun onStart() {
        super.onStart()
        if (this::session.isInitialized) session.setAppForeground(true)
    }

    override fun onStop() {
        if (this::session.isInitialized) session.setAppForeground(false)
        super.onStop()
    }
}

@Composable
private fun TelegramApp(session: TelegramSession) {
    val state by session.state.collectAsState()
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.authStep) {
        if (state.authStep == AuthStep.READY && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    var search by remember { mutableStateOf("") }
    MaterialTheme(colorScheme = lightColorScheme(primary = TelegramBlue)) {
        Surface(Modifier.fillMaxSize(), color = AppBackground) {
            if (state.authStep != AuthStep.READY) {
                AuthenticationScreen(state, session)
            } else {
                val activeId = state.activeChatId
                if (activeId == null) {
                    ChatListScreen(
                        state = state,
                        search = search,
                        searchResults = state.messageSearchResults,
                        contacts = state.contacts,
                        showContacts = state.showContacts,
                        searchingMessages = state.searchingMessages,
                        messageSearchCompleted = state.messageSearchCompleted,
                        onSearch = { search = it; session.searchMessages("") },
                        onSearchMessages = { session.searchMessages(search) },
                        onOpenSearchResult = { session.openChat(it.chatId, it.messageId) },
                        onToggleContacts = { search = ""; session.toggleContactList() },
                        onOpenContact = { session.openContact(it) },
                        onOpen = { session.openChat(it) },
                        onLoadMore = { session.loadMoreChats() },
                        onTogglePin = { session.togglePinned(it) },
                        onMarkUnread = { session.markChatUnread(it) },
                        onArchiveChat = { chatId, archive -> session.archiveChat(chatId, archive) },
                        onToggleArchived = { session.setArchivedFolder(!state.showArchivedChats) },
                        onLogout = { session.logOut() }
                    )
                } else {
                    BackHandler { session.closeChat() }
                    val chat = state.chats.firstOrNull { it.id == activeId }
                    val messages = state.messagesByChat[activeId].orEmpty()
                    ConversationScreen(
                        title = chat?.title ?: "Telegram",
                        chats = state.chats,
                        stickers = state.stickers,
                        messages = messages,
                        isLoading = state.loadingChatId == activeId,
                        isLoadingOlder = state.loadingOlderChatId == activeId,
                        canLoadOlder = state.canLoadOlderByChat[activeId] == true,
                        error = state.error,
                        onBack = { session.closeChat() },
                        onSend = { text, replyTo -> session.sendText(activeId, text, replyTo) },
                        onSendPhoto = { file, caption, replyTo -> session.sendPhoto(activeId, file, caption, replyTo) },
                        onSendVideo = { file, caption, replyTo -> session.sendVideo(activeId, file, caption, replyTo) },
                        onSendDocument = { file, caption, replyTo -> session.sendDocument(activeId, file, caption, replyTo) },
                        onDownloadAttachment = { session.downloadAttachment(it) },
                        onLoadOlder = { session.loadOlderMessages(activeId) },
                        onDeleteMessage = { session.deleteMessage(activeId, it) },
                        onEditMessage = { messageId, text -> session.editMessageText(activeId, messageId, text) },
                        onForwardMessage = { messageId, targetChatId -> session.forwardMessage(activeId, messageId, targetChatId) },
                        onToggleReaction = { messageId, emoji -> session.toggleReaction(activeId, messageId, emoji) },
                        onSendSticker = { sticker, replyTo -> session.sendSticker(activeId, sticker, replyTo) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthenticationScreen(state: TelegramUiState, session: TelegramSession) {
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    LaunchedEffect(state.authStep) { input = "" }
    Column(Modifier.fillMaxSize().imePadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(76.dp).background(TelegramBlue, CircleShape), contentAlignment = Alignment.Center) {
                Text("✈", color = Color.White, fontSize = 36.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text("Telegram", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(state.status.ifBlank { authDescription(state.authStep) }, color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(22.dp))

            when (state.authStep) {
                AuthStep.CONFIG -> {
                    Text("Conecte usando suas credenciais de desenvolvedor do Telegram.", color = Muted, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(apiId, { apiId = it.filter(Char::isDigit) }, label = { Text("API ID") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiHash, { apiHash = it }, label = { Text("API hash") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { session.configureAndConnect(apiId, apiHash) }, modifier = Modifier.fillMaxWidth()) { Text("Continuar") }
                    Spacer(Modifier.height(10.dp))
                    Text("Obtenha api_id e api_hash em my.telegram.org/apps. Eles identificam o aplicativo; não use credenciais de terceiros.", color = Muted, fontSize = 12.sp)
                }
                AuthStep.PHONE -> {
                    OutlinedTextField(input, { input = it }, label = { Text("Número de telefone") }, placeholder = { Text("+55 22 99999-9999") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { session.submitPhone(input) }, enabled = input.trim().length >= 6, modifier = Modifier.fillMaxWidth()) { Text("Enviar código") }
                }
                AuthStep.CODE, AuthStep.EMAIL_CODE -> {
                    OutlinedTextField(input, { input = it }, label = { Text(if (state.authStep == AuthStep.CODE) "Código do Telegram" else "Código do e-mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { if (state.authStep == AuthStep.CODE) session.submitCode(input) else session.submitEmailCode(input) }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Verificar") }
                }
                AuthStep.PASSWORD -> {
                    OutlinedTextField(input, { input = it }, label = { Text("Senha de verificação em duas etapas") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { session.submitPassword(input) }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Entrar") }
                }
                AuthStep.EMAIL -> {
                    OutlinedTextField(input, { input = it }, label = { Text("E-mail") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { session.submitEmail(input) }, enabled = input.contains('@'), modifier = Modifier.fillMaxWidth()) { Text("Enviar código") }
                }
                AuthStep.REGISTRATION -> {
                    OutlinedTextField(firstName, { firstName = it }, label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(lastName, { lastName = it }, label = { Text("Sobrenome (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { session.submitRegistration(firstName, lastName) }, enabled = firstName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Criar conta") }
                }
                AuthStep.QR_CONFIRMATION -> Text(state.status, color = Muted)
                AuthStep.CONNECTING -> CircularProgressIndicator(color = TelegramBlue)
                AuthStep.READY -> Unit
            }
            state.error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Color(0xFFB3261E), fontSize = 13.sp)
            }
        }
    }
}

private fun authDescription(step: AuthStep): String = when (step) {
    AuthStep.CONFIG -> "Entre com seu número do Telegram"
    AuthStep.CONNECTING -> "Conectando com segurança…"
    AuthStep.PHONE -> "Digite seu número com código do país"
    AuthStep.CODE -> "Verifique suas mensagens do Telegram"
    AuthStep.PASSWORD -> "Autenticação em duas etapas"
    AuthStep.EMAIL -> "Confirme seu e-mail"
    AuthStep.EMAIL_CODE -> "Verifique o código enviado por e-mail"
    AuthStep.REGISTRATION -> "Crie seu perfil"
    AuthStep.QR_CONFIRMATION -> "Confirme em outro dispositivo"
    AuthStep.READY -> ""
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(
    state: TelegramUiState,
    search: String,
    searchResults: List<TelegramSearchResult>,
    contacts: List<TelegramContact>,
    showContacts: Boolean,
    searchingMessages: Boolean,
    messageSearchCompleted: Boolean,
    onSearch: (String) -> Unit,
    onSearchMessages: () -> Unit,
    onOpenSearchResult: (TelegramSearchResult) -> Unit,
    onToggleContacts: () -> Unit,
    onOpenContact: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onTogglePin: (Long) -> Unit,
    onMarkUnread: (Long) -> Unit,
    onArchiveChat: (Long, Boolean) -> Unit,
    onToggleArchived: () -> Unit,
    onLogout: () -> Unit
) {
    var searchVisible by remember { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(when { showContacts -> "Contatos"; state.showArchivedChats -> "Arquivadas"; else -> "Chats" }, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) onSearch("") }) { Icon(Icons.Default.Search, "Pesquisar") }
                    TextButton(onClick = onToggleContacts) { Text(if (showContacts) "Chats" else "Contatos") }
                    if (!showContacts) TextButton(onClick = onToggleArchived) { Text(if (state.showArchivedChats) "Chats" else "Arquivo") }
                    TextButton(onClick = onLogout) { Text("Sair") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color.White)) {
            if (searchVisible) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = onSearch,
                        modifier = Modifier.weight(1f),
                        label = { Text(if (showContacts) "Pesquisar contatos" else "Pesquisar conversas e mensagens") },
                        singleLine = true
                    )
                    if (!showContacts) {
                        TextButton(onClick = onSearchMessages, enabled = search.isNotBlank() && !searchingMessages) {
                            Text(if (searchingMessages) "Buscando…" else "Mensagens")
                        }
                    }
                }
            }
            state.error?.let { Text(it, color = Color(0xFFB3261E), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp) }
            if (showContacts) {
                if (state.loadingContacts) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TelegramBlue) }
                } else {
                    val filteredContacts = contacts.filter { it.name.contains(search, ignoreCase = true) }
                    if (filteredContacts.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhum contato encontrado.", color = Muted) }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filteredContacts, key = { it.userId }) { contact ->
                                ContactRow(contact) { onOpenContact(contact.userId) }
                            }
                        }
                    }
                }
            } else if (searchingMessages || messageSearchCompleted) {
                if (searchingMessages) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = TelegramBlue) }
                } else if (searchResults.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nenhuma mensagem encontrada.", color = Muted) }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(searchResults, key = { "${it.chatId}:${it.messageId}" }) { result ->
                            SearchResultRow(result) { onOpenSearchResult(result) }
                        }
                    }
                }
            } else if (state.chats.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (!state.showArchivedChats || state.error != null) CircularProgressIndicator(color = TelegramBlue)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        when {
                            state.showArchivedChats && state.error == null -> "Nenhuma conversa arquivada"
                            state.error == null -> "Carregando suas conversas…"
                            else -> "Não foi possível carregar as conversas"
                        },
                        color = Muted
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = if (state.showArchivedChats && state.error == null) onToggleArchived else onLoadMore) {
                        Text(if (state.showArchivedChats && state.error == null) "Voltar aos chats" else "Tentar novamente")
                    }
                }
            } else {
                val filtered = state.chats.filter { it.title.contains(search, true) || it.preview.contains(search, true) }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { chat ->
                        ChatRow(
                            chat = chat,
                            onClick = { onOpen(chat.id) },
                            onTogglePin = { onTogglePin(chat.id) },
                            onMarkUnread = { onMarkUnread(chat.id) },
                            onArchive = { onArchiveChat(chat.id, !chat.isArchived) }
                        )
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onLoadMore).padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDownload, null, tint = TelegramBlue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Carregar mais conversas", color = TelegramBlue, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(contact: TelegramContact, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).background(avatarColor(contact.name), CircleShape), contentAlignment = Alignment.Center) {
            Text(contact.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(contact.name, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Divider(Modifier.padding(start = 76.dp), color = Color(0xFFF0F0F0), thickness = 0.5.dp)
}

@Composable
private fun SearchResultRow(result: TelegramSearchResult, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(result.chatTitle, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (result.date > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(result.date * 1000L)) else "", color = Muted, fontSize = 10.sp)
        }
        Text(result.text, color = Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
    Divider(Modifier.padding(start = 16.dp), color = Color(0xFFF0F0F0), thickness = 0.5.dp)
}

@Composable
private fun ChatRow(
    chat: TelegramChat,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onMarkUnread: () -> Unit,
    onArchive: () -> Unit
) {
    var menuExpanded by remember(chat.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).background(avatarColor(chat.title), CircleShape), contentAlignment = Alignment.Center) {
            Text(chat.title.trim().take(1).uppercase().ifBlank { "T" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chat.preview, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(if (chat.date > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(chat.date * 1000L)) else "", color = Muted, fontSize = 11.sp)
            if (chat.unreadCount > 0) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.background(TelegramBlue, CircleShape).padding(horizontal = 7.dp, vertical = 2.dp)) {
                    Text(chat.unreadCount.toString(), color = Color.White, fontSize = 11.sp)
                }
            }
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, "Ações da conversa", tint = Muted) }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (!chat.isArchived) {
                    DropdownMenuItem(
                        text = { Text(if (chat.isPinned) "Desafixar conversa" else "Fixar conversa") },
                        onClick = { menuExpanded = false; onTogglePin() }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Marcar como não lida") },
                    onClick = { menuExpanded = false; onMarkUnread() }
                )
                DropdownMenuItem(
                    text = { Text(if (chat.isArchived) "Desarquivar conversa" else "Arquivar conversa") },
                    onClick = { menuExpanded = false; onArchive() }
                )
            }
        }
    }
    Divider(Modifier.padding(start = 80.dp), color = Color(0xFFF0F0F0), thickness = 0.5.dp)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    title: String,
    chats: List<TelegramChat>,
    stickers: List<TelegramSticker>,
    messages: List<TelegramMessage>,
    isLoading: Boolean,
    isLoadingOlder: Boolean,
    canLoadOlder: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSend: (String, Long?) -> Unit,
    onSendPhoto: (File, String, Long?) -> Unit,
    onSendVideo: (File, String, Long?) -> Unit,
    onSendDocument: (File, String, Long?) -> Unit,
    onDownloadAttachment: (Int) -> Unit,
    onLoadOlder: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onEditMessage: (Long, String) -> Unit,
    onForwardMessage: (Long, Long) -> Unit,
    onToggleReaction: (Long, String) -> Unit,
    onSendSticker: (TelegramSticker, Long?) -> Unit
) {
    var draft by remember(title) { mutableStateOf("") }
    var attachmentDialog by remember { mutableStateOf(false) }
    var stickerDialog by remember { mutableStateOf(false) }
    var preparingAttachment by remember { mutableStateOf(false) }
    var attachmentError by remember(title) { mutableStateOf<String?>(null) }
    var messageActions by remember { mutableStateOf<TelegramMessage?>(null) }
    var replyToMessage by remember { mutableStateOf<TelegramMessage?>(null) }
    var messageToDelete by remember { mutableStateOf<TelegramMessage?>(null) }
    var messageToEdit by remember { mutableStateOf<TelegramMessage?>(null) }
    var messageToForward by remember { mutableStateOf<TelegramMessage?>(null) }
    var forwardSearch by remember { mutableStateOf("") }
    var editedText by remember { mutableStateOf("") }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val messageListState = rememberLazyListState()

    fun prepareAttachment(uri: Uri?, isPhoto: Boolean, cleanupFile: File? = null, asVideo: Boolean = false) {
        if (uri == null) { cleanupFile?.delete(); return }
        scope.launch {
            preparingAttachment = true
            attachmentError = null
            val caption = draft
            val replyId = replyToMessage?.id
            try {
                val file = withContext(Dispatchers.IO) {
                    copyPickedUri(
                        context = context,
                        uri = uri,
                        maxBytes = if (isPhoto) MAX_PHOTO_BYTES else MAX_DOCUMENT_BYTES
                    )
                }
                when {
                    isPhoto -> onSendPhoto(file, caption, replyId)
                    asVideo -> onSendVideo(file, caption, replyId)
                    else -> onSendDocument(file, caption, replyId)
                }
                draft = ""
                replyToMessage = null
            } catch (failure: Exception) {
                attachmentError = failure.message ?: "Não foi possível preparar o anexo."
            } finally {
                cleanupFile?.delete()
                if (cameraFile == cleanupFile) cameraFile = null
                preparingAttachment = false
            }
        }
    }

    val cameraPicker = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraFile
        if (success && file != null) {
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            } catch (_: Exception) {
                Toast.makeText(context, "Não foi possível preparar a câmera.", Toast.LENGTH_SHORT).show()
                file.delete()
                cameraFile = null
                null
            }
            if (uri != null) prepareAttachment(uri, true, file)
        } else {
            file?.delete()
            cameraFile = null
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        prepareAttachment(uri, true)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        prepareAttachment(uri, false, asVideo = true)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        prepareAttachment(uri, false)
    }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) messageListState.animateScrollToItem(messages.lastIndex)
    }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Column { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("Telegram", color = Muted, fontSize = 11.sp) } },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Voltar", tint = TelegramBlue) } }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().background(Color(0xFFF8F8F8))) {
                replyToMessage?.let { target ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Respondendo a ${if (target.outgoing) "você" else title}", color = TelegramBlue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            Text(target.text, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { replyToMessage = null }) { Text("Cancelar") }
                    }
                }
                if (preparingAttachment) Text("Preparando anexo…", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 18.dp, top = 6.dp))
                attachmentError?.let { Text(it, color = Color(0xFFB3261E), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) }
                Row(Modifier.fillMaxWidth().imePadding().padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { stickerDialog = true }, enabled = !preparingAttachment) {
                        Icon(Icons.Default.EmojiEmotions, "Stickers", tint = TelegramBlue)
                    }
                    IconButton(onClick = { attachmentDialog = true }, enabled = !preparingAttachment) {
                        Icon(Icons.Default.AttachFile, "Anexar foto ou arquivo", tint = TelegramBlue)
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Mensagem") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank() && !preparingAttachment) { onSend(draft, replyToMessage?.id); draft = ""; replyToMessage = null } })
                    )
                    IconButton(onClick = { if (draft.isNotBlank() && !preparingAttachment) { onSend(draft, replyToMessage?.id); draft = ""; replyToMessage = null } }, enabled = draft.isNotBlank() && !preparingAttachment) {
                        Icon(Icons.Default.Send, "Enviar", tint = if (draft.isNotBlank()) TelegramBlue else Muted)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(Color(0xFFE9EEF2))) {
            error?.let { Text(it, color = Color(0xFFB3261E), modifier = Modifier.fillMaxWidth().background(Color.White).padding(10.dp), fontSize = 13.sp) }
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isLoading) CircularProgressIndicator(color = TelegramBlue, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(if (isLoading) "Carregando mensagens…" else "Nenhuma mensagem ainda. Envie uma para começar.", color = Muted, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(state = messageListState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), reverseLayout = false, verticalArrangement = Arrangement.Bottom) {
                    if (canLoadOlder || isLoadingOlder) {
                        item(key = "load-older") {
                            TextButton(onClick = onLoadOlder, enabled = !isLoadingOlder, modifier = Modifier.fillMaxWidth()) {
                                if (isLoadingOlder) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text(if (isLoadingOlder) "Carregando mensagens antigas…" else "Carregar mensagens anteriores")
                            }
                        }
                    }
                    items(messages, key = { it.id }) { message ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                            Surface(
                                color = if (message.outgoing) Color(0xFFD9FDD3) else Color.White,
                                shape = RoundedCornerShape(16.dp),
                                shadowElevation = 1.dp,
                                modifier = Modifier.padding(vertical = 3.dp).pointerInput(message.id, message.outgoing) {
                                    detectTapGestures(onLongPress = { messageActions = message })
                                }
                            ) {
                                Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    if (message.fileId != null) {
                                        ReceivedAttachment(message, onDownloadAttachment)
                                    }
                                    Text(message.text, color = Color(0xFF202328), fontSize = 15.sp)
                                    if (message.reactions.isNotEmpty()) {
                                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            message.reactions.take(5).forEach { reaction ->
                                                val selected = message.chosenReactions.any { reaction.startsWith(it) }
                                                Surface(
                                                    color = if (selected) Color(0xFFD6E9F8) else Color(0xFFF0F2F4),
                                                    shape = RoundedCornerShape(12.dp)
                                                ) {
                                                    Text(reaction, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Color(0xFF33383D), fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                        Text(if (message.date > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.date * 1000L)) else "", color = Muted, fontSize = 10.sp)
                                        if (message.outgoing) { Spacer(Modifier.width(3.dp)); Icon(Icons.Default.Check, null, tint = TelegramBlue, modifier = Modifier.size(12.dp)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (stickerDialog) {
        val stickerRows = stickers.chunked(4)
        AlertDialog(
            onDismissRequest = { stickerDialog = false },
            title = { Text("Stickers") },
            text = {
                if (stickerRows.isEmpty()) {
                    Text("Carregando stickers ou nenhum conjunto instalado.", color = Muted)
                } else {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(stickerRows) { _, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                row.forEach { sticker ->
                                    TextButton(onClick = {
                                        onSendSticker(sticker, replyToMessage?.id)
                                        replyToMessage = null
                                        stickerDialog = false
                                    }) {
                                        Text(sticker.emoji.ifBlank { "✨" }, fontSize = 28.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { stickerDialog = false }) { Text("Fechar") } }
        )
    }
    if (attachmentDialog) {
        AlertDialog(
            onDismissRequest = { attachmentDialog = false },
            title = { Text("Anexar") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            attachmentDialog = false
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Escolher foto") }
                    TextButton(
                        onClick = {
                            attachmentDialog = false
                            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Escolher vídeo") }
                    TextButton(
                        onClick = {
                            attachmentDialog = false
                            val directory = File(context.cacheDir, "telegram_camera").apply { mkdirs() }
                            val photo = File(directory, "capture_${System.currentTimeMillis()}.jpg")
                            cameraFile = photo
                            try {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photo)
                                cameraPicker.launch(uri)
                            } catch (_: Exception) {
                                photo.delete()
                                cameraFile = null
                                Toast.makeText(context, "Nenhuma câmera disponível.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tirar foto") }
                    TextButton(
                        onClick = {
                            attachmentDialog = false
                            documentPicker.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Escolher arquivo") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { attachmentDialog = false }) { Text("Cancelar") } }
        )
    }
    messageActions?.let { message ->
        AlertDialog(
            onDismissRequest = { messageActions = null },
            title = { Text(if (message.outgoing) "Mensagem enviada" else "Mensagem recebida") },
            text = {
                Column {
                    Text("Reagir", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("👍", "❤️", "😂", "😮", "😢").forEach { emoji ->
                            TextButton(onClick = {
                                onToggleReaction(message.id, emoji)
                                messageActions = null
                            }) { Text(emoji, fontSize = 20.sp) }
                        }
                    }
                    TextButton(onClick = {
                        replyToMessage = message
                        messageActions = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Responder") }
                    TextButton(onClick = {
                        messageToForward = message
                        forwardSearch = ""
                        messageActions = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Encaminhar") }
                    if (message.outgoing && message.isText) {
                        TextButton(onClick = {
                            editedText = message.text
                            messageToEdit = message
                            messageActions = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("Editar mensagem") }
                    }
                    if (message.outgoing) {
                        TextButton(onClick = {
                            messageToDelete = message
                            messageActions = null
                        }, modifier = Modifier.fillMaxWidth()) { Text("Apagar mensagem", color = Color(0xFFB3261E)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { messageActions = null }) { Text("Cancelar") } }
        )
    }
    messageToEdit?.let { message ->
        AlertDialog(
            onDismissRequest = { messageToEdit = null },
            title = { Text("Editar mensagem") },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditMessage(message.id, editedText)
                    messageToEdit = null
                }, enabled = editedText.isNotBlank()) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { messageToEdit = null }) { Text("Cancelar") } }
        )
    }
    messageToForward?.let { message ->
        val matchingChats = chats.filter { it.title.contains(forwardSearch, ignoreCase = true) }
        AlertDialog(
            onDismissRequest = { messageToForward = null },
            title = { Text("Encaminhar para") },
            text = {
                Column {
                    OutlinedTextField(
                        value = forwardSearch,
                        onValueChange = { forwardSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pesquisar conversa") },
                        singleLine = true
                    )
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(matchingChats, key = { it.id }) { destination ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onForwardMessage(message.id, destination.id)
                                    messageToForward = null
                                }.padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(36.dp).background(avatarColor(destination.title), CircleShape), contentAlignment = Alignment.Center) {
                                    Text(destination.title.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(destination.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (matchingChats.isEmpty()) Text("Nenhuma conversa encontrada", color = Muted, modifier = Modifier.padding(8.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { messageToForward = null }) { Text("Cancelar") } }
        )
    }
    messageToDelete?.let { message ->
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            title = { Text("Apagar mensagem?") },
            text = { Text("A mensagem será apagada para todos, se o Telegram permitir.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteMessage(message.id)
                    messageToDelete = null
                }) { Text("Apagar", color = Color(0xFFB3261E)) }
            },
            dismissButton = { TextButton(onClick = { messageToDelete = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun ReceivedAttachment(message: TelegramMessage, onDownload: (Int) -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(message.localPath) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(message.localPath) {
        val path = message.localPath
        bitmap = if (message.mediaType == "image" && path != null) {
            withContext(Dispatchers.IO) { decodePreview(path)?.asImageBitmap() }
        } else null
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (message.mediaType == "image" && bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = message.fileName ?: "Foto recebida",
                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        if (message.localPath != null) {
            TextButton(onClick = {
                try {
                    val file = File(message.localPath)
                    val extension = file.extension.lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) {
                    Toast.makeText(context, "Não foi possível abrir este arquivo.", Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir ${message.fileName ?: "arquivo"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else if (message.downloadProgress != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                CircularProgressIndicator(progress = { message.downloadProgress ?: 0f }, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Baixando ${((message.downloadProgress * 100).toInt())}%", color = Muted, fontSize = 12.sp)
            }
        } else {
            TextButton(onClick = { message.fileId?.let(onDownload) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Baixar ${message.fileName ?: if (message.mediaType == "image") "foto" else "arquivo"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun decodePreview(path: String): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1200 || bounds.outHeight / sampleSize > 1200) sampleSize *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sampleSize })
}

private fun avatarColor(value: String): Color {
    val palette = listOf(Color(0xFF6485E8), Color(0xFFE58C78), Color(0xFF48A68A), Color(0xFF956FC2), Color(0xFFE1A347), Color(0xFF468FAF))
    return palette[(value.hashCode().toLong().and(0x7fffffffL) % palette.size).toInt()]
}
