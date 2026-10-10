package app.lunex

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import io.socket.client.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Native one-to-one WebRTC calls. Signaling is relayed by Lunex's authenticated Socket.IO server.
 * This class owns media resources and must be disposed when the call UI leaves composition.
 */
internal class AndroidCallManager(
    private val context: Context,
    private val server: String,
    private val token: String,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onIncoming: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
    private val onLocalVideo: (VideoTrack?) -> Unit,
    private val onRemoteVideo: (VideoTrack?) -> Unit,
    private val onError: (String) -> Unit
) {
    private var socket: Socket? = null
    private val egl = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var currentCallId = ""
    private var currentConversationId = ""
    private var targetUserId = ""
    private var currentMode = "audio"
    private var isCaller = false
    private var localSignalReady = false
    private var pendingOffer: JSONObject? = null
    private val pendingLocalCandidates = CopyOnWriteArrayList<JSONObject>()
    private val pendingRemoteCandidates = CopyOnWriteArrayList<IceCandidate>()
    private var remoteDescriptionReady = false
    private var disposed = false
    private var micEnabled = true
    private var cameraEnabled = true
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callTimeout: Runnable? = null
    private var connectionEstablished = false

    val eglContext get() = egl.eglBaseContext

    companion object {
        @Volatile private var nativeInitialized = false

        @Synchronized
        private fun ensureInitialized(context: Context) {
            if (!nativeInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
                )
                nativeInitialized = true
            }
        }
    }

    init {
        ensureInitialized(context)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun bind(socket: Socket) {
        this.socket = socket
        socket.on("call:incoming") { args ->
            val event = args.firstOrNull() as? JSONObject ?: return@on
            if (currentCallId.isNotBlank()) {
                socket.emit("call:reject", JSONObject().put("callId", event.optString("callId")).put("conversationId", event.optString("conversationId")))
                return@on
            }
            currentCallId = event.optString("callId")
            currentConversationId = event.optString("conversationId")
            pendingOffer = event.optJSONObject("offer")
            currentMode = event.optString("mode", "audio")
            isCaller = false
            connectionEstablished = false
            scheduleTimeout("Не удалось дозвониться: нет ответа.")
            onState("incoming")
            onIncoming(event)
        }
        socket.on("call:accepted") { args ->
            val event = args.firstOrNull() as? JSONObject ?: return@on
            if (event.optString("callId") != currentCallId) return@on
            val answer = event.optJSONObject("answer") ?: return@on
            val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(answer.optString("type", "answer")), answer.optString("sdp"))
            peer?.setRemoteDescription(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionReady = true
                    drainRemoteCandidates()
                    onState("connecting")
                }
                override fun onSetFailure(error: String) { fail("Не удалось принять ответ звонка: $error") }
            }, desc)
        }
        socket.on("call:signal") { args ->
            val event = args.firstOrNull() as? JSONObject ?: return@on
            if (event.optString("callId") != currentCallId) return@on
            val candidateJson = event.optJSONObject("signal")?.optJSONObject("candidate") ?: return@on
            val candidate = IceCandidate(
                candidateJson.optString("sdpMid").takeIf { it != "null" },
                candidateJson.optInt("sdpMLineIndex"),
                candidateJson.optString("candidate")
            )
            if (remoteDescriptionReady) peer?.addIceCandidate(candidate) else pendingRemoteCandidates.add(candidate)
        }
        socket.on("call:rejected") { args ->
            val event = args.firstOrNull() as? JSONObject ?: return@on
            if (event.optString("callId") == currentCallId) { closeInternal(); onState("rejected") }
        }
        socket.on("call:ended") { args ->
            val event = args.firstOrNull() as? JSONObject ?: return@on
            if (event.optString("callId") == currentCallId) { closeInternal(); onState("ended") }
        }
    }

    fun startOutgoing(conversationId: String, toUserId: String, mode: String) {
        if (currentCallId.isNotBlank()) { onError("Звонок уже активен."); return }
        currentCallId = java.util.UUID.randomUUID().toString()
        currentConversationId = conversationId
        targetUserId = toUserId
        currentMode = mode
        isCaller = true
        localSignalReady = false
        connectionEstablished = false
        onState("calling")
        scheduleTimeout("Не удалось дозвониться. Проверь сеть или TURN-сервер.")
        try {
            prepareMedia(mode)
            createPeer()
            peer?.createOffer(object : EmptySdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription) {
                    peer?.setLocalDescription(object : EmptySdpObserver() {
                        override fun onSetSuccess() {
                            val offer = JSONObject().put("type", desc.type.canonicalForm()).put("sdp", desc.description)
                            socket?.emit("call:invite", JSONObject()
                                .put("callId", currentCallId)
                                .put("conversationId", currentConversationId)
                                .put("toUserId", targetUserId)
                                .put("mode", currentMode)
                                .put("offer", offer))
                            localSignalReady = true
                            flushLocalCandidates()
                        }
                        override fun onSetFailure(error: String) { fail("Не удалось подготовить звонок: $error") }
                    }, desc)
                }
                override fun onCreateFailure(error: String) { fail("Не удалось создать звонок: $error") }
            }, MediaConstraints())
        } catch (e: Exception) { fail(e.message ?: "Не удалось запустить звонок.") }
    }

    fun acceptIncoming() {
        val offer = pendingOffer
        if (currentCallId.isBlank() || offer == null) { onError("Предложение звонка устарело."); return }
        try {
            prepareMedia(currentMode)
            createPeer()
            val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(offer.optString("type", "offer")), offer.optString("sdp"))
            peer?.setRemoteDescription(object : EmptySdpObserver() {
                override fun onSetSuccess() {
                    remoteDescriptionReady = true
                    drainRemoteCandidates()
                    peer?.createAnswer(object : EmptySdpObserver() {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            peer?.setLocalDescription(object : EmptySdpObserver() {
                                override fun onSetSuccess() {
                                    socket?.emit("call:accept", JSONObject()
                                        .put("callId", currentCallId)
                                        .put("conversationId", currentConversationId)
                                        .put("answer", JSONObject().put("type", answer.type.canonicalForm()).put("sdp", answer.description)))
                                    localSignalReady = true
                                    flushLocalCandidates()
                                    onState("connecting")
                                }
                                override fun onSetFailure(error: String) { fail("Не удалось подготовить ответ: $error") }
                            }, answer)
                        }
                        override fun onCreateFailure(error: String) { fail("Не удалось ответить на звонок: $error") }
                    }, MediaConstraints())
                }
                override fun onSetFailure(error: String) { fail("Не удалось открыть входящий звонок: $error") }
            }, desc)
        } catch (e: Exception) { fail(e.message ?: "Не удалось принять звонок.") }
    }

    fun rejectIncoming() {
        if (currentCallId.isNotBlank()) socket?.emit("call:reject", JSONObject().put("callId", currentCallId).put("conversationId", currentConversationId))
        closeInternal()
        onState("idle")
    }

    fun endCall() {
        if (currentCallId.isNotBlank()) socket?.emit("call:end", JSONObject().put("callId", currentCallId).put("conversationId", currentConversationId))
        closeInternal()
        onState("idle")
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        micEnabled = enabled
        audioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        videoTrack?.setEnabled(enabled)
        if (enabled) runCatching { capturer?.startCapture(640, 480, 24) }
        else runCatching { capturer?.stopCapture() }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        closeInternal()
        socket?.off("call:incoming")
        socket?.off("call:accepted")
        socket?.off("call:signal")
        socket?.off("call:rejected")
        socket?.off("call:ended")
        socket = null
        factory.dispose()
        egl.release()
    }

    private fun prepareMedia(mode: String) {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("lunex-audio", audioSource).apply { setEnabled(micEnabled) }
        if (mode == "video") {
            val enumerator = Camera2Enumerator(context)
            val cameraName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: throw IllegalStateException("Камера не найдена.")
            val camera = enumerator.createCapturer(cameraName, null) as? CameraVideoCapturer
                ?: throw IllegalStateException("Не удалось открыть камеру.")
            val source = factory.createVideoSource(false)
            val helper = SurfaceTextureHelper.create("LunexCameraThread", egl.eglBaseContext)
            capturer = camera
            videoSource = source
            textureHelper = helper
            camera.initialize(helper, context, source.capturerObserver)
            camera.startCapture(640, 480, 24)
            videoTrack = factory.createVideoTrack("lunex-video", source).apply { setEnabled(cameraEnabled) }
            onLocalVideo(videoTrack)
        } else {
            onLocalVideo(null)
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = true }
    }

    private fun createPeer() {
        val config = PeerConnection.RTCConfiguration(iceServers)
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> { connectionEstablished = true; cancelTimeout(); onState("connected") }
                    PeerConnection.IceConnectionState.FAILED -> fail("Не удалось соединить устройства. Для некоторых сетей нужен TURN-сервер.")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidate(candidate: IceCandidate) {
                val json = JSONObject().put("candidate", JSONObject()
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp))
                val payload = JSONObject().put("callId", currentCallId).put("conversationId", currentConversationId).put("signal", json)
                if (isCaller && !localSignalReady) pendingLocalCandidates.add(payload) else socket?.emit("call:signal", payload)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) { stream.videoTracks.firstOrNull()?.let(onRemoteVideo) }
            override fun onRemoveStream(stream: MediaStream) { onRemoteVideo(null) }
            override fun onDataChannel(channel: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                (receiver.track() as? VideoTrack)?.let(onRemoteVideo)
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let(onRemoteVideo)
            }
        })
        val pc = peer ?: throw IllegalStateException("Не удалось создать WebRTC-соединение.")
        audioTrack?.let { pc.addTrack(it, listOf("lunex-stream")) }
        videoTrack?.let { pc.addTrack(it, listOf("lunex-stream")) }
    }

    private fun flushLocalCandidates() {
        pendingLocalCandidates.forEach { socket?.emit("call:signal", it) }
        pendingLocalCandidates.clear()
    }

    private fun drainRemoteCandidates() {
        pendingRemoteCandidates.forEach { peer?.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    private fun scheduleTimeout(message: String) {
        cancelTimeout()
        val expectedCallId = currentCallId
        callTimeout = Runnable { if (currentCallId == expectedCallId && !connectionEstablished) { socket?.emit("call:end", JSONObject().put("callId", expectedCallId).put("conversationId", currentConversationId)); fail(message) } }
        mainHandler.postDelayed(callTimeout!!, 35000)
    }

    private fun cancelTimeout() {
        callTimeout?.let(mainHandler::removeCallbacks)
        callTimeout = null
    }

    private fun fail(message: String) {
        if (currentCallId.isNotBlank()) socket?.emit("call:end", JSONObject().put("callId", currentCallId).put("conversationId", currentConversationId))
        closeInternal()
        onState("failed")
        onError(message)
    }

    private fun closeInternal() {
        cancelTimeout()
        connectionEstablished = false
        runCatching { capturer?.stopCapture() }
        runCatching { capturer?.dispose() }
        capturer = null
        textureHelper?.dispose(); textureHelper = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        peer?.close(); peer?.dispose(); peer = null
        pendingLocalCandidates.clear(); pendingRemoteCandidates.clear()
        pendingOffer = null
        currentCallId = ""; currentConversationId = ""; targetUserId = ""
        remoteDescriptionReady = false; localSignalReady = false
        onLocalVideo(null); onRemoteVideo(null)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        runCatching { audioManager.isSpeakerphoneOn = false }
    }
}

internal open class EmptySdpObserver : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {}
    override fun onSetFailure(error: String) {}
}
