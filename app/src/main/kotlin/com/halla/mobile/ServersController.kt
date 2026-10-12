package com.halla.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lista de servidores salvos extraída do MainActivity (refactor do
 * monólito): persistência em SharedPreferences (senha no Keystore via
 * HallaCore), cartões com probe de ping/vagas, formulário de edição,
 * confirmação de fingerprint TLS (TOFU), apelido e quick-connect.
 *
 * Os handlers de conexão da Activity atualizam [lastConnectAttempt] e
 * chamam [promptForNickname]/[updateActiveServerSlots]/[btnConnectStatusNormal].
 */
class ServersController(private val activity: MainActivity) {

    // Servidores salvos persistidos
    internal var savedServers = JSONArray()

    // Última tentativa de conexão a partir de um cartão salvo — usada para
    // repetir a conexão com outro apelido quando o servidor responde
    // name_in_use/bad_nick.
    internal var lastConnectAttempt: JSONObject? = null

    // ============================================================================
    // Gestão de Servidores Salvos (Persistência em SharedPreferences)
    // ============================================================================

    private fun serverPasswordKey(server: JSONObject): String =
        "server-password:${server.optString("host").lowercase()}:${server.optInt("port", 9987)}"

    internal fun loadSavedServers() {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_servers", null)
        if (jsonStr == null) {
            // A instalação começa sem servidores pré-configurados.
            // O usuário adiciona os seus próprios servidores TeamSpeak.
            savedServers = JSONArray()
        } else {
            try {
                savedServers = JSONArray(jsonStr)
                // Migração: remove o servidor Halla que versões antigas
                // inseriam automaticamente. Não tocar nos outros favoritos.
                for (i in savedServers.length() - 1 downTo 0) {
                    val server = savedServers.getJSONObject(i)
                    val isLegacyHallaDefault =
                        server.optString("name").equals("HALLA OFFICIAL SERVER", ignoreCase = true) &&
                        server.optString("host") == "163.176.35.133" &&
                        server.optInt("port", 9987) == 9987
                    if (isLegacyHallaDefault) {
                        savedServers.remove(i)
                        continue
                    }
                    val key = serverPasswordKey(server)
                    val legacy = server.optString("pass", "")
                    if (legacy.isNotEmpty()) HallaCore.storeSecret(activity, key, legacy)
                    server.put("pass", HallaCore.readSecret(activity, key))
                }
                persistServersOnly()
            } catch (e: Exception) {
                savedServers = JSONArray()
            }
        }
        rebuildServerList()
    }

    private fun persistServersOnly() {
        val sanitized = JSONArray()
        for (i in 0 until savedServers.length()) {
            val server = JSONObject(savedServers.getJSONObject(i).toString())
            val password = server.optString("pass", "")
            HallaCore.storeSecret(activity, serverPasswordKey(server), password)
            server.remove("pass")
            sanitized.put(server)
        }
        activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("saved_servers", sanitized.toString())
            .remove("last_srv_pass")
            .apply()
    }

    private fun saveServersToStorage() {
        persistServersOnly()
        rebuildServerList()
    }

    internal fun refreshServerListFromNetwork() {
        if (savedServers.length() == 0) {
            activity.refreshServers.isRefreshing = false
            return
        }
        // Reconstrói os cartões para refletir imediatamente alterações feitas
        // no formulário e depois consulta novamente ping, nome e vagas reais.
        rebuildServerList(startProbe = false)
        pingServersInBackground {
            activity.refreshServers.isRefreshing = false
            Toast.makeText(activity, activity.getString(R.string.server_list_updated), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebuildServerList(startProbe: Boolean = true) {
        activity.containerServers.removeAllViews()

        if (savedServers.length() == 0) {
            activity.layoutEmptyState.visibility = View.VISIBLE
            activity.refreshServers.visibility = View.GONE
        } else {
            activity.layoutEmptyState.visibility = View.GONE
            activity.refreshServers.visibility = View.VISIBLE

            for (i in 0 until savedServers.length()) {
                val srv = savedServers.getJSONObject(i)
                val card = createServerCard(srv, i)
                activity.containerServers.addView(card)
            }
            if (startProbe) pingServersInBackground()
        }
    }

    private fun createServerCard(srv: JSONObject, index: Int): View {
        val context = activity
        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 0, 0, 14)
            layoutParams = lParams
            setPadding(28, 26, 28, 26)

            // Mesma linguagem visual dos canais: superfície neutra, canto
            // generoso, contorno de luz e feedback de toque (ripple).
            val shape = GradientDrawable().apply {
                setColor(Color.parseColor("#16141F"))
                cornerRadius = 20f
                setStroke(activity.dp(1), Color.parseColor("#14FFFFFF"))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#1F8B5CF6")), shape, null)
        }

        // Linha 1: Nome do Servidor (Esquerda) e Três Pontinhos (Direita)
        val row1 = RelativeLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Avatar circular com a inicial do servidor, alinhado com os avatares
        // de usuário dentro dos canais.
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
        }
        val txtSrvAvatar = TextView(context).apply {
            text = srv.getString("name").take(1).uppercase()
            setTextColor(Color.parseColor("#F1EEFA"))
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#3B2A6B"), Color.parseColor("#241B45"))
            ).apply {
                shape = GradientDrawable.OVAL
            }
            layoutParams = LinearLayout.LayoutParams(activity.dp(38), activity.dp(38)).apply {
                setMargins(0, 0, 16, 0)
            }
        }
        val txtSrvTitle = TextView(context).apply {
            text = srv.getString("name")
            setTextColor(Color.parseColor("#F1EEFA"))
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
        }
        titleRow.addView(txtSrvAvatar)
        titleRow.addView(txtSrvTitle)

        val btnOptions = Button(context).apply {
            text = "⋮"
            textSize = 20f
            setTextColor(Color.parseColor("#8E89A8"))
            background = ContextCompat.getDrawable(context, android.R.color.transparent)
            val rParams = RelativeLayout.LayoutParams(
                72, 72
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
            
            setOnClickListener { view ->
                val popup = PopupMenu(context, view)
                popup.menu.add(activity.getString(R.string.server_edit))
                popup.menu.add(activity.getString(R.string.whisper_delete))
                popup.setOnMenuItemClickListener { menuItem ->
                    if (menuItem.title == activity.getString(R.string.server_edit)) {
                        showServerFormDialog(srv)
                    } else if (menuItem.title == activity.getString(R.string.whisper_delete)) {
                        savedServers.remove(index)
                        saveServersToStorage()
                    }
                    true
                }
                popup.show()
            }
        }

        row1.addView(titleRow)
        row1.addView(btnOptions)

        // Linha 2: chips de vagas (esquerda) e ping (direita)
        val row2 = RelativeLayout(context).apply {
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 12, 0, 0)
            layoutParams = lParams
        }

        // Chip de vagas no mesmo estilo dos badges do banner
        val txtStatus = TextView(context).apply {
            val hasProbe = srv.has("onlineClients") && srv.has("maxClients")
            val savedSlots = srv.optString("slots", "0/32")
            text = if (hasProbe) activity.getString(R.string.available_slots, savedSlots)
                   else activity.getString(R.string.searching)
            tag = "slots_text_$index"
            setTextColor(Color.parseColor("#A5B4FC"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(14, 5, 14, 5)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#241F33"))
                cornerRadius = 12f
            }
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
        }

        // Ping em destaque tipográfico (cor aplicada dinamicamente pelo probe)
        val txtPing = TextView(context).apply {
            text = activity.getString(R.string.searching)
            tag = "ping_text_$index"
            setTextColor(Color.parseColor("#8E89A8"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            val rParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            rParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            rParams.addRule(RelativeLayout.CENTER_VERTICAL)
            layoutParams = rParams
        }

        row2.addView(txtStatus)
        row2.addView(txtPing)

        // Linha 3: Ícone Usuário + Apelido (Nickname)
        val row3 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 12, 0, 4)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtUserIcon = TextView(context).apply {
            text = "👤 "
            textSize = 13f
        }
        val txtNickname = TextView(context).apply {
            text = srv.getString("nick")
            setTextColor(Color.parseColor("#E7E5F0"))
            textSize = 14f
        }
        row3.addView(txtUserIcon)
        row3.addView(txtNickname)

        // Linha 4: Ícone Servidor + Endereço IP:Porta
        val row4 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lParams.setMargins(0, 4, 0, 0)
            layoutParams = lParams
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val txtServerIcon = TextView(context).apply {
            text = "🖥️ "
            textSize = 13f
        }
        val txtAddress = TextView(context).apply {
            text = "${srv.getString("host")}:${srv.getInt("port")}"
            setTextColor(Color.parseColor("#8E89A8"))
            textSize = 13f
        }
        row4.addView(txtServerIcon)
        row4.addView(txtAddress)

        // Adiciona todas as linhas ao cartão
        cardLayout.addView(row1)
        cardLayout.addView(row2)
        cardLayout.addView(row3)
        cardLayout.addView(row4)

        // Tapping card triggers connection!
        cardLayout.setOnClickListener {
            connectToSavedServer(srv)
        }

        return cardLayout
    }


    // Formulário de Adicionar / Editar Servidor
    internal fun showServerFormDialog(editSrv: JSONObject?) {
        val context = activity
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#151322"))
        }

        val txtTitle = TextView(context).apply {
            text = if (editSrv != null) activity.getString(R.string.edit_server) else activity.getString(R.string.add_server)
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 24)
        }
        dialogView.addView(txtTitle)

        val inputName = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.server_name_hint)
            setText(editSrv?.optString("name") ?: "")
        }
        dialogView.addView(inputName)

        val inputNick = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.nickname_hint)
            setText(editSrv?.optString("nick") ?: "TS3-GasMObile")
        }
        dialogView.addView(inputNick)

        val inputHost = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.host_hint)
            setText(editSrv?.optString("host") ?: "127.0.0.1")
        }
        dialogView.addView(inputHost)

        val inputPort = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.port_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(editSrv?.optString("port") ?: "9987")
        }
        dialogView.addView(inputPort)

        val inputPass = HallaInputEditText(context).apply {
            hint = activity.getString(R.string.server_password_optional)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(editSrv?.optString("pass") ?: "")
        }
        dialogView.addView(inputPass)

        var selectedUid = editSrv?.optString("identity_uid") ?: ""
        var selectedIdentityName = activity.getString(R.string.default_identity)
        
        val idList = activity.identities.getSavedIdentities()
        for (i in 0 until idList.length()) {
            val idObj = idList.getJSONObject(i)
            if (idObj.getString("uid") == selectedUid) {
                selectedIdentityName = idObj.getString("name")
                break
            }
        }

        val btnSelectIdentity = Button(context).apply {
            text = activity.getString(R.string.identity_label, selectedIdentityName)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_dock_bubble)
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val list = activity.identities.getSavedIdentities()
                val names = Array(list.length()) { "" }
                val uids = Array(list.length()) { "" }
                for (i in 0 until list.length()) {
                    val obj = list.getJSONObject(i)
                    names[i] = obj.getString("name")
                    uids[i] = obj.getString("uid")
                }
                AlertDialog.Builder(context)
                    .setTitle(activity.getString(R.string.choose_identity))
                    .setItems(names) { _, index ->
                        selectedUid = uids[index]
                        text = activity.getString(R.string.identity_label, names[index])
                    }
                    .show()
            }
        }
        dialogView.addView(btnSelectIdentity)

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val btnSave = Button(context).apply {
            text = activity.getString(R.string.save_upper)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setTextColor(Color.parseColor("#FFFFFF"))
            setOnClickListener {
                val name = inputName.text.toString().trim()
                val nick = inputNick.text.toString().trim()
                val host = inputHost.text.toString().trim()
                val portStr = inputPort.text.toString().trim()
                val pass = inputPass.text.toString().trim()

                if (name.isEmpty() || nick.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(context, activity.getString(R.string.required_fields), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val port = portStr.toIntOrNull()
                if (port == null || port !in 1..65535) {
                    inputPort.error = activity.getString(R.string.invalid_port)
                    return@setOnClickListener
                }

                if (editSrv != null) {
                    editSrv.put("name", name)
                    editSrv.put("nick", nick)
                    editSrv.put("host", host)
                    editSrv.put("port", port)
                    editSrv.put("pass", pass)
                    editSrv.put("identity_uid", selectedUid)
                } else {
                    val newSrv = JSONObject().apply {
                        put("name", name)
                        put("nick", nick)
                        put("host", host)
                        put("port", port)
                        put("pass", pass)
                        put("identity_uid", selectedUid)
                        // Até a primeira consulta, o cartão não inventa o
                        // limite: o servidor responderá com o valor real.
                        put("slots", "0/32")
                    }
                    savedServers.put(newSrv)
                }

                saveServersToStorage()
                dialog.dismiss()
            }
        }
        dialogView.addView(btnSave)

        dialog.show()
    }

    private fun connectToSavedServer(srv: JSONObject) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val nick = srv.getString("nick")

        // Entrar sem nome não é permitido: o servidor oficial vem pré-salvo
        // com o apelido vazio justamente para o app perguntar aqui.
        if (nick.isBlank()) {
            promptForNickname(srv)
            return
        }
        connectToSavedServerWithNick(srv, nick)
    }

    // Pede um apelido (campo vazio, servidor devolveu name_in_use/bad_nick)
    // e reconecta em seguida com o nome escolhido.
    internal fun promptForNickname(srv: JSONObject, inUse: Boolean = false) {
        val input = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.nickname_hint)
            setText(srv.optString("nick", ""))
            setSingleLine()
        }
        val container = android.widget.FrameLayout(activity).apply {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val titleRes = if (inUse) R.string.nickname_in_use_title else R.string.choose_nickname_title
        val msgRes = if (inUse) R.string.nickname_in_use_message else R.string.choose_nickname_message
        val serverName = srv.optString("name", srv.optString("host", ""))
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(titleRes))
            .setMessage(activity.getString(msgRes, serverName))
            .setView(container)
            .setPositiveButton(activity.getString(R.string.nickname_confirm)) { dialog, _ ->
                val chosen = input.text.toString().trim()
                if (chosen.isEmpty()) {
                    Toast.makeText(activity, activity.getString(R.string.nickname_required),
                        Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }
                srv.put("nick", chosen)
                saveServersToStorage()
                connectToSavedServerWithNick(srv, chosen)
            }
            .setNegativeButton(activity.getString(R.string.cancel), null)
            .show()
    }


    private fun startTs3Connection(host: String, port: Int, nick: String, password: String) {
        val latestSnapshot = java.util.concurrent.atomic.AtomicReference(
            io.github.ts3mobile.protocol.SessionSnapshot.Empty
        )
        val connected = java.util.concurrent.atomic.AtomicBoolean(false)
        Ts3ConnectionManager.connect(
            activity, host, port, nick, password,
            onStatus = { status ->
                when (status.phase) {
                    io.github.ts3mobile.protocol.ConnectionPhase.CONNECTED -> {
                        val first = connected.compareAndSet(false, true)
                        activity.runOnUiThread {
                            if (first) activity.onConnected(host, "TeamSpeak 3")
                            renderTs3Snapshot(latestSnapshot.get(), nick)
                        }
                    }
                    io.github.ts3mobile.protocol.ConnectionPhase.ERROR -> {
                        val wasConnected = connected.getAndSet(false)
                        activity.runOnUiThread {
                            activity.connectionTimeoutRunnable?.let { activity.handler.removeCallbacks(it) }
                            activity.connectionTimeoutRunnable = null
                            if (wasConnected) activity.onDisconnected() else btnConnectStatusNormal()
                            activity.txtError.text = status.detail ?: "TeamSpeak connection failed"
                            activity.txtError.visibility = View.VISIBLE
                        }
                    }
                    io.github.ts3mobile.protocol.ConnectionPhase.DISCONNECTED -> {
                        if (connected.get()) activity.runOnUiThread {
                            connected.set(false)
                            activity.onDisconnected()
                        }
                    }
                    else -> Unit
                }
            },
            onSnapshot = { snapshot ->
                latestSnapshot.set(snapshot)
                if (connected.get()) activity.runOnUiThread { renderTs3Snapshot(snapshot, nick) }
            },
            onChat = { fromUserId, fromName, scope, targetClientId, text ->
                activity.onChatMessageReceived(scope, fromUserId, targetClientId, fromName, text)
            }
        )
    }

    private fun renderTs3Snapshot(
        snapshot: io.github.ts3mobile.protocol.SessionSnapshot,
        nickname: String
    ) {
        val usersByChannel = snapshot.participants.groupBy { it.channelId }
        val channels = JSONArray()
        for (channel in snapshot.channels) {
            val channelUsers = JSONArray()
            for (user in usersByChannel[channel.id].orEmpty()) {
                channelUsers.put(JSONObject()
                    .put("id", user.id).put("channel", user.channelId)
                    .put("name", user.nickname).put("uid", user.uniqueIdentifier)
                    .put("group", "Client").put("talking", user.isTalking)
                    .put("muted", user.isInputMuted).put("deafened", user.isOutputMuted)
                    .put("cc", false))
            }
            channels.put(JSONObject()
                .put("id", channel.id).put("parent", channel.parentId)
                .put("order", channel.orderAfterId).put("name", channel.name)
                .put("users", channelUsers).put("desc", "")
                .put("hasPassword", channel.hasPassword))
        }
        val users = JSONArray()
        for (user in snapshot.participants) {
            users.put(JSONObject()
                .put("id", user.id).put("channel", user.channelId)
                .put("name", user.nickname).put("uid", user.uniqueIdentifier)
                .put("group", "Client").put("talking", user.isTalking)
                .put("muted", user.isInputMuted).put("deafened", user.isOutputMuted)
                .put("cc", false))
        }
        val selfId = snapshot.ownClientId
            ?: snapshot.participants.firstOrNull { it.nickname == nickname }?.id ?: 0
        val welcome = JSONObject()
            .put("selfId", selfId).put("channels", channels).put("users", users)
            .put("myPerms", JSONObject()).put("groups", JSONArray())
            .put("server", JSONObject().put("maxClients", 32))
        activity.onWelcomeReceived(welcome.toString())
    }

    private fun connectToSavedServerWithNick(srv: JSONObject, nick: String) {
        val host = srv.getString("host")
        val port = srv.getInt("port")
        val pass = srv.optString("pass", "")

        // Guarda a tentativa para poder repetir a conexão com outro apelido
        // quando o servidor recusar (name_in_use/bad_nick).
        lastConnectAttempt = srv

        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_srv_host", host)
            .putInt("last_srv_port", port)
            .putString("last_srv_nick", nick)
            .remove("last_srv_pass").apply()
        HallaCore.storeSecret(activity, "last-server-password", pass)

        activity.txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        // Escopo do cache de ícones de cargo para este servidor.
        activity.activeServerKey = RoleIconCache.serverKey(host, port)

        val uid = if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
            srv.getString("identity_uid")
        } else {
            activity.getOrCreateClientUid()
        }
        startTs3Connection(host, port, nick, pass)

        activity.connectionTimeoutRunnable?.let { activity.handler.removeCallbacks(it) }
        activity.connectionTimeoutRunnable = Runnable {
            if (activity.layoutConnect.visibility == View.VISIBLE) {
                btnConnectStatusNormal()
                val logContent = activity.readLocalDiagnosticsLog()
                activity.txtError.text = activity.getString(R.string.timeout_details, logContent)
                activity.txtError.visibility = View.VISIBLE
            }
        }
        activity.handler.postDelayed(activity.connectionTimeoutRunnable!!, 20000)
    }

    internal fun connectToQuickServer() {
        val prefs = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE)
        val host = prefs.getString("last_srv_host", "") ?: ""
        val port = prefs.getInt("last_srv_port", 0)
        val nick = prefs.getString("last_srv_nick", "") ?: ""
        val legacyPass = prefs.getString("last_srv_pass", "").orEmpty()
        if (legacyPass.isNotEmpty()) {
            HallaCore.storeSecret(activity, "last-server-password", legacyPass)
            prefs.edit().remove("last_srv_pass").apply()
        }
        val pass = HallaCore.readSecret(activity, "last-server-password")

        if (host.isEmpty() || port == 0 || nick.isEmpty()) {
            Toast.makeText(activity, activity.getString(R.string.no_recent_server), Toast.LENGTH_SHORT).show()
            return
        }

        activity.txtError.visibility = View.GONE
        btnConnectStatusConnecting()

        // Escopo do cache de ícones de cargo para este servidor.
        activity.activeServerKey = RoleIconCache.serverKey(host, port)

        var uid = activity.getOrCreateClientUid()
        for (i in 0 until savedServers.length()) {
            val srv = savedServers.getJSONObject(i)
            if (srv.getString("host") == host && srv.getInt("port") == port) {
                if (srv.has("identity_uid") && srv.getString("identity_uid").isNotEmpty()) {
                    uid = srv.getString("identity_uid")
                }
                break
            }
        }
        startTs3Connection(host, port, nick, pass)
    }

    internal fun btnConnectStatusNormal() {
        activity.btnAddServer.isEnabled = true
        activity.btnQuickConnect.isEnabled = true
        activity.btnQuickConnect.text = "➦"
    }

    private fun btnConnectStatusConnecting() {
        activity.btnAddServer.isEnabled = false
        activity.btnQuickConnect.isEnabled = false
        activity.btnQuickConnect.text = "⏳"
    }

    // TS3 has no Halla TLS/server_probe endpoint. Check TCP reachability only;
    // actual slot counts are updated after a successful TS3 session.
    private fun pingServersInBackground(onFinished: (() -> Unit)? = null) {
        val total = savedServers.length()
        if (total == 0) { onFinished?.invoke(); return }
        val remaining = AtomicInteger(total)
        fun finish() {
            if (remaining.decrementAndGet() == 0) activity.runOnUiThread { onFinished?.invoke() }
        }
        for (i in 0 until total) {
            val srv = savedServers.getJSONObject(i)
            val host = srv.optString("host", "")
            val port = srv.optInt("port", 9987)
            thread {
                var socket: java.net.Socket? = null
                val started = android.os.SystemClock.elapsedRealtime()
                try {
                    socket = java.net.Socket()
                    socket.connect(java.net.InetSocketAddress(host, port), 2500)
                    val elapsed = (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                    activity.runOnUiThread { updateServerProbeOnUI(i, "${elapsed}ms", true, null, null) }
                } catch (_: Exception) {
                    activity.runOnUiThread { updateServerProbeOnUI(i, activity.getString(R.string.offline), false, null, null) }
                } finally {
                    try { socket?.close() } catch (_: Exception) { }
                    finish()
                }
            }
        }
    }

    private fun updateServerProbeOnUI(
        index: Int,
        pingText: String,
        online: Boolean,
        clientsCount: Int?,
        maxClients: Int?
    ) {
        val txtPing = activity.containerServers.findViewWithTag<TextView>("ping_text_$index")
        if (txtPing != null) {
            txtPing.text = pingText
            txtPing.setTextColor(Color.parseColor(if (online) "#4CAF50" else "#D9534F"))
        }

        if (index < 0 || index >= savedServers.length()) return
        val srv = savedServers.optJSONObject(index) ?: return
        if (clientsCount != null && maxClients != null) {
            srv.put("onlineClients", clientsCount)
            srv.put("maxClients", maxClients)
            srv.put("slots", "$clientsCount/$maxClients")
            val txtStatus = activity.containerServers.findViewWithTag<TextView>("slots_text_$index")
            txtStatus?.text = activity.getString(R.string.available_slots, "$clientsCount/$maxClients")
            persistServersOnly()
        }
    }

    internal fun updateActiveServerSlots(clientsCount: Int, maxClients: Int) {
        val host = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getString("last_srv_host", "") ?: ""
        val port = activity.getSharedPreferences("HallaPrefs", Context.MODE_PRIVATE).getInt("last_srv_port", 0)
        
        if (host.isEmpty() || port == 0) return

        var modified = false
        for (i in 0 until savedServers.length()) {
            val srv = savedServers.getJSONObject(i)
            if (srv.getString("host") == host && srv.getInt("port") == port) {
                srv.put("onlineClients", clientsCount)
                srv.put("maxClients", maxClients)
                srv.put("slots", "$clientsCount/$maxClients")
                modified = true
                break
            }
        }
        if (modified) {
            persistServersOnly()
        }
    }
}
