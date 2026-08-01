<template>
  <div class="remote-control">
    <canvas ref="canvas" id="canvas"></canvas>
    <div id="fps">{{ fpsText }}</div>
    <div id="info">{{ infoText }}</div>
  </div>
</template>

<script>
export default {
  name: 'RemoteControl',
  data() {
    return {
      ws: null,
      sessionId: null,
      canvas: null,
      ctx: null,
      fpsText: '-- fps',
      infoText: '等待连接...',
      frameCount: 0,
      lastFpsTime: 0,
      connected: false,
      codec: 'JPEG',
    }
  },
  mounted() {
    this.canvas = this.$refs.canvas
    this.ctx = this.canvas.getContext('2d')
    // Expose methods for the sidebar buttons
    window.__app = this
  },
  methods: {
    connect(targetId, verifyCode, codec) {
      if (this.ws) this.disconnect()
      this.codec = codec || 'JPEG'
      this.infoText = '正在连接...'
      const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
      const url = `${protocol}//${location.host}/ws`
      this.ws = new WebSocket(url)
      this.ws.binaryType = 'arraybuffer'
      this.ws.onopen = () => {
        this.infoText = 'WebSocket 已连接，发送 connect...'
        this.ws.send(JSON.stringify({
          type: 'connect',
          targetId: targetId,
          verifyCode: verifyCode,
          protocol: 'DESKTOP'
        }))
      }
      this.ws.onmessage = (e) => this.onMessage(e)
      this.ws.onclose = () => {
        this.infoText = '连接已断开'
        this.connected = false
        this.sessionId = null
      }
      this.ws.onerror = (e) => {
        this.infoText = 'WebSocket 错误'
        this.connected = false
      }
    },
    disconnect() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
      this.connected = false
      this.sessionId = null
      this.infoText = '已断开'
    },
    onMessage(e) {
      if (typeof e.data === 'string') {
        this.onTextMessage(JSON.parse(e.data))
      } else {
        this.onBinaryMessage(e.data)
      }
    },
    onTextMessage(msg) {
      switch (msg.type) {
        case 'connected':
          this.sessionId = msg.sessionId
          this.connected = true
          this.infoText = `会话已建立: ${msg.sessionId}`
          // Send capabilities
          this.ws.send(JSON.stringify({
            type: 'capabilities',
            sessionId: msg.sessionId,
            codecs: [this.codec],
            width: 1920,
            height: 1080,
            fps: 15
          }))
          break
        case 'error':
          this.infoText = `错误: ${msg.msg || msg.message}`
          break
        case 'disconnected':
          this.infoText = '会话已断开'
          this.connected = false
          this.sessionId = null
          break
        case 'desktop_frame_info':
          // Text frame with base64 data (fallback path)
          if (msg.data) {
            this.renderFrame(msg)
          }
          break
        case 'desktop_metrics':
          // FPS info from server
          break
        case 'pong':
          break
        default:
          console.log('未处理的消息:', msg.type)
      }
    },
    onBinaryMessage(buffer) {
      try {
        const view = new DataView(buffer)
        let offset = 0
        // sessionId length (4 bytes)
        const sidLen = view.getInt32(offset, false)
        offset += 4
        // sessionId
        offset += sidLen
        // width (4 bytes)
        const width = view.getInt32(offset, false)
        offset += 4
        // height (4 bytes)
        const height = view.getInt32(offset, false)
        offset += 4
        // keyFrame flag (1 byte)
        const keyFrame = view.getUint8(offset) === 1
        offset += 1
        // frame data
        const frameData = new Uint8Array(buffer, offset)
        this.displayFrame(frameData, width, height, keyFrame)
      } catch (err) {
        console.error('解码帧失败:', err)
      }
    },
    displayFrame(data, width, height, keyFrame) {
      // Update FPS
      this.frameCount++
      const now = performance.now()
      if (now - this.lastFpsTime >= 1000) {
        this.fpsText = `${this.frameCount} fps`
        this.frameCount = 0
        this.lastFpsTime = now
      }
      if (this.codec === 'JPEG') {
        // JPEG: create blob URL and draw on canvas
        const blob = new Blob([data], { type: 'image/jpeg' })
        const url = URL.createObjectURL(blob)
        const img = new Image()
        img.onload = () => {
          this.canvas.width = width
          this.canvas.height = height
          this.ctx.drawImage(img, 0, 0, width, height)
          URL.revokeObjectURL(url)
        }
        img.onerror = () => URL.revokeObjectURL(url)
        img.src = url
      } else {
        // H.264: use VideoDecoder API (WebCodecs)
        this.decodeH264(data, width, height)
      }
      this.infoText = `${width}x${height} ${keyFrame ? '[关键帧] ' : ''}${this.sessionId || ''}`
    },
    decodeH264(data, width, height) {
      if (!this.videoDecoder) {
        this.videoDecoder = new VideoDecoder({
          output: (frame) => {
            this.canvas.width = frame.displayWidth
            this.canvas.height = frame.displayHeight
            this.ctx.drawImage(frame, 0, 0)
            frame.close()
          },
          error: (err) => console.error('VideoDecoder 错误:', err)
        })
        this.videoDecoder.configure({
          codec: 'avc1.42001E',
          codedWidth: width,
          codedHeight: height,
          optimizeForLatency: true
        })
      }
      const chunk = new EncodedVideoChunk({
        type: data[4] === 0x67 ? 'key' : 'delta',
        timestamp: 0,
        data: data
      })
      this.videoDecoder.decode(chunk)
    }
  }
}
</script>

<style scoped>
.remote-control { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
canvas { max-width: 100%; max-height: 100%; }
</style>