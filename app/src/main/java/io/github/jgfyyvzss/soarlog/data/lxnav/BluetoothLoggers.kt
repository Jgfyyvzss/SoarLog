package io.github.jgfyyvzss.soarlog.data.lxnav

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.jgfyyvzss.soarlog.core.lxnav.LinkClosed
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavConnector
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavLink
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class PairedDevice(val address: String, val name: String)

sealed interface BluetoothStatus {
    data object Unsupported : BluetoothStatus
    data object NeedsPermission : BluetoothStatus
    data object Off : BluetoothStatus
    data class Ready(val devices: List<PairedDevice>) : BluetoothStatus
}

/**
 * Paired Bluetooth devices and serial connections to them. The app never scans: the pilot pairs the logger in
 * Android's Bluetooth settings. Connecting uses the standard serial port UUID, as Serial Bluetooth Terminal does.
 */
class BluetoothLoggers(private val context: Context) {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** BLUETOOTH_CONNECT is a runtime permission from Android 12; before that the install-time permissions apply. */
    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Paired devices, LXNAV ones first. */
    @SuppressLint("MissingPermission") // checked by hasPermission
    fun status(): BluetoothStatus {
        val adapter = adapter ?: return BluetoothStatus.Unsupported
        if (!hasPermission()) return BluetoothStatus.NeedsPermission
        if (!adapter.isEnabled) return BluetoothStatus.Off
        val devices = adapter.bondedDevices.orEmpty()
            .map { PairedDevice(it.address, it.name ?: it.address) }
            .sortedWith(compareBy<PairedDevice> { !it.name.startsWith("LXNAV", ignoreCase = true) }.thenBy { it.name.lowercase() })
        return BluetoothStatus.Ready(devices)
    }

    @SuppressLint("MissingPermission")
    fun connector(address: String): LxnavConnector = LxnavConnector {
        val adapter = adapter ?: throw IOException("no Bluetooth")
        if (!hasPermission()) throw IOException("no Bluetooth permission")
        // Discovery slows a connection down. Cancelling it needs BLUETOOTH_SCAN from Android 12, which this app
        // does not ask for because it never scans, so it is only done before then.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) adapter.cancelDiscovery()
        val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERIAL_PORT)
        connect(socket)
        BluetoothLink(socket)
    }

    /** connect() blocks for up to about 12 seconds; closing the socket is the only way to stop it early. */
    private suspend fun connect(socket: BluetoothSocket) = suspendCancellableCoroutine { cont ->
        thread(name = "logger-connect") {
            try {
                socket.connect()
                cont.resume(Unit)
            } catch (e: IOException) {
                runCatching { socket.close() }
                cont.resumeWithException(e)
            }
        }
        cont.invokeOnCancellation { runCatching { socket.close() } }
    }

    private companion object {
        /** The Bluetooth serial port profile's UUID (0x1101). */
        val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

/** A connected socket: a reader copies incoming bytes into a channel, which closes when the link drops. */
private class BluetoothLink(private val socket: BluetoothSocket) : LxnavLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        scope.launch {
            val buffer = ByteArray(4096)
            try {
                val input = socket.inputStream
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    incoming.send(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                // The link dropped or was closed.
            } finally {
                incoming.close(LinkClosed())
            }
        }
    }

    override suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        try {
            socket.outputStream.write(bytes)
            socket.outputStream.flush()
        } catch (e: IOException) {
            throw LinkClosed(e)
        }
    }

    override suspend fun receive(): ByteArray = incoming.receive()

    override fun clearInput(): Int {
        var bytes = 0
        while (true) bytes += incoming.tryReceive().getOrNull()?.size ?: break
        return bytes
    }

    override fun close() {
        runCatching { socket.close() }
        incoming.close(LinkClosed())
        scope.cancel()
    }
}
