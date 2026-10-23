package com.example.adshield

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.example.adshield.START"
        const val ACTION_STOP = "com.example.adshield.STOP"
        const val CHANNEL = "adshield_vpn"
        private val active = AtomicBoolean(false)
        fun isActive() = active.get()
    }
    private var vpn: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val executor = Executors.newFixedThreadPool(4)
    private val writeLock = Any()
    private val upstream = InetAddress.getByName("1.1.1.1")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (active.get()) return START_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "AdShield VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("AdShield activ")
            .setContentText("Filtrarea DNS este pornită")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open).setOngoing(true).build()
        startForeground(100, notification)
        try {
            vpn = Builder().setSession("AdShield DNS")
                .addAddress("10.111.0.2", 32)
                .addDnsServer("10.111.0.1")
                .addRoute("10.111.0.1", 32)
                .setMtu(1500)
                .establish() ?: error("VPN permission missing")
            active.set(true)
            val descriptor = vpn!!
            worker = Thread({ runLoop(descriptor) }, "adshield-dns").apply { start() }
        } catch (e: Exception) {
            Log.e("AdShield", "VPN start failed", e)
            stopSelf()
        }
        return START_STICKY
    }

    private fun runLoop(fd: ParcelFileDescriptor) {
        val store = FilterStore(this)
        val stats = getSharedPreferences("stats", MODE_PRIVATE)
        try {
            FileInputStream(fd.fileDescriptor).use { input ->
                FileOutputStream(fd.fileDescriptor).use { output ->
                    val buffer = ByteArray(32767)
                    while (active.get() && !Thread.currentThread().isInterrupted) {
                        val size = input.read(buffer)
                        if (size <= 0) break
                        val query = PacketDns.parse(buffer.copyOf(size)) ?: continue
                        if (query.destinationPort != 53) continue
                        val name = PacketDns.questionName(query.dns) ?: continue
                        val blocked = store.shouldBlock(name)
                        synchronized(stats) {
                            val editor = stats.edit().putLong("total", stats.getLong("total", 0) + 1)
                            if (blocked) editor.putLong("blocked", stats.getLong("blocked", 0) + 1)
                                .putString("last", name)
                            editor.apply()
                        }
                        if (blocked) {
                            val response = PacketDns.response(query, PacketDns.refused(query.dns))
                            synchronized(writeLock) { output.write(response) }
                        } else {
                            executor.execute {
                                try {
                                    DatagramSocket().use { socket ->
                                        if (!protect(socket)) return@use
                                        socket.soTimeout = 2500
                                        socket.send(DatagramPacket(query.dns, query.dns.size, upstream, 53))
                                        val data = ByteArray(4096)
                                        val reply = DatagramPacket(data, data.size)
                                        socket.receive(reply)
                                        val response = PacketDns.response(query, reply.data.copyOf(reply.length))
                                        synchronized(writeLock) { if (active.get()) output.write(response) }
                                    }
                                } catch (e: Exception) { Log.d("AdShield", "DNS forwarding failed: ${e.message}") }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.d("AdShield", "VPN read loop ended: ${e.message}") }
        finally { if (active.get()) stopSelf() }
    }
    override fun onRevoke() { stopSelf(); super.onRevoke() }
    override fun onDestroy() {
        active.set(false)
        try { vpn?.close() } catch (_: Exception) {}
        vpn = null
        worker?.interrupt()
        executor.shutdownNow()
        super.onDestroy()
    }
}
