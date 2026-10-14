#include <jni.h>
#include "llama.h"
#include "hy_mt_prompt.h"
#include <algorithm>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr int context_tokens = 1024;
constexpr int output_tokens = 256;
constexpr int prompt_tokens = context_tokens - output_tokens;
constexpr int batch_tokens = 64;
constexpr size_t max_output_bytes = 16384;
struct cancelled : std::exception {};

// ggml can poll from a worker thread, so never share JNIEnv across threads.
struct cancellation {
    JavaVM * vm = nullptr;
    jobject object;
    jmethodID method;
    cancellation(JNIEnv * env, jobject source) {
        env->GetJavaVM(&vm);
        object = env->NewGlobalRef(source);
        jclass cls = env->GetObjectClass(source);
        method = env->GetMethodID(cls, "isCancelled", "()Z");
        env->DeleteLocalRef(cls);
        if (!object || !method) {
            if (object) env->DeleteGlobalRef(object);
            throw std::runtime_error("Cancellation callback unavailable");
        }
    }
    ~cancellation() {
        JNIEnv * env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(object);
    }
    bool stopped() const {
        JNIEnv * env = nullptr;
        bool attached = false;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
            if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
#else
            if (vm->AttachCurrentThread(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return true;
#endif
            attached = true;
        }
        bool stop = env->CallBooleanMethod(object, method);
        if (env->ExceptionCheck()) { env->ExceptionClear(); stop = true; }
        if (attached) vm->DetachCurrentThread();
        return stop;
    }
    void check() const { if (stopped()) throw cancelled(); }
    static bool abort(void * data) { return static_cast<cancellation *>(data)->stopped(); }
    static bool progress(float, void * data) { return !abort(data); }
};

struct engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    ~engine() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

struct reset_context {
    llama_context * context;
    explicit reset_context(llama_context * ctx, cancellation & cancel) : context(ctx) {
        llama_memory_clear(llama_get_memory(context), true);
        llama_set_abort_callback(context, cancellation::abort, &cancel);
    }
    ~reset_context() {
        llama_synchronize(context);
        llama_set_abort_callback(context, nullptr, nullptr);
        llama_memory_clear(llama_get_memory(context), true);
    }
};

std::string bytes(JNIEnv * env, jbyteArray input, int maximum) {
    if (!input) throw std::runtime_error("Missing local model input");
    const auto count = env->GetArrayLength(input);
    if (count > maximum) throw std::runtime_error("Local model input limit exceeded");
    std::string value(static_cast<size_t>(count), '\0');
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(value.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Could not read local model input");
    if (value.find('\0') != std::string::npos) throw std::runtime_error("Invalid local model input");
    return value;
}

void fail(JNIEnv * env, const char * type, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    // The verified template already emits BOS; never add a second one.
    const int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, false, true);
    if (count >= 0 || -count > prompt_tokens) throw std::runtime_error("Translation exceeds local model context");
    std::vector<llama_token> tokens(static_cast<size_t>(-count));
    const int actual = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(),
                                      static_cast<int>(tokens.size()), false, true);
    if (actual != static_cast<int>(tokens.size())) throw std::runtime_error("Local tokenizer failed");
    return tokens;
}

std::string infer(engine & owned, const std::string & message, cancellation & cancel) {
    cancel.check();
    reset_context reset(owned.context, cancel);
    const auto * vocab = llama_model_get_vocab(owned.model);
    const auto prompt = hy_mt::render_single_user(llama_model_chat_template(owned.model, nullptr), message);
    auto tokens = tokenize(vocab, prompt);
    auto chain_params = llama_sampler_chain_default_params();
    chain_params.no_perf = true;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(chain_params), llama_sampler_free);
    // Same effective chain as the official CLI experiment (disabled samplers omitted).
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.f, 0.f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(42));
    for (auto token : tokens) llama_sampler_accept(sampler.get(), token);
    for (size_t start = 0; start < tokens.size(); start += batch_tokens) {
        cancel.check();
        const int count = static_cast<int>(std::min<size_t>(batch_tokens, tokens.size() - start));
        const int result = llama_decode(owned.context, llama_batch_get_one(tokens.data() + start, count));
        cancel.check();
        if (result != 0) throw std::runtime_error("Local model prompt evaluation failed");
    }
    std::string output;
    for (int n = 0; n < output_tokens; ++n) {
        cancel.check();
        llama_token token = llama_sampler_sample(sampler.get(), owned.context, -1); // accepts token once
        if (llama_vocab_is_eog(vocab, token)) { cancel.check(); return output; }
        char piece[256];
        int count = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (count < 0) {
            if (-count > static_cast<int>(max_output_bytes)) throw std::runtime_error("Local model token limit exceeded");
            std::vector<char> large(static_cast<size_t>(-count));
            count = llama_token_to_piece(vocab, token, large.data(), static_cast<int>(large.size()), 0, false);
            if (count < 0) throw std::runtime_error("Local model decoding failed");
            output.append(large.data(), static_cast<size_t>(count));
        } else {
            output.append(piece, static_cast<size_t>(count));
        }
        if (output.size() > max_output_bytes) throw std::runtime_error("Local model output limit exceeded");
        cancel.check();
        if (n + 1 == output_tokens) break;
        const int result = llama_decode(owned.context, llama_batch_get_one(&token, 1));
        cancel.check();
        if (result != 0) throw std::runtime_error("Local model generation failed");
    }
    // No EOG means incomplete output. Never publish a silently truncated translation.
    throw std::runtime_error("Local translation did not finish within the token limit");
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_framenest_feature_listen_1translate_mt_HyMtJni_nativeOpen(
        JNIEnv * env, jobject, jbyteArray path_bytes, jint threads, jobject cancellation_object) {
    try {
        cancellation cancel(env, cancellation_object);
        cancel.check();
        auto path = bytes(env, path_bytes, 4096);
        if (path.empty() || path.front() != '/' || threads < 1 || threads > 4) throw std::runtime_error("Invalid local model configuration");
        static std::once_flag initialized;
        std::call_once(initialized, [] {
            // Avoid writing model paths, tokens or subtitles to Android logs.
            llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
            llama_backend_init();
        });
        std::unique_ptr<engine> owned(new engine());
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        model_params.progress_callback = cancellation::progress;
        model_params.progress_callback_user_data = &cancel;
        owned->model = llama_model_load_from_file(path.c_str(), model_params);
        cancel.check();
        if (!owned->model) throw std::runtime_error("Unable to load verified local translation model");
        hy_mt::render_single_user(llama_model_chat_template(owned->model, nullptr), "");
        auto params = llama_context_default_params();
        params.n_ctx = context_tokens;
        params.n_batch = batch_tokens;
        params.n_ubatch = batch_tokens;
        params.n_seq_max = 1;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.offload_kqv = false;
        params.op_offload = false;
        params.no_perf = true;
        params.abort_callback = cancellation::abort;
        params.abort_callback_data = &cancel;
        owned->context = llama_init_from_model(owned->model, params);
        cancel.check();
        if (!owned->context) throw std::runtime_error("Unable to allocate local translation context");
        llama_set_abort_callback(owned->context, nullptr, nullptr);
        return reinterpret_cast<jlong>(owned.release());
    } catch (const cancelled &) {
        fail(env, "java/util/concurrent/CancellationException", "Local translation cancelled");
    } catch (const std::exception &) {
        fail(env, "java/lang/IllegalStateException", "Unable to initialize the local translation model");
    }
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_framenest_feature_listen_1translate_mt_HyMtJni_nativeTranslate(
        JNIEnv * env, jobject, jlong handle, jbyteArray message_bytes, jobject cancellation_object) {
    try {
        if (!handle) throw std::runtime_error("Local translation context is closed");
        cancellation cancel(env, cancellation_object);
        const auto output = infer(*reinterpret_cast<engine *>(handle), bytes(env, message_bytes, 9216), cancel);
        cancel.check();
        auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
        return result;
    } catch (const cancelled &) {
        fail(env, "java/util/concurrent/CancellationException", "Local translation cancelled");
    } catch (const std::exception &) {
        fail(env, "java/lang/IllegalStateException", "Local translation failed or exceeded its bounded context/output");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_framenest_feature_listen_1translate_mt_HyMtJni_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<engine *>(handle);
}
