package com.example.bruceir

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.materialswitch.MaterialSwitch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class IntercomActivity : AppCompatActivity() {

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isListening = false
    private val sampleRate = 8000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
    
    private lateinit var bruceIp: String
    private val udpPort = 8001 // Port do streamingu audio

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intercom)

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val url = prefs.getString("bruce_url", "http://192.168.1.100") ?: ""
        bruceIp = url.removePrefix("http://").removePrefix("https://").substringBefore(":")

        val btnPTT = findViewById<Button>(R.id.btnPTT)
        val swSpy = findViewById<MaterialSwitch>(R.id.swSpyMode)
        val tvStatus = findViewById<TextView>(R.id.tvIntercomStatus)

        btnPTT.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startRecording()
                    tvStatus.text = "TALKING..."
                    v.isPressed = true
                }
                MotionEvent.ACTION_UP -> {
                    stopRecording()
                    tvStatus.text = "READY"
                    v.isPressed = false
                }
            }
            true
        }

        swSpy.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startListening() else stopListening()
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        Thread {
            try {
                val socket = DatagramSocket()
                val address = InetAddress.getByName(bruceIp)
                val buffer = ByteArray(bufferSize)
                
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfigIn, audioFormat, bufferSize)
                audioRecord?.startRecording()

                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val packet = DatagramPacket(buffer, read, address, udpPort)
                        socket.send(packet)
                    }
                }
                audioRecord?.stop()
                audioRecord?.release()
                socket.close()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Send Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun stopRecording() {
        isRecording = false
    }

    private fun startListening() {
        if (isListening) return
        isListening = true
        Thread {
            try {
                val socket = DatagramSocket(udpPort)
                val buffer = ByteArray(bufferSize)
                
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, channelConfigOut, audioFormat, bufferSize, AudioTrack.MODE_STREAM
                )
                audioTrack?.play()

                while (isListening) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    audioTrack?.write(packet.data, 0, packet.length)
                }
                audioTrack?.stop()
                audioTrack?.release()
                socket.close()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Listen Error: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun stopListening() {
        isListening = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        isListening = false
    }
}
