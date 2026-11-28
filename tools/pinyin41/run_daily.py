"""Build and run one explicit device profile, preserving timestamped evidence for daily QA."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse
import hashlib
import json
import math
import msvcrt
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'tools'))
from format_candidate_review import format_report
from corpus import check as check_corpora

ADB = Path('D:/Program/Android/Sdk/platform-tools/adb.exe')
BUILD_SHELL = Path('E:/Vibe Coding/Android/Open Android Build Shell.cmd')
TZ = timezone(timedelta(hours=8))
PACKAGE = 'com.kongye.keyboard'
RUNNER = PACKAGE+'.test/'+PACKAGE+'.OfflineResourcesCheck'
# Learning/UI suites use isolated fixtures or restore backups, but should be run
# against a dedicated emulator rather than interrupting a person's phone.
EXTRA_MODES = {'pinyin-adversarial', 'pinyin-400', 'fuzzy-switches', 'correction-switches', 'learning-400',
               'pinyin-live-400', 'floating-layout-400', 'floating-layout-live-400',
               'layout-400', 'orientation-keyboard', 'settings-preview', 'punctuation-flicks',
               'typing-stats', 'stats-report-ui', 'input-coverage', 'candidate-pinyin-selection', 'candidate-mode', 'partial-delete', 'handwriting-touch','touch-recognition','live-240'}
PHONE_SAFE = {'pinyin-adversarial', 'pinyin-400', 'correction-switches', 'layout-400'}


def digest(p):
    with p.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()


def percentile(values, q):
    if not values: return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(q*len(ordered))-1)]


def execute(args, log, timeout=360):
    with log.open('w', encoding='utf8') as f:
        process = subprocess.run([str(a) for a in args], cwd=ROOT, stdout=f, stderr=subprocess.STDOUT,
                                 encoding='utf8', errors='replace', timeout=timeout)
    return process.returncode, log.read_text(encoding='utf8', errors='replace')


def success(code, text):
    return code == 0 and 'INSTRUMENTATION_CODE: -1' in text and not re.search(
        r'(?m)^FAILED:|^FAILURES|INSTRUMENTATION_FAILED|Process crashed', text)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial', required=True)
    p.add_argument('--splits', nargs='*', choices=['train','validation','test'], default=['validation'])
    p.add_argument('--modes', nargs='*', choices=sorted(EXTRA_MODES), default=['pinyin-adversarial','pinyin-400'])
    p.add_argument('--build', action='store_true')
    p.add_argument('--install', action='store_true')
    args = p.parse_args()
    if not args.splits and not args.modes: p.error('Select at least one corpus or regression mode')
    check_corpora()
    base = ROOT/'build/pinyin41'
    base.mkdir(parents=True, exist_ok=True)
    with (base/'daily.lock').open('a+b') as guard:
        guard.seek(0); guard.write(b'1'); guard.flush(); guard.seek(0)
        try: msvcrt.locking(guard.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError: raise SystemExit('Another pinyin41 run is active; do not overlap device testing')
        now = datetime.now(TZ)
        folder = base/now.strftime('%Y-%m-%d')/(now.strftime('%H%M%S')+'-'+uuid.uuid4().hex[:6])
        folder.mkdir(parents=True)
        summary = {'started':now.isoformat(), 'serial':args.serial, 'runs':[], 'status':'RUNNING'}
        def save():
            (folder/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
        save()
        print('Report directory: '+str(folder), flush=True)
        try:
            if args.build:
                signing = '"-Pandroid.injected.signing.store.file=C:\\Users\\Koletis\\.android\\debug.keystore" -Pandroid.injected.signing.store.password=android -Pandroid.injected.signing.key.alias=androiddebugkey -Pandroid.injected.signing.key.password=android'
                lines = f'cd /d "{ROOT}"\ncall gradlew.bat {signing} :app:assembleRelease :app:assembleReleaseAndroidTest --offline --console=plain --max-workers=1\nexit %errorlevel%\n'
                with (folder/'build.log').open('w',encoding='utf8') as log:
                    result = subprocess.run(['cmd.exe','/d','/c','call',str(BUILD_SHELL)],input=lines,
                                            text=True,stdout=log,stderr=subprocess.STDOUT,cwd=ROOT,timeout=1200)
                text = (folder/'build.log').read_text('utf8',errors='replace')
                if result.returncode != 0 or 'BUILD SUCCESSFUL' not in text: raise RuntimeError('Build failed; see build.log')
                print('Build passed',flush=True)
            code, devices = execute([ADB,'devices','-l'],folder/'devices.log')
            if code or not re.search(r'(?m)^'+re.escape(args.serial)+r'\s+device\b',devices):
                summary['status']='DEVICE_UNAVAILABLE'
                raise RuntimeError('Requested device is not connected/authorized; no results fabricated')
            _, model = execute([ADB,'-s',args.serial,'shell','getprop','ro.product.model'],folder/'model.log')
            _, abi = execute([ADB,'-s',args.serial,'shell','getprop','ro.product.cpu.abi'],folder/'abi.log')
            summary.update(model=model.strip(), abi=abi.strip())
            metadata = json.loads((ROOT/'app/build/outputs/apk/release/output-metadata.json').read_text('utf8'))
            versions = {e['versionName'] for e in metadata['elements']}
            if len(versions)!=1 or not next(iter(versions)).startswith('4.1.'):
                raise RuntimeError('Expected a 4.1.x test APK, not a stale 4.0 artifact')
            summary['version']=next(iter(versions))
            if not any(name in abi for name in ('x86_64','arm64-v8a')): raise RuntimeError('Unsupported test device ABI: '+abi.strip())
            arch = 'x86_64' if 'x86_64' in abi else 'arm64-v8a'
            apk = ROOT/f'app/build/outputs/apk/release/app-{arch}-release.apk'
            test_apk = ROOT/'app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'
            summary['apkSha256']=digest(apk)
            summary['testApkSha256']=digest(test_apk)
            if args.install:
                for name,path in [('app',apk),('tests',test_apk)]:
                    code,text=execute([ADB,'-s',args.serial,'install','-r',path],folder/f'install-{name}.log')
                    if code or 'Success' not in text: raise RuntimeError('Install failed: '+name)
            for split in args.splits:
                token=uuid.uuid4().hex
                print('Running '+split,flush=True)
                code,text=execute([ADB,'-s',args.serial,'shell','am','instrument','-w','-e','mode','pinyin41','-e','split',split,'-e','runToken',token,RUNNER],folder/f'{split}.log')
                record={'name':split,'kind':'corpus','passed':success(code,text)}
                summary['runs'].append(record)
                target=folder/f'{split}.json'
                pulled,_=execute([ADB,'-s',args.serial,'pull',f'/sdcard/Android/data/{PACKAGE}/files/pinyin41/{split}/results.json',target],folder/f'pull-{split}.log')
                if pulled==0 and target.exists():
                    data=json.loads(target.read_text('utf8'))
                    reference=json.loads((ROOT/f'app/src/androidTest/assets/pinyin41/{split}.json').read_text('utf8'))
                    fresh=(data.get('runToken')==token and data.get('complete') is True
                           and data.get('appVersion')==summary['version']
                           and data.get('datasetSha256')==digest(ROOT/f'app/src/androidTest/assets/pinyin41/{split}.json'))
                    if not fresh: record.update(passed=False,error='Stale, incomplete or mismatched report')
                    else:
                        record.update(cases=len(data['cases']),failures=len(data['failures']),
                                      reviews=sum(bool(c['review']) for c in data['cases']),
                                      settingsPreserved=data['settingsPreserved'],personalDataPreserved=data['personalDataPreserved'])
                        record['passed'] &= not data['failures'] and data['settingsPreserved'] and data['personalDataPreserved']
                        first=[c['firstBatchMs'] for c in data['cases'] if isinstance(c.get('firstBatchMs'),(int,float))]
                        total=[c['totalMs'] for c in data['cases'] if isinstance(c.get('totalMs'),(int,float))]
                        record.update(firstBatchP50Ms=percentile(first,.5),firstBatchP95Ms=percentile(first,.95),totalP95Ms=percentile(total,.95))
                        refs={c['id']:c for c in reference['cases']}
                        for c in data['cases']: c['watch']=refs[c['id']].get('watch',[])
                        (folder/f'{split}.md').write_text(format_report(data),encoding='utf8')
                else: record.update(passed=False,error='No device report; inspect instrumentation log')
                print(json.dumps(record,ensure_ascii=False),flush=True); save()
            for mode in args.modes:
                if not args.serial.startswith('emulator-') and mode not in PHONE_SAFE:
                    summary['runs'].append({'name':mode,'kind':'regression','status':'SKIPPED','reason':'Requires dedicated emulator for learning/UI interaction'})
                    print('Skipped dedicated-emulator mode '+mode,flush=True);save();continue
                print('Running '+mode,flush=True)
                token=uuid.uuid4().hex
                code,text=execute([ADB,'-s',args.serial,'shell','am','instrument','-w','-e','mode',mode,'-e','runToken',token,RUNNER],folder/f'{mode}.log')
                record={'name':mode,'kind':'regression','passed':success(code,text)}
                if mode=='fuzzy-switches':
                    target=folder/f'{mode}.json'
                    pulled,_=execute([ADB,'-s',args.serial,'pull',f'/sdcard/Android/data/{PACKAGE}/files/fuzzy-switches/results.json',target],folder/f'pull-{mode}.log')
                    if pulled==0 and target.exists():
                        data=json.loads(target.read_text('utf8'))
                        fresh=data.get('runToken')==token and data.get('complete') is True and data.get('appVersion')==summary['version']
                        record.update(cases=len(data['cases']),failures=len(data['failures']),reviews=len(data['reviews']),
                                      settingsPreserved=data['settingsPreserved'],personalDataPreserved=data['personalDataPreserved'])
                        first=[c['firstBatchMs'] for c in data['cases'] if isinstance(c.get('firstBatchMs'),(int,float))]
                        total=[c['totalMs'] for c in data['cases'] if isinstance(c.get('totalMs'),(int,float))]
                        record.update(firstBatchP50Ms=percentile(first,.5),firstBatchP95Ms=percentile(first,.95),totalP95Ms=percentile(total,.95))
                        record['passed'] &= fresh and not data['failures'] and data['settingsPreserved'] and data['personalDataPreserved']
                        if not fresh:record['error']='Stale, incomplete or mismatched fuzzy-switch report'
                    else:record.update(passed=False,error='No fuzzy-switch device report')
                summary['runs'].append(record);save()
            summary['status']='PASSED' if all(r.get('passed',True) for r in summary['runs']) else 'FAILED'
            if any(r.get('status')=='SKIPPED' for r in summary['runs']) and summary['status']=='PASSED': summary['status']='PARTIAL'
        except Exception as error:
            summary.update(status=summary['status'] if summary['status']=='DEVICE_UNAVAILABLE' else 'ERROR',error=str(error))
        finally:
            summary['finished']=datetime.now(TZ).isoformat();save()
            lines=[f"# 空叶键盘每日测试 · {now:%Y-%m-%d}","",f"版本：{summary.get('version','未确认')}；设备：{summary.get('model',args.serial)}；状态：{summary['status']}","",
                   "首批回调和完整查询耗时是引擎指标，不是按键到屏幕显示的端到端延迟。","",
                   "| 项目 | 状态 | 用例 | 规则错误 | 待评审 | 首批 p50/p95 ms | 完整查询 p95 ms |","| --- | --- | --- | --- | --- | --- | --- |"]
            for run in summary['runs']:
                state=run.get('status','PASS' if run.get('passed') else 'FAIL')
                lines.append(f"| {run['name']} | {state} | {run.get('cases','—')} | {run.get('failures','—')} | {run.get('reviews','—')} | {run.get('firstBatchP50Ms','—')}/{run.get('firstBatchP95Ms','—')} | {run.get('totalP95Ms','—')} |")
            if summary.get('error'): lines += ['', '运行异常：'+summary['error']]
            (folder/'summary.md').write_text('\n'.join(lines)+'\n',encoding='utf8')
            (base/'latest.json').write_text(json.dumps({'directory':str(folder),'status':summary['status']},ensure_ascii=False,indent=2)+'\n',encoding='utf8')
            print('FINAL '+summary['status']+' '+str(folder/'summary.md'),flush=True)
        return 0 if summary['status']=='PASSED' else 1


if __name__=='__main__':
    raise SystemExit(main())
