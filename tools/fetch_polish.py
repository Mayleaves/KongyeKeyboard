"""Reproduce the bundled offline polish runtime/model from pinned public sources (Python 3.12+)."""
import argparse,hashlib,json,os,shutil,subprocess,tarfile
from pathlib import Path
import requests
ROOT=Path(__file__).resolve().parents[1]
CONFIG=ROOT/'tools/polish'
META=json.loads((CONFIG/'sources.json').read_text('utf-8'))
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def fetch(url,path,digest):
 path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists() and sha(path)==digest:return
 temp=path.with_suffix(path.suffix+'.part')
 try:
  with requests.get(url,stream=True,timeout=120) as r:
   r.raise_for_status()
   with temp.open('wb') as f:
    for part in r.iter_content(1024*1024):f.write(part)
  if sha(temp)!=digest:raise RuntimeError('SHA-256 mismatch: '+path.name)
  os.replace(temp,path)
 finally:temp.unlink(missing_ok=True)
def main():
 parser=argparse.ArgumentParser(__doc__)
 parser.add_argument('--ndk',required=True,type=Path)
 parser.add_argument('--cmake',default=shutil.which('cmake'))
 parser.add_argument('--ninja',default=shutil.which('ninja'))
 args=parser.parse_args()
 if not args.cmake or not args.ninja:parser.error('Provide --cmake and --ninja executable paths')
 ndk=args.ndk.resolve();cache=ROOT/'build/polish';cache.mkdir(parents=True,exist_ok=True)
 model=META['model'];commit=META['runtime']['commit']
 fetch(model['project']+'/resolve/'+model['revision']+'/'+model['file'],ROOT/'app/src/main/assets/voice-polish'/model['file'],model['sha256'])
 archive=cache/'llama.tar.gz'
 fetch('https://codeload.github.com/ggml-org/llama.cpp/tar.gz/'+commit,archive,META['runtime']['archive_sha256'])
 source=cache/('llama.cpp-'+commit)
 if not source.exists():
  with tarfile.open(archive) as tar:tar.extractall(cache,filter='data')
 strip=ndk/'toolchains/llvm/prebuilt'/('windows-x86_64' if os.name=='nt' else 'linux-x86_64')/'bin'/('llvm-strip.exe' if os.name=='nt' else 'llvm-strip')
 for abi in META['build']['abis']:
  build=cache/abi
  subprocess.run([str(args.cmake),'-S',str(CONFIG),'-B',str(build),'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(Path(args.ninja).resolve()),'-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI='+abi,'-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static','-DCMAKE_BUILD_TYPE=Release','-DLLAMA_SOURCE='+str(source)],check=True)
  subprocess.run([str(args.cmake),'--build',str(build),'--target','kongye_polish','-j','4'],check=True)
  target=ROOT/'app/src/main/jniLibs'/abi/'libkongye_polish.so';target.parent.mkdir(parents=True,exist_ok=True)
  shutil.copyfile(build/'libkongye_polish.so',target);subprocess.run([str(strip),'--strip-unneeded',str(target)],check=True)
  print('Verified model and built '+abi,flush=True)
if __name__=='__main__':main()
