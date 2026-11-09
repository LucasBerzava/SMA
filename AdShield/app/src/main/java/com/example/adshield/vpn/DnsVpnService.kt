package com.example.adshield.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.example.adshield.R
import com.example.adshield.core.PacketDns
import com.example.adshield.data.Blocklists
import com.example.adshield.data.DohUpstream
import com.example.adshield.data.FallbackUpstream
import com.example.adshield.data.FilterEngine
import com.example.adshield.data.Settings
import com.example.adshield.data.Stats
import com.example.adshield.data.UdpUpstream
import com.example.adshield.data.Upstream
import com.example.adshield.data.Upstreams
import com.example.adshield.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * VPN local care primește DOAR traficul DNS (rutăm doar adresele serverului DNS virtual).
 * Restul traficului merge direct pe rețea, deci viteza și bateria nu sunt afectate.
 * Cererile către domenii de reclame primesc 0.0.0.0 / NXDOMAIN; celelalte sunt trimise
 * mai departe prin DNS criptat (DoH) sau UDP.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "AdShield"
        const val ACTION_START = "com.example.adshield.START"
        const val ACTION_STOP = "com.example.adshield.STOP"
        const val ACTION_RESTART = "com.example.adshield.RESTART"
        private const val CHANNEL = "adshield_vpn"
        private const val NOTIF_ID = 100

        private const val ADDR4 = "10.111.0.2"
        private const val DNS4 = "10.111.0.1"
        private const val ADDR6 = "fd00:a5a5::2"
        private const val DNS6 = "fd00:a5a5::1"
        private const val MTU = 4000

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** Pornire (după ce VpnService.prepare() a fost aprobat). */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
        }

        /** Aplică setări care cer reconstruirea VPN-ului (aplicații excluse, server DNS). */
        fun restartIfRunning(ctx: Context) {
            if (_running.value) ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_RESTART))
        }
    }

    /** O instanță a interfeței tun. La repornire se creează alta, iar cea veche se închide singură. */
    private class Session(val fd: ParcelFileDescriptor, val upstream: Upstream, val nullIp: Boolean) {
        @Volatile var active = true
        private val out = FileOutputStream(fd.fileDescriptor)
        fun write(packet: ByteArray) = synchronized(this) {
            if (active) try { out.write(packet) } catch (_: IOException) {}
        }
        fun close() = synchronized(this) {
            active = false
            try { fd.close() } catch (_: IOException) {}
        }
    }

    @Volatile private var session: Session? = null
    private lateinit var pool: ThreadPoolExecutor
    private lateinit var scheduler: ScheduledExecutorService
    private val appNames = ConcurrentHashMap<Int, String>()
    private var lastNotified = -1L

    override fun onCreate() {
        super.onCreate()
        Stats.load(this)
        pool = ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, LinkedBlockingQueue(512), ThreadPoolExecutor.DiscardOldestPolicy())
            .apply { allowCoreThreadTimeOut(true) }
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler.scheduleWithFixedDelay({ periodic() }, 15, 15, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Settings(this).wantRunning = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                if (session != null) { goForeground(); establish() }
                return START_STICKY
            }
            else -> {
                // ACTION_START, "always-on VPN" (android.net.VpnService) sau repornire STICKY (intent null)
                goForeground()
                if (session == null) {
                    Stats.startSession()
                    Settings(this).wantRunning = true
                    establish()
                }
                return START_STICKY
            }
        }
    }

    private fun establish() {
        stopSession()
        if (prepare(this) != null) { Log.w(TAG, "Permisiunea VPN lipsește"); shutdown(); return }
        val settings = Settings(this)
        val builder = Builder()
            .setSession("AdShield")
            .addAddress(ADDR4, 32)
            .addRoute(DNS4, 32)
            .addDnsServer(DNS4)
            .setMtu(MTU)
            .setBlocking(true)
            .setConfigureIntent(openAppIntent())
        try { builder.addAddress(ADDR6, 128).addRoute(DNS6, 128).addDnsServer(DNS6) } catch (e: Exception) { Log.w(TAG, "IPv6 indisponibil", e) }
        // Propria aplicație ocolește VPN-ul: descărcarea listelor și DoH merg direct, fără buclă.
        try { builder.addDisallowedApplication(packageName) } catch (_: PackageManager.NameNotFoundException) {}
        for (pkg in settings.excludedApps) {
            try { builder.addDisallowedApplication(pkg) } catch (_: PackageManager.NameNotFoundException) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false) // contorizat doar dacă rețeaua reală e contorizată

        val fd = try { builder.establish() } catch (e: Exception) { Log.e(TAG, "establish a eșuat", e); null }
        if (fd == null) { shutdown(); return }

        val s = Session(fd, buildUpstream(settings), settings.nullIpResponse)
        session = s
        _running.value = true
        Thread({ readLoop(s) }, "adshield-tun").start()
        pool.execute {
            FilterEngine.ensureLoaded(this)
            if (Blocklists.needsUpdate(this, settings.enabledLists)) Blocklists.update(this, force = false)
        }
        updateNotification(force = true)
    }

    private fun buildUpstream(settings: Settings): Upstream {
        val opt = Upstreams.option(settings.upstream)
        val protector: (java.net.DatagramSocket) -> Boolean = { protect(it) }
        if (opt.id == Upstreams.SYSTEM_ID) return UdpUpstream({ systemDnsServers() }, protector)
        val udp = UdpUpstream({ opt.udp.map { InetAddress.getByName(it) } }, protector)
        return if (settings.useDoh && opt.doh != null) FallbackUpstream(DohUpstream(opt.doh), udp) else udp
    }

    /** Serverele DNS ale rețelei reale (Wi-Fi / date mobile), nu ale VPN-ului nostru. */
    private fun systemDnsServers(): List<InetAddress> {
        val cm = getSystemService(ConnectivityManager::class.java)!!
        @Suppress("DEPRECATION")
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        for (n in networks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val servers = cm.getLinkProperties(n)?.dnsServers.orEmpty()
            if (servers.isNotEmpty()) return servers.sortedBy { if (it is Inet4Address) 0 else 1 }
        }
        return listOf(InetAddress.getByName("1.1.1.1"))
    }

    private fun readLoop(s: Session) {
        val input = FileInputStream(s.fd.fileDescriptor)
        val buffer = ByteArray(32767)
        val pollFd = StructPollfd().apply { fd = s.fd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        try {
            while (s.active) {
                pollFd.revents = 0
                val ready = try { Os.poll(arrayOf(pollFd), 500) } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else throw e
                }
                if (!s.active) break
                if (ready == 0) continue
                val rev = pollFd.revents.toInt()
                if (rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
                val length = input.read(buffer)
                if (length <= 0) break
                val query = PacketDns.parse(buffer, length) ?: continue
                if (query.destinationPort != 53) continue
                try { pool.execute { handle(s, query) } } catch (_: RejectedExecutionException) { break }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Bucla tun s-a oprit: ${e.message}")
        } finally {
            s.close()
            if (session === s) { // închisă din exterior (ex. alt VPN a preluat controlul)
                session = null
                _running.value = false
            }
        }
    }

    private fun handle(s: Session, query: PacketDns.Query) {
        val question = PacketDns.question(query.dns)
        if (question == null) {
            // Cerere nestandard: o trimitem mai departe neatinsă.
            try { s.write(PacketDns.buildPacket(query, s.upstream.resolve(query.dns))) } catch (_: Exception) {}
            return
        }
        val decision = FilterEngine.decide(question.name)
        val blocked = decision == FilterEngine.Decision.BLOCKED || decision == FilterEngine.Decision.USER_BLOCKED
        Stats.record(Stats.Entry(System.currentTimeMillis(), question.name, question.type, blocked, appFor(query)))

        var response = if (blocked) {
            PacketDns.blockedResponse(query.dns, question, s.nullIp)
        } else {
            try { s.upstream.resolve(query.dns) } catch (e: Exception) {
                Log.d(TAG, "Upstream a eșuat pentru ${question.name}: ${e.message}")
                PacketDns.errorResponse(query.dns, question, PacketDns.RCODE_SERVFAIL)
            }
        }
        if (response.size + 48 > MTU) response = PacketDns.truncated(query.dns, question)
        s.write(PacketDns.buildPacket(query, response))
    }

    /** Ce aplicație a făcut cererea (Android 10+; doar aplicațiile VPN au voie să întrebe). */
    private fun appFor(q: PacketDns.Query): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val cm = getSystemService(ConnectivityManager::class.java)!!
            val uid = cm.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(q.source), q.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(q.destination), q.destinationPort),
            )
            if (uid == Process.INVALID_UID) null else appNames.getOrPut(uid) { labelFor(uid) }
        } catch (_: Exception) { null }
    }

    private fun labelFor(uid: Int): String {
        if (uid == Process.SYSTEM_UID || uid == 0) return "Sistem Android"
        val pm = packageManager
        val pkg = pm.getPackagesForUid(uid)?.firstOrNull() ?: return pm.getNameForUid(uid) ?: "uid $uid"
        return try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    }

    private fun periodic() {
        try {
            Stats.flush(this)
            updateNotification(force = false)
            val settings = Settings(this)
            if (session != null && System.currentTimeMillis() - settings.lastAutoUpdate > 6 * 3600_000L &&
                Blocklists.needsUpdate(this, settings.enabledLists)
            ) Blocklists.update(this, force = false)
        } catch (e: Exception) { Log.w(TAG, "periodic", e) }
    }

    // ---------- notificare ----------

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)!!
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Protecție AdShield", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val blocked = Stats.blockedSession.get()
        val total = Stats.totalSession.get()
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("AdShield activ")
            .setContentText(if (total == 0L) "Filtrare reclame pornită" else "Blocate: %,d din %,d cereri".format(blocked, total))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Oprește", stop).build())
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(force: Boolean) {
        if (session == null) return
        val blocked = Stats.blockedSession.get()
        if (!force && blocked == lastNotified) return
        lastNotified = blocked
        getSystemService(NotificationManager::class.java)!!.notify(NOTIF_ID, buildNotification())
    }

    // ---------- oprire ----------

    private fun stopSession() {
        val s = session ?: return
        session = null
        s.active = false // bucla tun observă în ≤0,5 s și închide descriptorul
    }

    private fun shutdown() {
        stopSession()
        _running.value = false
        Stats.flush(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        // Utilizatorul a pornit alt VPN sau a revocat permisiunea.
        Settings(this).wantRunning = false
        shutdown()
    }

    override fun onDestroy() {
        stopSession()
        _running.value = false
        Stats.flush(this)
        scheduler.shutdownNow()
        pool.shutdown()
        super.onDestroy()
    }
}
