"""Pinned, offline streaming preview from sherpa-onnx's official Chinese model.
Apache-2.0 checkpoint: csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01.
The existing dialect-specific Paraformer remains the final recognition pass.
"""
import json
import shutil
from pathlib import Path
from fetch_offline_voice import fetch

ROOT = Path(__file__).resolve().parents[1]
REPO = 'csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01'
REV = 'a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da'
FILES = {
    'model.int8.onnx': '68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a',
    'tokens.txt': '6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd',
    'bbpe.model': '503204e0690eff065e30d0e01898c9ab06d0e6dc376a741eb6846198f95b2f82',
}


def main():
    target = ROOT / 'app/src/main/assets/voice-streaming'; target.mkdir(exist_ok=True)
    for name, sha in FILES.items():
        source = fetch(f'https://huggingface.co/{REPO}/resolve/{REV}/{name}', ROOT / 'build/voice-streaming' / name, sha)
        shutil.copyfile(source, target / name)
    fetch(f'https://huggingface.co/{REPO}/resolve/{REV}/test_wavs/0.wav', ROOT / 'build/voice-streaming/test_wavs/0.wav',
          '668bf8df51a10027b84d5d8816a1ce11ae93545538dc05cfe2aa6811d399c250')
    (target / 'sources.json').write_text(json.dumps({'repo': REPO, 'revision': REV, 'license': 'Apache-2.0',
        'checkpoint': 'https://huggingface.co/csukuangfj/icefall-streaming-zipformer-small-ctc-zh-2025-04-01',
        'files': FILES, 'changes': 'No model changes. Streaming inference follows sherpa-onnx Android OnlineRecognizer API.'}, indent=2), encoding='utf8')


if __name__ == '__main__': main()
