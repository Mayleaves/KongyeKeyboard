const SoundFX = {
      ctx: null,
      enabled: true,
      lastStepTime: 0,

      ensure() {
        if (!this.enabled) return null;
        if (!this.ctx) {
          const AudioCtx = window.AudioContext || window.webkitAudioContext;
          if (AudioCtx) this.ctx = new AudioCtx();
        }
        if (this.ctx && this.ctx.state === 'suspended') {
          this.ctx.resume().catch(() => {});
        }
        return this.ctx;
      },

      // 键帽清脆按压声
      playKeyClick() {
        const ctx = this.ensure();
        if (!ctx) return;
        const now = ctx.currentTime;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(320, now);
        osc.frequency.exponentialRampToValueAtTime(95, now + 0.038);
        gain.gain.setValueAtTime(0.14, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.04);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now);
        osc.stop(now + 0.042);
      },

      // 热敏步进电机细微脉冲声（随走纸速度调节音高与密度）
      playStepperTick(speedFactor = 1) {
        if (!this.enabled || !this.ctx || this.ctx.state !== 'running') return;
        const now = performance.now();
        const interval = speedFactor < 0.8 ? 34 : 24;
        if (now - this.lastStepTime < interval) return;
        this.lastStepTime = now;

        const t = this.ctx.currentTime;
        const osc = this.ctx.createOscillator();
        const filter = this.ctx.createBiquadFilter();
        const gain = this.ctx.createGain();

        osc.type = 'sawtooth';
        const baseFreq = 135 + Math.min(95, speedFactor * 55);
        osc.frequency.setValueAtTime(baseFreq, t);
        osc.frequency.exponentialRampToValueAtTime(68, t + 0.016);

        filter.type = 'bandpass';
        filter.frequency.setValueAtTime(920, t);
        filter.Q.setValueAtTime(2.2, t);

        gain.gain.setValueAtTime(0.026, t);
        gain.gain.exponentialRampToValueAtTime(0.001, t + 0.018);

        osc.connect(filter);
        filter.connect(gain);
        gain.connect(this.ctx.destination);
        osc.start(t);
        osc.stop(t + 0.02);
      },

      // 落章沉稳闷响（宣纸受压质感）
      playStampThud() {
        const ctx = this.ensure();
        if (!ctx) return;
        const t = ctx.currentTime;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(145, t);
        osc.frequency.exponentialRampToValueAtTime(42, t + 0.11);
        gain.gain.setValueAtTime(0.24, t);
        gain.gain.exponentialRampToValueAtTime(0.001, t + 0.12);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(t);
        osc.stop(t + 0.125);
      },

      // 锯齿口清脆撕纸声（高频滤波白噪扫频）
      playPaperTear() {
        const ctx = this.ensure();
        if (!ctx) return;
        const duration = 0.23;
        const bufferSize = Math.floor(ctx.sampleRate * duration);
        const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
        const data = buffer.getChannelData(0);
        for (let i = 0; i < bufferSize; i++) {
          // 锯齿调幅质感
          const jag = 0.65 + 0.35 * Math.sin(i * 0.09);
          data[i] = (Math.random() * 2 - 1) * jag;
        }
        const noise = ctx.createBufferSource();
        noise.buffer = buffer;

        const filter = ctx.createBiquadFilter();
        filter.type = 'bandpass';
        filter.frequency.setValueAtTime(1900, ctx.currentTime);
        filter.frequency.exponentialRampToValueAtTime(4200, ctx.currentTime + duration);
        filter.Q.setValueAtTime(1.1, ctx.currentTime);

        const gain = ctx.createGain();
        gain.gain.setValueAtTime(0.02, ctx.currentTime);
        gain.gain.linearRampToValueAtTime(0.16, ctx.currentTime + 0.04);
        gain.gain.exponentialRampToValueAtTime(0.002, ctx.currentTime + duration);

        noise.connect(filter);
        filter.connect(gain);
        gain.connect(ctx.destination);
        noise.start();
      }
    };