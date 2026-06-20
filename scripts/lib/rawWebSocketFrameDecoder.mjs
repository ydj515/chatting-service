export class RawWebSocketFrameDecoder {
  constructor({
    onText = () => {},
    onPing = () => {},
    onClose = () => {},
    maxPayloadBytes = 8 * 1024 * 1024,
  } = {}) {
    this.onText = onText;
    this.onPing = onPing;
    this.onClose = onClose;
    this.maxPayloadBytes = maxPayloadBytes;
    this.buffer = Buffer.alloc(0);
    this.fragmentedText = [];
  }

  read(chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const second = this.buffer[1];
      const fin = (first & 0x80) !== 0;
      const opcode = first & 0x0f;
      const masked = (second & 0x80) !== 0;
      let length = second & 0x7f;
      let offset = 2;

      if (length === 126) {
        if (this.buffer.length < offset + 2) return;
        length = this.buffer.readUInt16BE(offset);
        offset += 2;
      } else if (length === 127) {
        if (this.buffer.length < offset + 8) return;
        const wideLength = this.buffer.readBigUInt64BE(offset);
        if (wideLength > BigInt(Number.MAX_SAFE_INTEGER)) {
          throw new Error(`WebSocket frame payload too large: ${wideLength.toString()} bytes`);
        }
        length = Number(wideLength);
        offset += 8;
      }
      this.rejectOversizedPayload(length);

      const maskOffset = masked ? 4 : 0;
      if (this.buffer.length < offset + maskOffset + length) return;
      const mask = masked ? this.buffer.subarray(offset, offset + 4) : null;
      offset += maskOffset;
      let payload = this.buffer.subarray(offset, offset + length);
      this.buffer = this.buffer.subarray(offset + length);

      if (mask) {
        payload = Buffer.from(payload.map((byte, index) => byte ^ mask[index % 4]));
      }
      this.handleFrame({ fin, opcode, payload });
    }
  }

  rejectOversizedPayload(length) {
    if (length > this.maxPayloadBytes) {
      throw new Error(`WebSocket frame payload too large: ${length} bytes`);
    }
  }

  handleFrame({ fin, opcode, payload }) {
    if (opcode === 0x1) {
      if (fin) {
        this.onText(payload.toString('utf8'));
        return;
      }
      this.fragmentedText = [payload];
      return;
    }

    if (opcode === 0x0) {
      if (this.fragmentedText.length === 0) {
        return;
      }
      this.fragmentedText.push(payload);
      if (fin) {
        this.onText(Buffer.concat(this.fragmentedText).toString('utf8'));
        this.fragmentedText = [];
      }
      return;
    }

    if (opcode === 0x8) {
      this.onClose();
    } else if (opcode === 0x9) {
      this.onPing(payload);
    }
  }
}
