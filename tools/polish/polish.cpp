#include <jni.h>
#include "llama.h"
#include <atomic>
#include <chrono>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <stdexcept>
#include <android/log.h>
static std::atomic<int64_t> cancelledThrough{0};
static std::once_flag initialized;
struct Guard {int64_t id;std::chrono::steady_clock::time_point until;};
static bool aborted(void *p) {auto *g=(Guard*)p;return cancelledThrough.load()>=g->id || std::chrono::steady_clock::now()>g->until;}
static std::string bytes(JNIEnv *env,jbyteArray b) {std::string v(env->GetArrayLength(b),'\0');env->GetByteArrayRegion(b,0,v.size(),(jbyte*)v.data());return v;}
extern "C" JNIEXPORT void JNICALL Java_com_kongye_keyboard_NativePolish_cancel(JNIEnv*,jobject,jlong id) {
 auto old=cancelledThrough.load();while(old<id && !cancelledThrough.compare_exchange_weak(old,id)){}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_kongye_keyboard_NativePolish_rewrite(JNIEnv *env,jobject,jbyteArray file,jbyteArray input,jlong id) {
 try {
  Guard guard{id,std::chrono::steady_clock::now()+std::chrono::seconds(65)};
  if(aborted(&guard))throw std::runtime_error("已取消润色");
  std::call_once(initialized,[]{
   auto log=[](ggml_log_level level,const char *text,void*) {if(level==GGML_LOG_LEVEL_ERROR)__android_log_write(ANDROID_LOG_ERROR,"KongyePolish",text);};
   llama_log_set(log,nullptr);ggml_log_set(log,nullptr);llama_backend_init();
  });
  auto mp=llama_model_default_params();mp.n_gpu_layers=0;mp.load_mode=LLAMA_LOAD_MODE_MMAP;mp.progress_callback=[](float,void *data){return !aborted(data);};mp.progress_callback_user_data=&guard;
  auto path=bytes(env,file);auto prompt=bytes(env,input);
  std::unique_ptr<llama_model,decltype(&llama_model_free)> model(llama_model_load_from_file(path.c_str(),mp),llama_model_free);
  if(!model)throw std::runtime_error("离线模型加载失败");
  const auto *vocab=llama_model_get_vocab(model.get());
  int count=-llama_tokenize(vocab,prompt.data(),prompt.size(),nullptr,0,true,true);
  if(count<=0 || count>1400)throw std::runtime_error("内容过长，请分段润色");
  std::vector<llama_token> tokens(count);
  if(llama_tokenize(vocab,prompt.data(),prompt.size(),tokens.data(),tokens.size(),true,true)<0)throw std::runtime_error("无法读取输入内容");
  auto cp=llama_context_default_params();cp.n_ctx=2048;cp.n_batch=256;cp.n_ubatch=128;
  cp.n_threads=2;cp.n_threads_batch=3;cp.abort_callback=aborted;cp.abort_callback_data=&guard;
  std::unique_ptr<llama_context,decltype(&llama_free)> ctx(llama_init_from_model(model.get(),cp),llama_free);
  if(!ctx)throw std::runtime_error("内存不足，无法开始离线润色");
  std::unique_ptr<llama_batch_ext,decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(ctx.get()),llama_batch_ext_free);
  auto process=[&](const llama_token *data,int n,int start) {
   if(aborted(&guard))throw std::runtime_error("润色已取消或超时，原文已保留");
   llama_batch_ext_clear(batch.get());
   for(int i=0;i<n;i++){auto idx=llama_batch_ext_add_token(batch.get(),0,data[i]);llama_pos pos=start+i;llama_batch_ext_set_pos(batch.get(),idx,&pos);}
   llama_batch_ext_set_output_logits(batch.get(),n-1,true);
   if(llama_process(ctx.get(),LLAMA_PROCESS_TYPE_DECODE,batch.get())!=0)throw std::runtime_error("润色未完成，原文已保留");
  };
  for(int at=0;at<count;at+=256)process(tokens.data()+at,std::min(256,count-at),at);
  std::unique_ptr<llama_sampler,decltype(&llama_sampler_free)> sampler(llama_sampler_init_greedy(),llama_sampler_free);
  std::string output;bool finished=false;
  for(int step=0;step<384;step++) {
   if(aborted(&guard))throw std::runtime_error("润色已取消或超时，原文已保留");
   auto token=llama_sampler_sample(sampler.get(),ctx.get(),-1);
   if(llama_vocab_is_eog(vocab,token)){finished=true;break;}
   std::vector<char> piece(256);int n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);
   if(n<0){piece.resize(-n);n=llama_token_to_piece(vocab,token,piece.data(),piece.size(),0,false);}
   if(n<0)throw std::runtime_error("无法读取模型结果");
   output.append(piece.data(),n);process(&token,1,count+step);
  }
  if(!finished)throw std::runtime_error("结果过长，原文已保留");
  auto result=env->NewByteArray(output.size());env->SetByteArrayRegion(result,0,output.size(),(const jbyte*)output.data());return result;
 } catch(const std::exception &e) {env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());return nullptr;}
}
